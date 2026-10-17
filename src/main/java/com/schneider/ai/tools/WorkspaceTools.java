package com.schneider.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.schneider.ai.tools.JfrogTools.fail;
import static com.schneider.ai.tools.JfrogTools.ok;

/**
 * Sandboxed local workspace for code changes (for example vulnerability fixes): clone an organisation repository,
 * work on a feature branch, edit files, run Maven, commit, push and open a pull request.
 *
 * <p>Safety rules enforced here (not only in the prompt): everything stays below the workspace directory; only
 * repositories of the configured organisation are cloned; commits and pushes are only possible on {@code feature/*}
 * branches; Maven is limited to an allow-list of goals and flags (no deploy); build outputs, key stores and secrets
 * cannot be committed; nothing is ever merged.</p>
 */
@Component
public class WorkspaceTools {

    static final Pattern REPO_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");
    static final Pattern FEATURE_BRANCH = Pattern.compile("^feature/[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*$");
    static final Set<String> MAVEN_GOALS = Set.of("clean", "validate", "compile", "test-compile", "test", "package",
            "verify", "install", "dependency:tree", "dependency:resolve", "dependency:list", "dependency:analyze",
            "versions:display-dependency-updates", "versions:display-property-updates", "help:effective-pom");
    private static final Pattern MAVEN_FLAG = Pattern.compile(
            "^(-q|-B|-U|-o|-e|-am|-amd|-fae|-fn|-ff|-ntp|--fail-at-end|--fail-never|--also-make|--also-make-dependents|"
                    + "--batch-mode|--quiet|--update-snapshots|--offline|--no-transfer-progress)$");
    private static final Pattern MAVEN_PROPERTY = Pattern.compile("^-D([A-Za-z0-9_.\\-]+)(=[A-Za-z0-9_.,:/\\\\*!#+\\-]*)?$");
    private static final Pattern MAVEN_VALUE = Pattern.compile("^[A-Za-z0-9_.,:/\\\\!?\\-]+$");
    private static final Set<String> MAVEN_VALUE_FLAGS = Set.of("-pl", "-rf", "-T");
    private static final Pattern FORBIDDEN_PROPERTY = Pattern.compile("^(maven\\.repo|maven\\.multiModule|user\\.|java\\.|file\\.|settings|org\\.apache\\.maven\\.user)");
    private static final Pattern BLOCKED_COMMIT_PATH = Pattern.compile(
            "(^|/)(target|node_modules|\\.idea|\\.vscode)/|(^|/)\\.env($|\\.)|\\.(jar|war|ear|class|zip|jks|p12|pfx|pem|key|keystore)$");
    private static final Pattern SECRET = Pattern.compile(
            "(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|gh[ousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----|cmVmdGtu[A-Za-z0-9+/=]{20,}|eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,})");
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target", "node_modules", ".idea", ".mvn_repo");
    private static final Set<String> PROTECTED_BRANCHES = Set.of("develop", "development", "dev", "master", "main",
            "trunk", "staging", "production", "prod");

    private static final int MAX_READ_CHARS = 30_000;
    private static final int MAX_FILE_BYTES = 1_000_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate rest;
    private final ReentrantLock buildLock = new ReentrantLock();

    @Value("${workspace.dir:${user.home}/.emh-ai-workspace}")
    private String workspaceDir;

    @Value("${workspace.git.command:git}")
    private String gitCommand;

    @Value("${workspace.maven.command:}")
    private String mavenCommand;

    @Value("${workspace.maven.default-timeout-minutes:25}")
    private int mavenDefaultTimeout;

    @Value("${workspace.maven.max-timeout-minutes:45}")
    private int mavenMaxTimeout;

    @Value("${workspace.git.user-name:EMH AI Assistant}")
    private String gitUserName;

    @Value("${workspace.git.user-email:emh-ai-assistant@se.com}")
    private String gitUserEmail;

    @Value("${dashboard.github.base-url:https://github.schneider-electric.com/api/v3}")
    private String githubApi;

    @Value("${dashboard.github.web-url:https://github.schneider-electric.com}")
    private String githubWeb;

    @Value("${dashboard.github.org:ifw-microservices}")
    private String githubOrg;

    @Value("${dashboard.github.token:}")
    private String githubToken;

    @Value("${jfrog.token:}")
    private String jfrogToken;

