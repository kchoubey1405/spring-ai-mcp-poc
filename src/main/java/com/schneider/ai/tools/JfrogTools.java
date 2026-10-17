package com.schneider.ai.tools;

import com.schneider.ai.jfrog.*;
import com.schneider.ai.jfrog.VulnModels.*;
import com.schneider.ai.jfrog.VulnerabilityService.ComponentFinding;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Spring AI tools that replace the (not enabled) JFrog MCP server: image releases, Xray vulnerability counts and
 * details for the integration modules. Read-only.
 */
@Component
public class JfrogTools {

    private final VulnerabilityService vulnerabilities;
    private final VulnerabilityReportService reports;
    private final ProjectCatalogService catalog;

    public JfrogTools(VulnerabilityService vulnerabilities, VulnerabilityReportService reports,
                      ProjectCatalogService catalog) {
        this.vulnerabilities = vulnerabilities;
        this.reports = reports;
        this.catalog = catalog;
    }

    @Tool(name = "jfrog_listProjects", description = """
            List the organisation's integration projects (Git repositories) with their modules (Docker images in JFrog)
            and the latest release of each module. Optional 'project' (for example mdm-integration) limits the list to
            one project. Use it to find which project a module belongs to or which modules a project has.""")
    public Map<String, Object> listProjects(
            @ToolParam(required = false, description = "Project (Git repository) name, e.g. mdm-integration") String project) {
        try {
            ProjectCatalogService.Catalog c = catalog.catalog();
            List<Map<String, Object>> projects = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : c.projectToImages().entrySet()) {
                if (notBlank(project) && !entry.getKey().equalsIgnoreCase(project.trim())) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("project", entry.getKey());
                row.put("url", catalog.projectUrl(entry.getKey()));
                ProjectCatalogService.RepoInfo info = c.repos().get(entry.getKey());
                if (info != null) {
                    row.put("defaultBranch", info.defaultBranch());
                }
                row.put("modules", entry.getValue());
                projects.add(row);
            }
            if (projects.isEmpty()) {
                return fail("Unknown project '" + project + "'.", "Known projects: " + String.join(", ", c.projectToImages().keySet()));
            }
            return ok("projectCount", projects.size(), "projects", projects);
        } catch (Exception ex) {
            return fail(ex.getMessage(), null);
        }
    }

    @Tool(name = "jfrog_listImageTags", description = """
            List the releases (x.y.z tags, newest first) and the other tags (dev, SNAPSHOT, base-image tags such as 20-slim)
            of a module's Docker image in JFrog. Use it to show the user which release versions exist before fixing
            vulnerabilities, and to look at base image tags (module 'openjdk').""")
    public Map<String, Object> listImageTags(
            @ToolParam(description = "Module / image name, e.g. bfo-processor") String module,
            @ToolParam(required = false, description = "Maximum releases to return (default 10)") Integer limit) {
        try {
            String image = resolveModule(module);
            TagInfo info = vulnerabilities.tags(image, true);
            int max = limit == null ? 10 : Math.max(1, Math.min(limit, 100));
            return ok("module", image, "project", vulnerabilities.projectOf(image), "latestRelease",
                    info.latestRelease() == null ? "none" : info.latestRelease(),
                    "releases", info.releases().stream().limit(max).toList(),
                    "releaseCount", info.releases().size(), "otherTags", info.otherTags());
        } catch (Exception ex) {
            return fail(ex.getMessage(), null);
        }
    }

    @Tool(name = "xray_getVulnerabilityCounts", description = """
            JFrog Xray vulnerability counts (Critical, High, Medium, Low, Total) of module Docker images.
            - No arguments: ALL projects, module by module, for the LATEST release of every module.
            - 'project': all modules of that project (Git repository, e.g. mdm-integration).
            - 'module': one module (e.g. bfo-processor).
            - 'release': a specific release version (e.g. 1.89.0) instead of the latest.
            The result contains a ready-made 'markdown' report with tables and CSV download links: show it to the user
            as it is instead of retyping numbers.""")
    public Map<String, Object> getVulnerabilityCounts(
            @ToolParam(required = false, description = "Project (Git repository) name, e.g. mdm-integration") String project,
            @ToolParam(required = false, description = "Module / image name, e.g. bfo-processor") String module,
            @ToolParam(required = false, description = "Release version, e.g. 1.90.0. Omit for the latest release") String release) {
        try {
            Target target = resolveTarget(project, module);
            String version = notBlank(release) ? release.trim() : null;
            List<ProjectSummary> summaries = vulnerabilities.projectSummaries(target, version);
            Counts total = summaries.stream().map(ProjectSummary::totals).reduce(Counts.EMPTY, Counts::plus);
            Map<String, Object> result = ok("scope", target.isEmpty() ? "all projects" : target.label(),
                    "release", version == null ? "latest" : version,
                    "totals", countsMap(total),
                    "projectCount", summaries.size(),
                    "markdown", reports.markdown(summaries, target, version));
            List<String> problems = summaries.stream().flatMap(p -> p.modules().stream())
                    .filter(m -> !VulnModels.STATUS_OK.equals(m.status()))
                    .map(m -> m.module() + ": " + m.status() + (m.message().isBlank() ? "" : " (" + m.message() + ")"))
                    .limit(30).toList();
            if (!problems.isEmpty()) {
                result.put("notScanned", problems);
            }
            return result;
        } catch (Exception ex) {
            return fail(ex.getMessage(), null);
        }
    }

    @Tool(name = "xray_getVulnerabilityDetails", description = """
            Detailed Xray findings of ONE module release, grouped by affected component: installed version, severities,
            CVEs and the versions that fix them. Use it to plan a vulnerability fix. packageType 'maven' = a Java
            dependency inside the application (fix by upgrading the dependency in the pom); 'deb', 'apk', 'rpm' or
            'generic' = operating-system / base image layer (fix by a newer base image). Omit 'release' for the latest.""")
    public Map<String, Object> getVulnerabilityDetails(
            @ToolParam(description = "Module / image name, e.g. bfo-processor") String module,
            @ToolParam(required = false, description = "Release version, e.g. 1.90.0. Omit for the latest release") String release,
            @ToolParam(required = false, description = "Minimum severity: Critical, High, Medium or Low (default Low)") String minSeverity,
            @ToolParam(required = false, description = "Maximum components to return (default 40)") Integer maxComponents) {
        try {
            String image = resolveModule(module);
            ImageScan scan = vulnerabilities.scanModule(image, notBlank(release) ? release.trim() : null);
            if (!scan.ok()) {
                return fail("No Xray scan for " + image + ":" + scan.tag() + " - " + scan.status() + " " + scan.message(), null);
            }
            List<ComponentFinding> findings = VulnerabilityService.groupByComponent(scan.issues(), minSeverity);
            int max = maxComponents == null ? 40 : Math.max(1, Math.min(maxComponents, 200));
            List<Map<String, Object>> rows = new ArrayList<>();
            int maven = 0;
            int os = 0;
            for (ComponentFinding f : findings) {
                boolean isMaven = "maven".equalsIgnoreCase(f.packageType());
                if (isMaven) {
                    maven++;
                } else {
                    os++;
                }
                if (rows.size() >= max) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("component", f.component());
                row.put("installedVersion", f.installedVersion());
                row.put("packageType", f.packageType());
                row.put("highestSeverity", f.highestSeverity());
                row.put("counts", countsMap(f.counts()));
                row.put("cves", f.cves().stream().limit(8).toList());
                row.put("fixedVersions", f.fixedVersions().stream().limit(10).toList());
                row.put("where", f.path());
                row.put("fixType", isMaven ? "upgrade-maven-dependency" : "newer-base-image");
                rows.add(row);
            }
            return ok("module", image, "project", vulnerabilities.projectOf(image), "release", scan.tag(),
                    "totals", countsMap(scan.counts()), "affectedComponents", findings.size(),
                    "mavenComponents", maven, "osOrBaseImageComponents", os,
                    "components", rows, "truncated", findings.size() > rows.size());
        } catch (Exception ex) {
            return fail(ex.getMessage(), null);
        }
    }

    @Tool(name = "xray_createReportLink", description = """
            Create the download link of the vulnerability report (CSV) for a project, a module or all projects. The CSV
            has module-wise Critical/High/Medium/Low counts; detail=true lists every CVE. The UI right panel offers the
            same download. Return the markdown link to the user.""")
    public Map<String, Object> createReportLink(
            @ToolParam(required = false, description = "Project (Git repository) name") String project,
            @ToolParam(required = false, description = "Module / image name") String module,
            @ToolParam(required = false, description = "Release version; omit for the latest release") String release,
            @ToolParam(required = false, description = "true = one row per CVE, false = one row per module (default)") Boolean detail) {
        try {
            Target target = resolveTarget(project, module);
            String version = notBlank(release) ? release.trim() : null;
            boolean full = Boolean.TRUE.equals(detail);
            return ok("markdownLink", VulnerabilityReportService.link(target, version, full,
                    (full ? "Detailed vulnerability report (CSV)" : "Vulnerability report (CSV)")));
        } catch (Exception ex) {
            return fail(ex.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Target resolveTarget(String project, String module) {
        ProjectCatalogService.Catalog c = catalog.catalog();
        String p = null;
        String m = null;
        if (notBlank(project)) {
            p = matchKey(project, c.projectToImages().keySet());
            if (p == null) {
                Target found = catalog.findTarget(project);
                p = found.project();
                m = found.module();
            }
            if (p == null && m == null) {
                throw new IllegalArgumentException("Unknown project '" + project + "'. Known projects: "
                        + String.join(", ", c.projectToImages().keySet()));
            }
        }
        if (notBlank(module)) {
            m = resolveModule(module);
        }
        return new Target(m != null ? c.imageToProject().get(m) : p, m);
    }

    private String resolveModule(String module) {
        if (!notBlank(module)) {
            throw new IllegalArgumentException("module is required");
        }
        ProjectCatalogService.Catalog c = catalog.catalog();
        String exact = matchKey(module, c.imageToProject().keySet());
        if (exact != null) {
            return exact;
        }
        Target found = catalog.findTarget(module);
        if (found.module() != null) {
            return found.module();
        }
        String needle = module.trim().toLowerCase(Locale.ROOT);
        List<String> similar = c.imageToProject().keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(needle)).limit(10).toList();
        throw new IllegalArgumentException("Unknown module '" + module + "'."
                + (similar.isEmpty() ? " Use jfrog_listProjects to see the modules." : " Did you mean: " + String.join(", ", similar)));
    }

    private static String matchKey(String value, Collection<String> keys) {
        String v = value.trim();
        for (String key : keys) {
            if (key.equalsIgnoreCase(v)) {
                return key;
            }
        }
        return null;
    }

    static Map<String, Object> countsMap(Counts c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("critical", c.critical());
        map.put("high", c.high());
        map.put("medium", c.medium());
        map.put("low", c.low());
        map.put("unknown", c.unknown());
        map.put("total", c.total());
        map.put("fixable", c.fixable());
        return map;
    }

    static Map<String, Object> ok(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", true);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    static Map<String, Object> fail(String error, String hint) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", false);
        map.put("error", error == null ? "unknown error" : error);
        if (hint != null) {
            map.put("hint", hint);
        }
        return map;
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
