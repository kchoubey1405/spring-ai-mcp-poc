package com.schneider.ai.jfrog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schneider.ai.jfrog.VulnModels.Target;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Maps Docker images in JFrog to the organisation's Git projects (repositories) by reading the repository trees on
 * GitHub Enterprise: a directory that contains a pom.xml or Dockerfile and is named like an image is that image's
 * module. Images without a matching directory are assigned by naming rules or the configured overrides.
 */
@Slf4j
@Service
public class ProjectCatalogService {

    public static final String OTHER_PROJECT = "other-images";

    public record RepoInfo(String name, String defaultBranch, String url) {
    }

    public record Catalog(Map<String, String> imageToProject, Map<String, List<String>> projectToImages,
                          Map<String, RepoInfo> repos, Instant builtAt) {

        public List<String> images() {
            return new ArrayList<>(imageToProject.keySet());
        }
    }

    private final JfrogClient jfrog;
    private final JfrogProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${dashboard.github.base-url:https://github.schneider-electric.com/api/v3}")
    private String githubApi;

    @Value("${dashboard.github.web-url:https://github.schneider-electric.com}")
    private String githubWeb;

    @Value("${dashboard.github.org:ifw-microservices}")
    private String githubOrg;

    @Value("${dashboard.github.token:}")
    private String githubToken;

    private volatile Catalog cached;

    public ProjectCatalogService(JfrogClient jfrog, JfrogProperties props) {
        this.jfrog = jfrog;
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(20_000);
        factory.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /** Cached catalog; rebuilt when older than the configured cache TTL or when {@code force} is set. */
    public synchronized Catalog catalog(boolean force) {
        Catalog current = cached;
        boolean fresh = current != null
                && Duration.between(current.builtAt(), Instant.now()).toMinutes() < props.getCacheTtlMinutes() * 2L;
        if (!force && fresh) {
            return current;
        }
        try {
            cached = build();
        } catch (RuntimeException ex) {
            if (current != null) {
                log.warn("Project catalog refresh failed, keeping the previous one: {}", ex.getMessage());
                return current;
            }
            throw ex;
        }
        return cached;
    }

    public Catalog catalog() {
        return catalog(false);
    }

    // ------------------------------------------------------------------ build

    private Catalog build() {
        List<String> images = jfrog.listImages();
        Map<String, RepoInfo> repos = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<String[]>> candidates = new HashMap<>();   // image -> [repo, dirDepth]

        List<JsonNode> repoNodes = listRepos();
        for (JsonNode repo : repoNodes) {
            if (repo.path("archived").asBoolean(false)) {
                continue;
            }
            String name = repo.path("name").asText();
            repos.put(name, new RepoInfo(name, repo.path("default_branch").asText("develop"),
                    githubWeb + "/" + githubOrg + "/" + name));
        }

        Set<String> imageSet = new HashSet<>(images);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, props.getParallelism()));
        try {
            Map<String, Future<List<String[]>>> futures = new LinkedHashMap<>();
            for (RepoInfo repo : repos.values()) {
                futures.put(repo.name(), pool.submit(() -> scanRepo(repo, imageSet)));
            }
            for (Map.Entry<String, Future<List<String[]>>> entry : futures.entrySet()) {
                try {
                    for (String[] hit : entry.getValue().get()) {
                        candidates.computeIfAbsent(hit[0], k -> new ArrayList<>())
                                .add(new String[]{entry.getKey(), hit[1]});
                    }
                } catch (Exception ex) {
                    log.warn("Unable to scan repository {}: {}", entry.getKey(), ex.getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, String> imageToProject = new TreeMap<>();
        for (String image : images) {
            String project = props.getImageProjectOverrides().get(image);
            if (project == null) {
                project = bestCandidate(candidates.get(image));
            }
            if (project == null) {
                project = inferByName(image, repos.keySet());
            }
            imageToProject.put(image, project != null ? project : OTHER_PROJECT);
        }

        Map<String, List<String>> projectToImages = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        imageToProject.forEach((image, project) ->
                projectToImages.computeIfAbsent(project, k -> new ArrayList<>()).add(image));
        projectToImages.values().forEach(Collections::sort);
        log.info("Project catalog built: {} images, {} projects ({} unmapped)", images.size(), projectToImages.size(),
                projectToImages.getOrDefault(OTHER_PROJECT, List.of()).size());
        return new Catalog(imageToProject, projectToImages, repos, Instant.now());
    }

    /** Prefers root-level module directories, then the shortest repository name (so date-integration beats a clone). */
    static String bestCandidate(List<String[]> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        return hits.stream()
                .min(Comparator.<String[]>comparingInt(h -> Integer.parseInt(h[1]))
                        .thenComparingInt(h -> h[0].length())
                        .thenComparing(h -> h[0]))
                .map(h -> h[0])
                .orElse(null);
    }

    /** Naming rule for images without a matching directory: titan-x-processor -> titan-integration, etc. */
    static String inferByName(String image, Collection<String> repoNames) {
        String lower = image.toLowerCase(Locale.ROOT);
        String best = null;
        int bestLength = 0;
        for (String repo : repoNames) {
            String stem = repo.toLowerCase(Locale.ROOT).replaceAll("[-_.]?integrations?$", "");
            if (stem.length() < 3 || repo.toLowerCase(Locale.ROOT).equals(stem)) {
                continue;   // only repos that follow the <name>-integration convention
            }
            Pattern p = Pattern.compile("(^|-)" + Pattern.quote(stem) + "(-|$)");
            if (p.matcher(lower).find() && stem.length() > bestLength) {
                best = repo;
                bestLength = stem.length();
            }
        }
        return best;
    }

    private List<String[]> scanRepo(RepoInfo repo, Set<String> images) {
        List<String[]> hits = new ArrayList<>();
        JsonNode tree = getJson(githubApi + "/repos/" + githubOrg + "/" + repo.name() + "/git/trees/"
                + repo.defaultBranch() + "?recursive=1");
        Set<String> dirs = new LinkedHashSet<>();
        for (JsonNode node : tree.path("tree")) {
            if (!"blob".equals(node.path("type").asText())) {
                continue;
            }
            String path = node.path("path").asText();
            if (path.equals("pom.xml") || path.equals("Dockerfile")) {
                dirs.add("");
            } else if (path.endsWith("/pom.xml") || path.endsWith("/Dockerfile")) {
                dirs.add(path.substring(0, path.lastIndexOf('/')));
            }
        }
        for (String dir : dirs) {
            String base = dir.isEmpty() ? repo.name() : dir.substring(dir.lastIndexOf('/') + 1);
            if (images.contains(base)) {
                int depth = dir.isEmpty() ? 0 : dir.split("/").length;
                hits.add(new String[]{base, String.valueOf(depth)});
            }
        }
        return hits;
    }

    private List<JsonNode> listRepos() {
        List<JsonNode> repos = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode batch = getJson(githubApi + "/orgs/" + githubOrg + "/repos?per_page=100&type=all&page=" + page);
            if (!batch.isArray() || batch.isEmpty()) {
                break;
            }
            batch.forEach(repos::add);
            if (batch.size() < 100) {
                break;
            }
        }
        return repos;
    }

    private JsonNode getJson(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        if (githubToken != null && !githubToken.isBlank()) {
            headers.set("Authorization", "token " + githubToken);
        }
        byte[] body = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class).getBody();
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Unexpected response from GitHub: " + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------ lookups

    public RepoInfo repo(String project) {
        return catalog().repos().get(project);
    }

    public String projectUrl(String project) {
        RepoInfo info = repo(project);
        return info != null ? info.url() : githubWeb + "/" + githubOrg + "/" + project;
    }

    /**
     * Finds the project and/or module a free-text request refers to. A module name wins over a project name, longer
     * names win over shorter ones.
     */
    public Target findTarget(String text) {
        if (text == null || text.isBlank()) {
            return new Target(null, null);
        }
        Catalog catalog;
        try {
            catalog = catalog();
        } catch (RuntimeException ex) {
            return new Target(null, null);
        }
        String value = text.toLowerCase(Locale.ROOT);
        String module = longestMatch(value, catalog.imageToProject().keySet());
        String project = longestMatch(value, catalog.projectToImages().keySet());
        if (module != null && (project == null || module.length() >= project.length())) {
            String owner = catalog.imageToProject().get(module);
            // "plm-integration" is both a project and a module: treat it as the project unless asked for the module.
            if (module.equals(project) || module.equals(owner)) {
                return new Target(owner, catalog.projectToImages().getOrDefault(owner, List.of()).size() > 1 ? null : module);
            }
            return new Target(owner, module);
        }
        if (project != null) {
            return new Target(project, null);
        }
        return new Target(null, null);
    }

    private static String longestMatch(String lowerText, Collection<String> names) {
        String best = null;
        for (String name : names) {
            if (OTHER_PROJECT.equals(name)) {
                continue;
            }
            String lowerName = name.toLowerCase(Locale.ROOT);
            Pattern p = Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(lowerName) + "(?![a-z0-9_-])");
            if (p.matcher(lowerText).find() && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        return best;
    }
}