    public WorkspaceTools() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(20_000);
        factory.setReadTimeout(60_000);
        this.rest = new RestTemplate(factory);
    }

    // =====================================================================================================
    // clone / branch
    // =====================================================================================================

    @Tool(name = "workspace_cloneRepo", description = """
            Clone (or refresh) an organisation Git repository into the local workspace and check out its latest default
            branch (develop/master/main). Always call this first when code has to be read or changed. Returns the local
            state: branch, commit, root files, Maven modules, java version and the base image from the root pom.
            If the repository is already in the workspace it is reset to the latest remote state (local changes are
            discarded).""")
    public Map<String, Object> cloneRepo(
            @ToolParam(description = "Repository name in the organisation, e.g. mdm-integration") String repo,
            @ToolParam(required = false, description = "Branch to check out; default = the repository's default branch") String branch) {
        return guarded(() -> {
            String name = validateRepo(repo);
            JsonNode info = githubGet("/repos/" + githubOrg + "/" + name);
            String defaultBranch = info.path("default_branch").asText("master");
            String base = notBlank(branch) ? branch.trim() : defaultBranch;
            validateRef(base);
            Path root = root();
            Files.createDirectories(root);
            Path dir = root.resolve(name);
            String url = githubWeb + "/" + githubOrg + "/" + name + ".git";
            if (!Files.isDirectory(dir.resolve(".git"))) {
                requireOk(git(root, Duration.ofMinutes(15), "clone", "--depth", "50", "--single-branch", "--branch", base,
                        url, name), "git clone");
            } else {
                String origin = requireOk(git(dir, Duration.ofMinutes(1), "remote", "get-url", "origin"), "git remote").trim();
                if (!origin.equalsIgnoreCase(url)) {
                    throw new IllegalStateException("Workspace folder " + name + " belongs to another remote (" + origin + ")");
                }
                requireOk(git(dir, Duration.ofMinutes(5), "fetch", "--depth", "50", "origin",
                        "+refs/heads/" + base + ":refs/remotes/origin/" + base), "git fetch");
                requireOk(git(dir, Duration.ofMinutes(1), "checkout", "-f", "-B", base, "origin/" + base), "git checkout");
                requireOk(git(dir, Duration.ofMinutes(1), "reset", "--hard", "origin/" + base), "git reset");
                requireOk(git(dir, Duration.ofMinutes(1), "clean", "-fd"), "git clean");
            }
            String head = requireOk(git(dir, Duration.ofMinutes(1), "rev-parse", "--short", "HEAD"), "git rev-parse").trim();
            Map<String, Object> result = ok("repo", name, "path", dir.toString(), "branch", base,
                    "defaultBranch", defaultBranch, "commit", head, "url", githubWeb + "/" + githubOrg + "/" + name);
            result.put("rootEntries", listDir(dir, dir, 80));
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                String text = Files.readString(pom, StandardCharsets.UTF_8);
                result.put("modules", matchAll(text, "<module>\\s*([^<\\s]+)\\s*</module>", 80));
                result.put("javaVersion", firstMatch(text, "<java.version>\\s*([^<]+)\\s*</java.version>"));
                result.put("baseImage", firstMatch(text, "<from>\\s*<image>\\s*([^<]+)\\s*</image>"));
                result.put("version", firstMatch(text.replaceAll("(?s)<parent>.*?</parent>", ""),
                        "<version>\\s*([^<]+)\\s*</version>"));
            }
            return result;
        });
    }

    @Tool(name = "workspace_createBranch", description = """
            Create and check out a new feature branch in the cloned repository, based on the current HEAD. The name must
            start with 'feature/', for example feature/CMDM-8449 or feature/vulnerability-fix-1.90.0. Fails if the branch
            already exists on the remote.""")
    public Map<String, Object> createBranch(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "New branch name starting with feature/") String branch) {
        return guarded(() -> {
            Path dir = repoDir(repo);
            String name = validateFeatureBranch(branch);
            String remote = requireOk(git(dir, Duration.ofMinutes(2), "ls-remote", "--heads", "origin", name), "git ls-remote");
            if (!remote.isBlank()) {
                throw new IllegalStateException("Branch " + name + " already exists on origin. Choose another name "
                        + "(for example add a suffix) or ask the user.");
            }
            requireOk(git(dir, Duration.ofMinutes(1), "checkout", "-B", name), "git checkout -B");
            return ok("repo", repo, "branch", name, "basedOn", requireOk(
                    git(dir, Duration.ofMinutes(1), "rev-parse", "--short", "HEAD~0"), "git rev-parse").trim());
        });
    }

    // =====================================================================================================
    // files
    // =====================================================================================================

    @Tool(name = "workspace_listFiles", description = """
            List files of the cloned repository. Without 'glob' it lists one directory; with a glob such as
            '**/pom.xml' or '**/Dockerfile' it searches recursively (target/, .git and node_modules are skipped).""")
    public Map<String, Object> listFiles(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(required = false, description = "Directory relative to the repository root (default: root)") String path,
            @ToolParam(required = false, description = "Recursive glob, e.g. **/pom.xml") String glob,
            @ToolParam(required = false, description = "Maximum entries (default 200)") Integer maxResults) {
        return guarded(() -> {
            Path dir = repoDir(repo);
            Path base = resolveInside(dir, notBlank(path) ? path : ".");
            int max = maxResults == null ? 200 : Math.max(1, Math.min(maxResults, 1000));
            List<String> entries = new ArrayList<>();
            if (notBlank(glob)) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob.trim());
                try (Stream<Path> walk = Files.walk(base)) {
                    Iterator<Path> it = walk.filter(p -> !hasSkippedSegment(dir, p)).iterator();
                    while (it.hasNext() && entries.size() < max) {
                        Path p = it.next();
                        Path rel = dir.relativize(p);
                        if (Files.isRegularFile(p) && (matcher.matches(rel) || matcher.matches(rel.getFileName()))) {
                            entries.add(slash(rel));
                        }
                    }
                }
            } else {
                entries.addAll(listDir(dir, base, max));
            }
            return ok("repo", repo, "path", notBlank(path) ? path : ".", "count", entries.size(), "entries", entries);
        });
    }

    @Tool(name = "workspace_readFile", description = """
            Read a text file from the cloned repository (optionally a line range). Large files are cut; use startLine and
            endLine to page through them.""")
    public Map<String, Object> readFile(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "File path relative to the repository root") String path,
            @ToolParam(required = false, description = "First line (1-based, default 1)") Integer startLine,
            @ToolParam(required = false, description = "Last line (default: first line + 400)") Integer endLine) {
        return guarded(() -> {
            Path file = resolveInside(repoDir(repo), path);
            requireTextFile(file);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int to = endLine == null ? from + 399 : Math.min(lines.size(), Math.max(from, endLine));
            StringBuilder out = new StringBuilder();
            int shown = from - 1;
            for (int i = from - 1; i < to && i < lines.size(); i++) {
                if (out.length() + lines.get(i).length() > MAX_READ_CHARS) {
                    break;
                }
                out.append(lines.get(i)).append('\n');
                shown = i + 1;
            }
            return ok("path", path, "startLine", from, "endLine", shown, "totalLines", lines.size(),
                    "truncated", shown < Math.min(to, lines.size()), "content", out.toString());
        });
    }

    @Tool(name = "workspace_searchText", description = """
            Search text in the cloned repository (like grep). Returns file, line number and the matching line. Use it to
            find where a dependency version, property or class is used. Skips target/, .git and binary files.""")
    public Map<String, Object> searchText(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Text (or regular expression when regex=true) to find") String query,
            @ToolParam(required = false, description = "Only files matching this glob, e.g. **/pom.xml") String fileGlob,
            @ToolParam(required = false, description = "Treat the query as a regular expression (default false)") Boolean regex,
            @ToolParam(required = false, description = "Maximum matches (default 60)") Integer maxResults) {
        return guarded(() -> {
            Path dir = repoDir(repo);
            if (!notBlank(query)) {
                throw new IllegalArgumentException("query is required");
            }
            Pattern pattern = Boolean.TRUE.equals(regex) ? Pattern.compile(query) : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
            PathMatcher matcher = notBlank(fileGlob) ? FileSystems.getDefault().getPathMatcher("glob:" + fileGlob.trim()) : null;
            int max = maxResults == null ? 60 : Math.max(1, Math.min(maxResults, 300));
            List<Map<String, Object>> matches = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir)) {
                Iterator<Path> it = walk.filter(p -> Files.isRegularFile(p) && !hasSkippedSegment(dir, p)).iterator();
                while (it.hasNext() && matches.size() < max) {
                    Path p = it.next();
                    Path rel = dir.relativize(p);
                    if (matcher != null && !(matcher.matches(rel) || matcher.matches(rel.getFileName()))) {
                        continue;
                    }
                    try {
                        if (Files.size(p) > MAX_FILE_BYTES) {
                            continue;
                        }
                        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size() && matches.size() < max; i++) {
                            if (pattern.matcher(lines.get(i)).find()) {
                                matches.add(Map.of("file", slash(rel), "line", i + 1, "text", abbreviate(lines.get(i).strip(), 240)));
                            }
                        }
                    } catch (IOException binaryOrUnreadable) {
                        // skip binary / non UTF-8 files
                    }
                }
            }
            return ok("repo", repo, "count", matches.size(), "truncated", matches.size() >= max, "matches", matches);
        });
    }

    @Tool(name = "workspace_replaceInFile", description = """
            Replace exact text in a file of the cloned repository. 'oldText' must appear exactly once (or set
            replaceAll=true). Include enough surrounding lines to make it unique. Prefer this over rewriting a whole
            file. Line endings of the file are preserved.""")
    public Map<String, Object> replaceInFile(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "File path relative to the repository root") String path,
            @ToolParam(description = "Exact text to replace") String oldText,
            @ToolParam(description = "Replacement text") String newText,
            @ToolParam(required = false, description = "Replace every occurrence (default false)") Boolean replaceAll) {
        return guarded(() -> {
            Path file = resolveInside(repoDir(repo), path);
            requireTextFile(file);
            if (oldText == null || oldText.isEmpty()) {
                throw new IllegalArgumentException("oldText must not be empty");
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            boolean crlf = content.contains("\r\n");
            String from = crlf ? oldText.replace("\r\n", "\n").replace("\n", "\r\n") : oldText.replace("\r\n", "\n");
            String to = newText == null ? "" : (crlf ? newText.replace("\r\n", "\n").replace("\n", "\r\n") : newText.replace("\r\n", "\n"));
            int count = countOccurrences(content, from);
            if (count == 0) {
                throw new IllegalStateException("oldText not found in " + path + ". Read the file again and copy the text exactly.");
            }
            if (count > 1 && !Boolean.TRUE.equals(replaceAll)) {
                throw new IllegalStateException("oldText occurs " + count + " times in " + path
                        + ". Add surrounding lines to make it unique or set replaceAll=true.");
            }
            Files.writeString(file, content.replace(from, to), StandardCharsets.UTF_8);
            return ok("path", path, "replacements", count);
        });
    }

    @Tool(name = "workspace_writeFile", description = """
            Create or overwrite a text file in the cloned repository with the given content (use for new files, such as a
            new test; use workspace_replaceInFile for small edits).""")
    public Map<String, Object> writeFile(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "File path relative to the repository root") String path,
            @ToolParam(description = "Complete new file content") String content) {
        return guarded(() -> {
            Path file = resolveInside(repoDir(repo), path);
            if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("content is missing or larger than 1 MB");
            }
            String rel = slash(repoDir(repo).relativize(file));
            if (BLOCKED_COMMIT_PATH.matcher(rel).find()) {
                throw new IllegalArgumentException("Writing " + rel + " is not allowed (build output, key material or environment file).");
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return ok("path", rel, "bytes", content.getBytes(StandardCharsets.UTF_8).length);
        });
    }

    // =====================================================================================================
    // build
    // =====================================================================================================

    @Tool(name = "workspace_runMaven", description = """
            Run Maven in the cloned repository. 'goals' is a space separated list from: clean, validate, compile,
            test-compile, test, package, verify, install, dependency:tree, dependency:resolve, dependency:list,
            dependency:analyze, versions:display-dependency-updates, versions:display-property-updates,
            help:effective-pom. 'args' may contain -pl <modules>, -am, -amd, -rf <module>, -DskipTests, -Dtest=Name,
            -DfailIfNoTests=false, -fae, -U, -o. Deploy and release are not possible. To save time build only the changed
            modules, e.g. goals 'clean install' args '-pl bfo-processor -am'. Returns success, test summary, the
            [ERROR] lines and the tail of the output; the full log is stored under the returned logFile.""")
    public Map<String, Object> runMaven(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Goals, e.g. 'clean install'") String goals,
            @ToolParam(required = false, description = "Extra arguments, e.g. '-pl bfo-processor -am -DskipTests'") String args,
            @ToolParam(required = false, description = "Sub-directory with the pom to build (default: repository root)") String workingDir,
            @ToolParam(required = false, description = "Timeout in minutes (default 25, max 45)") Integer timeoutMinutes) {
        return guarded(() -> {
            Path dir = repoDir(repo);
            Path cwd = notBlank(workingDir) ? resolveInside(dir, workingDir) : dir;
            if (!Files.isRegularFile(cwd.resolve("pom.xml"))) {
                throw new IllegalArgumentException("No pom.xml in " + dir.relativize(cwd) + ". Use workingDir or build from the repository root.");
            }
            List<String> command = new ArrayList<>();
            command.add(mavenExecutable());
            command.addAll(buildMavenArguments(goals, args));
            int minutes = timeoutMinutes == null ? mavenDefaultTimeout : Math.max(1, Math.min(timeoutMinutes, mavenMaxTimeout));
            if (!buildLock.tryLock()) {
                throw new IllegalStateException("Another Maven build is already running in the workspace. Try again when it has finished.");
            }
            try {
                Path logs = Files.createDirectories(root().resolve("_logs"));
                Path log = logs.resolve(repo + "-mvn-" + Instant.now().toString().replace(':', '-') + ".log");
                ProcessRunner.Result result = ProcessRunner.run(command, cwd, Map.of("MAVEN_OPTS", mavenOpts()),
                        Duration.ofMinutes(minutes), Pattern.compile("^\\[(ERROR|WARNING)\\] (?!.*\\[Help)|Tests run:.*(FAIL|ERROR)|BUILD (FAILURE|SUCCESS)"),
                        log, this::scrub);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("success", result.success());
                out.put("exitCode", result.exitCode());
                out.put("timedOut", result.timedOut());
                out.put("durationSeconds", result.millis() / 1000);
                out.put("command", "mvn " + String.join(" ", command.subList(1, command.size())));
                out.put("testSummary", lastMatching(result.tail(), Pattern.compile("Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+$")));
                out.put("reactor", reactorSummary(result.tail()));
                List<String> errors = result.matches().stream().filter(l -> l.startsWith("[ERROR]")).limit(60).toList();
                if (!result.success()) {
                    out.put("errors", errors);
                }
                out.put("outputTail", result.tailText(result.success() ? 25 : 80, result.success() ? 3000 : 9000));
                out.put("logFile", log.toString());
                if (result.timedOut()) {
                    out.put("error", "Maven did not finish within " + minutes + " minutes and was stopped.");
                }
                return out;
            } finally {
                buildLock.unlock();
            }
        });
    }

    // =====================================================================================================
    // git: status / diff / commit / PR
    // =====================================================================================================

    @Tool(name = "workspace_gitStatus", description = "Show the current branch and the changed files of the cloned repository, with a diff summary.")
    public Map<String, Object> gitStatus(@ToolParam(description = "Repository name") String repo) {
        return guarded(() -> {
            Path dir = repoDir(repo);
            String branch = currentBranch(dir);
            List<String> changed = lines(requireOk(git(dir, Duration.ofMinutes(1), "status", "--porcelain"), "git status"));
            String stat = requireOk(git(dir, Duration.ofMinutes(1), "diff", "--stat", "HEAD"), "git diff --stat");
            return ok("repo", repo, "branch", branch, "changedFiles", changed.stream().limit(200).toList(),
                    "changedCount", changed.size(), "diffStat", abbreviate(stat, 4000));
        });
    }

    @Tool(name = "workspace_gitDiff", description = "Show the unified diff of the uncommitted changes (optionally of one file) to review before committing.")
    public Map<String, Object> gitDiff(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(required = false, description = "Limit the diff to this file path") String path) {
        return guarded(() -> {
            Path dir = repoDir(repo);
            List<String> args = new ArrayList<>(List.of("diff", "HEAD"));
            if (notBlank(path)) {
                args.add("--");
                args.add(slash(dir.relativize(resolveInside(dir, path))));
            }
            String diff = requireOk(git(dir, Duration.ofMinutes(1), args.toArray(String[]::new)), "git diff");
            return ok("repo", repo, "diff", abbreviate(diff, 20_000), "truncated", diff.length() > 20_000);
        });
    }

    @Tool(name = "workspace_commitAndPush", description = """
            Commit all changes of the cloned repository and push the current branch to origin. Only works on a feature/
            branch (never on develop/master/main/release). Build output (target/), key stores and secrets cannot be
            committed. Use a message like 'CMDM-8449: fix summary' or 'Fix vulnerabilities in bfo-processor 1.90.0'.""")
    public Map<String, Object> commitAndPush(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Commit message (first line = summary)") String message) {
        return guarded(() -> {
            Path dir = repoDir(repo);
            String branch = currentBranch(dir);
            validateFeatureBranch(branch);
            if (!notBlank(message) || message.length() > 2000) {
                throw new IllegalArgumentException("A commit message (max 2000 characters) is required");
            }
            requireOk(git(dir, Duration.ofMinutes(2), "add", "-A"), "git add");
            List<String> staged = lines(requireOk(git(dir, Duration.ofMinutes(1), "diff", "--cached", "--name-only"), "git diff --cached"));
            if (staged.isEmpty()) {
                throw new IllegalStateException("Nothing to commit - the working tree has no changes.");
            }
            List<String> blocked = staged.stream().filter(p -> BLOCKED_COMMIT_PATH.matcher(p).find()).toList();
            if (!blocked.isEmpty()) {
                git(dir, Duration.ofMinutes(1), "reset");
                throw new IllegalStateException("Refusing to commit build output / key material / environment files: " + blocked
                        + ". Remove or ignore them and try again.");
            }
            String added = requireOk(git(dir, Duration.ofMinutes(1), "diff", "--cached", "-U0"), "git diff --cached -U0");
            String secret = findSecret(added);
            if (secret != null) {
                git(dir, Duration.ofMinutes(1), "reset");
                throw new IllegalStateException("Refusing to commit: the change contains what looks like a secret (" + secret + ").");
            }
            requireOk(git(dir, Duration.ofMinutes(3), "-c", "user.name=" + gitUserName, "-c", "user.email=" + gitUserEmail,
                    "commit", "-m", message), "git commit");
            String sha = requireOk(git(dir, Duration.ofMinutes(1), "rev-parse", "--short", "HEAD"), "git rev-parse").trim();
            requireOk(git(dir, Duration.ofMinutes(10), "push", "-u", "origin", "refs/heads/" + branch + ":refs/heads/" + branch), "git push");
            return ok("repo", repo, "branch", branch, "commit", sha, "filesCommitted", staged.size(),
                    "branchUrl", githubWeb + "/" + githubOrg + "/" + repo + "/tree/" + branch);
        });
    }

    @Tool(name = "workspace_createPullRequest", description = """
            Open a pull request from the current (pushed) feature branch into the repository's default branch and return
            the PR link for the user to review. Never merges. The title for Jira work is '<JIRA-KEY>: <summary>'. If a PR
            for the branch already exists its link is returned.""")
    public Map<String, Object> createPullRequest(
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Pull request title") String title,
            @ToolParam(description = "Pull request description (Markdown): what changed, why, how it was verified") String body,
            @ToolParam(required = false, description = "Target branch; default = the repository's default branch") String baseBranch) {
        return guarded(() -> {
            Path dir = repoDir(repo);
            String head = validateFeatureBranch(currentBranch(dir));
            if (!notBlank(title) || title.length() > 250) {
                throw new IllegalArgumentException("A title (max 250 characters) is required");
            }
            String remote = requireOk(git(dir, Duration.ofMinutes(2), "ls-remote", "--heads", "origin", head), "git ls-remote");
            if (remote.isBlank()) {
                throw new IllegalStateException("Branch " + head + " is not on origin yet. Call workspace_commitAndPush first.");
            }
            JsonNode repoInfo = githubGet("/repos/" + githubOrg + "/" + repo);
            String base = notBlank(baseBranch) ? baseBranch.trim() : repoInfo.path("default_branch").asText("master");
            if (head.equals(base)) {
                throw new IllegalArgumentException("Head and base branch are identical");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title.trim());
            payload.put("head", head);
            payload.put("base", base);
            payload.put("body", body == null ? "" : abbreviate(body, 60_000));
            try {
                JsonNode pr = githubPost("/repos/" + githubOrg + "/" + repo + "/pulls", payload);
                return ok("repo", repo, "number", pr.path("number").asInt(), "url", pr.path("html_url").asText(),
                        "head", head, "base", base, "state", pr.path("state").asText());
            } catch (HttpStatusCodeException ex) {
                if (ex.getStatusCode().value() == 422) {
                    JsonNode existing = githubGet("/repos/" + githubOrg + "/" + repo + "/pulls?state=open&head="
                            + githubOrg + ":" + head);
                    if (existing.isArray() && !existing.isEmpty()) {
                        JsonNode pr = existing.get(0);
                        return ok("repo", repo, "number", pr.path("number").asInt(), "url", pr.path("html_url").asText(),
                                "head", head, "base", base, "alreadyExisted", true);
                    }
                    throw new IllegalStateException("GitHub rejected the pull request: " + abbreviate(ex.getResponseBodyAsString(), 600)
                            + " (is there any difference between " + head + " and " + base + "?)");
                }
                throw ex;
            }
        });
    }

    // =====================================================================================================
    // helpers (package-private static ones are unit-tested)
    // =====================================================================================================

    @FunctionalInterface
    private interface Body {
        Map<String, Object> run() throws Exception;
    }

    private Map<String, Object> guarded(Body body) {
        try {
            return body.run();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return fail(scrub(ex.getMessage()), null);
        } catch (HttpStatusCodeException ex) {
            return fail("GitHub API error HTTP " + ex.getStatusCode().value() + ": " + abbreviate(scrub(ex.getResponseBodyAsString()), 500), null);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return fail(scrub(cause.getClass().getSimpleName() + ": " + cause.getMessage()), null);
        }
    }

    private Path root() {
        return Path.of(workspaceDir).toAbsolutePath().normalize();
    }

    private Path repoDir(String repo) {
        String name = validateRepo(repo);
        Path dir = root().resolve(name);
        if (!Files.isDirectory(dir.resolve(".git"))) {
            throw new IllegalStateException("Repository " + name + " is not cloned yet. Call workspace_cloneRepo first.");
        }
        return dir;
    }

    static String validateRepo(String repo) {
        if (repo == null || !REPO_NAME.matcher(repo.trim()).matches() || repo.trim().equals("_logs")) {
            throw new IllegalArgumentException("Invalid repository name '" + repo + "'");
        }
        return repo.trim();
    }

    static String validateFeatureBranch(String branch) {
        String name = branch == null ? "" : branch.trim();
        if (!FEATURE_BRANCH.matcher(name).matches() || name.contains("..") || name.endsWith(".lock") || name.length() > 120) {
            throw new IllegalArgumentException("Branch '" + name + "' is not allowed. Commits and pushes are only allowed on "
                    + "feature branches named feature/<name>, never on develop, master, main or release branches.");
        }
        return name;
    }

    private static void validateRef(String ref) {
        if (!Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,119}$").matcher(ref).matches() || ref.contains("..")) {
            throw new IllegalArgumentException("Invalid branch name '" + ref + "'");
        }
    }

    static boolean isProtectedBranch(String branch) {
        String v = branch == null ? "" : branch.toLowerCase(Locale.ROOT);
        return PROTECTED_BRANCHES.contains(v) || v.startsWith("release/") || v.startsWith("release-") || v.startsWith("hotfix/");
    }

    /** Resolves a repo-relative path and makes sure it stays inside the repository (no .., no symlink escapes, no .git). */
    static Path resolveInside(Path repoDir, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        String cleaned = relative.trim().replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Use a path relative to the repository root: " + relative);
        }
        Path resolved = repoDir.resolve(cleaned).normalize();
        if (!resolved.startsWith(repoDir)) {
            throw new IllegalArgumentException("Path escapes the repository: " + relative);
        }
        for (Path segment : repoDir.relativize(resolved)) {
            if (segment.toString().equals(".git")) {
                throw new IllegalArgumentException("Access to .git is not allowed");
            }
        }
        Path existing = resolved;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null && !existing.toRealPath().startsWith(repoDir.toRealPath())) {
            throw new IllegalArgumentException("Path escapes the repository: " + relative);
        }
        return resolved;
    }

    /** Validates goals and flags against the allow-list and returns the final argument list (batch mode included). */
    static List<String> buildMavenArguments(String goals, String args) {
        if (!notBlank(goals)) {
            throw new IllegalArgumentException("goals are required, e.g. 'clean install'");
        }
        List<String> result = new ArrayList<>(List.of("-B", "-ntp"));
        for (String goal : goals.trim().split("\\s+")) {
            if (!MAVEN_GOALS.contains(goal)) {
                throw new IllegalArgumentException("Maven goal '" + goal + "' is not allowed. Allowed: " + new TreeSet<>(MAVEN_GOALS));
            }
            result.add(goal);
        }
        if (notBlank(args)) {
            String[] tokens = args.trim().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i];
                if (MAVEN_FLAG.matcher(token).matches()) {
                    if (!result.contains(token)) {
                        result.add(token);
                    }
                } else if (MAVEN_VALUE_FLAGS.contains(token)) {
                    if (i + 1 >= tokens.length || !MAVEN_VALUE.matcher(tokens[i + 1]).matches()) {
                        throw new IllegalArgumentException("Flag " + token + " needs a value");
                    }
                    result.add(token);
                    result.add(tokens[++i]);
                } else {
                    Matcher m = MAVEN_PROPERTY.matcher(token);
                    if (!m.matches() || FORBIDDEN_PROPERTY.matcher(m.group(1)).find()) {
                        throw new IllegalArgumentException("Maven argument '" + token + "' is not allowed");
                    }
                    result.add(token);
                }
            }
        }
        return result;
    }

    /** Returns a short description of the first secret-looking added line, or null. */
    static String findSecret(String unifiedDiff) {
        for (String line : unifiedDiff.split("\\r?\\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                Matcher m = SECRET.matcher(line);
                if (m.find()) {
                    String hit = m.group(1);
                    return hit.substring(0, Math.min(6, hit.length())) + "...";
                }
            }
        }
        return null;
    }

    private String mavenExecutable() {
        if (notBlank(mavenCommand)) {
            return mavenCommand.trim();
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
    }

    private String mavenOpts() {
        String existing = System.getenv("MAVEN_OPTS");
        return existing == null ? "-Xmx1536m" : existing;
    }

    private ProcessRunner.Result git(Path cwd, Duration timeout, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(gitCommand);
        command.add("-c");
        command.add("core.longpaths=true");
        command.addAll(Arrays.asList(args));
        Map<String, String> env = new HashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GCM_INTERACTIVE", "never");
        if (notBlank(githubToken)) {
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + githubToken).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http." + githubWeb + "/.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        return ProcessRunner.run(command, cwd, env, timeout, null, null, this::scrub);
    }

    private String requireOk(ProcessRunner.Result result, String what) {
        if (!result.success()) {
            throw new IllegalStateException(what + " failed" + (result.timedOut() ? " (timed out)" : " (exit " + result.exitCode() + ")")
                    + ": " + abbreviate(result.tailText(15, 1500), 1500));
        }
        return String.join("\n", result.tail());
    }

    private String currentBranch(Path dir) throws IOException, InterruptedException {
        return requireOk(git(dir, Duration.ofMinutes(1), "rev-parse", "--abbrev-ref", "HEAD"), "git rev-parse").trim();
    }

    private JsonNode githubGet(String path) {
        return readTree(rest.exchange(githubApi + path, HttpMethod.GET, new HttpEntity<>(githubHeaders()), byte[].class).getBody());
    }

    private JsonNode githubPost(String path, Object body) {
        HttpHeaders headers = githubHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return readTree(rest.exchange(githubApi + path, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class).getBody());
    }

    private HttpHeaders githubHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        if (notBlank(githubToken)) {
            headers.set("Authorization", "token " + githubToken);
        }
        return headers;
    }

    private JsonNode readTree(byte[] body) {
        try {
            return mapper.readTree(body);
        } catch (IOException ex) {
            throw new IllegalStateException("Unexpected response from GitHub: " + ex.getMessage(), ex);
        }
    }

    /** Masks the tokens in any text that goes back to the model or the logs. */
    String scrub(String text) {
        if (text == null) {
            return "";
        }
        String out = text;
        for (String secret : new String[]{githubToken, jfrogToken}) {
            if (notBlank(secret) && secret.length() > 8) {
                out = out.replace(secret, "***");
            }
        }
        return out.replaceAll("(?i)(Authorization: Basic )[A-Za-z0-9+/=]+", "$1***");
    }

    private static List<String> listDir(Path repoDir, Path dir, int max) throws IOException {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> !SKIP_DIRS.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p)).thenComparing(p -> p.getFileName().toString()))
                    .limit(max)
                    .forEach(p -> entries.add(slash(repoDir.relativize(p)) + (Files.isDirectory(p) ? "/" : "")));
        }
        return entries;
    }

    private static boolean hasSkippedSegment(Path repoDir, Path path) {
        for (Path segment : repoDir.relativize(path)) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void requireTextFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found: " + file.getFileName());
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File is larger than 1 MB");
        }
        try (Stream<String> probe = Files.lines(file, StandardCharsets.UTF_8)) {
            probe.limit(1).count();
        } catch (java.io.UncheckedIOException ex) {
            throw new IllegalArgumentException("File is not a UTF-8 text file");
        }
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static List<String> matchAll(String text, String regex, int max) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find() && out.size() < max) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String firstMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String lastMatching(List<String> lines, Pattern pattern) {
        String found = null;
        for (String line : lines) {
            if (pattern.matcher(line).find()) {
                found = line;
            }
        }
        return found;
    }

    private static List<String> reactorSummary(List<String> tail) {
        List<String> out = new ArrayList<>();
        boolean inside = false;
        for (String line : tail) {
            if (line.contains("Reactor Summary")) {
                inside = true;
                continue;
            }
            if (inside) {
                if (line.contains("BUILD ") || line.startsWith("[INFO] ----")) {
                    if (line.contains("BUILD ")) {
                        break;
                    }
                    continue;
                }
                if (line.contains("SUCCESS") || line.contains("FAILURE") || line.contains("SKIPPED")) {
                    out.add(line.replace("[INFO] ", "").trim());
                }
            }
        }
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n...[truncated]";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
