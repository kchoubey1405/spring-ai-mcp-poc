package com.schneider.ai.tools;

import com.schneider.ai.jfrog.JfrogClient;
import com.schneider.ai.jfrog.VulnModels.Counts;
import com.schneider.ai.jfrog.VulnModels.Issue;
import com.schneider.ai.jfrog.Versions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.schneider.ai.tools.JfrogTools.*;

/**
 * Finds the best (newest, vulnerability-free) version of a Maven dependency in the organisation's Maven repository
 * (JFrog virtual repository) by checking candidate versions against Xray.
 */
@Component
public class MavenTools {

    private static final int SAME_MAJOR_LOWEST = 3;
    private static final int SAME_MAJOR_NEWEST = 4;
    private static final int OTHER_MAJOR_NEWEST = 2;

    private final JfrogClient jfrog;

    public MavenTools(JfrogClient jfrog) {
        this.jfrog = jfrog;
    }

    @Tool(name = "maven_findSafeVersion", description = """
            Find the version to upgrade a Maven dependency to, from the organisation's Maven repository (JFrog), checked
            against JFrog Xray. Give groupId, artifactId, the currentVersion and optionally the fixedVersions reported by
            Xray (comma separated). It evaluates the lowest fixing versions and the newest versions (same major line and
            newer majors) and returns 'recommended' = the newest stable version of the SAME major line with the fewest
            vulnerabilities (0 = vulnerability free), plus 'latestStable'. Prefer 'recommended' to avoid breaking changes;
            mention a major upgrade only as an option.""")
    public Map<String, Object> findSafeVersion(
            @ToolParam(description = "Maven groupId, e.g. com.fasterxml.jackson.core") String groupId,
            @ToolParam(description = "Maven artifactId, e.g. jackson-databind") String artifactId,
            @ToolParam(description = "Version currently used, e.g. 2.21.4") String currentVersion,
            @ToolParam(required = false, description = "Comma separated fixed versions from Xray, e.g. 2.18.9,2.21.5,2.22.1") String fixedVersions) {
        try {
            if (!notBlank(groupId) || !notBlank(artifactId) || !notBlank(currentVersion)) {
                return fail("groupId, artifactId and currentVersion are required", null);
            }
            String group = groupId.trim();
            String artifact = artifactId.trim();
            String current = currentVersion.trim();

            List<String> stable = jfrog.mavenVersions(group, artifact).stream()
                    .filter(Versions::isStableMaven).sorted(Versions.ORDER).toList();
            if (stable.isEmpty()) {
                return fail("No stable versions of " + group + ":" + artifact + " found in the Maven repository", null);
            }
            String latestStable = stable.get(stable.size() - 1);
            String minFixed = lowestFixed(fixedVersions, current);

            List<String> newer = stable.stream()
                    .filter(v -> Versions.compare(v, current) > 0)
                    .filter(v -> minFixed == null || Versions.compare(v, minFixed) >= 0)
                    .toList();
            if (newer.isEmpty()) {
                return ok("dependency", group + ":" + artifact, "currentVersion", current, "latestStable", latestStable,
                        "recommended", null, "note", "No newer stable version " + (minFixed == null ? "" : "at or above " + minFixed)
                                + " is available in the Maven repository. The fix may not be published yet.");
            }

            List<String> sameMajor = newer.stream().filter(v -> Versions.sameMajor(v, current)).toList();
            List<String> otherMajor = newer.stream().filter(v -> !Versions.sameMajor(v, current)).toList();

            Set<String> evaluate = new LinkedHashSet<>();
            sameMajor.stream().limit(SAME_MAJOR_LOWEST).forEach(evaluate::add);
            sameMajor.stream().skip(Math.max(0, sameMajor.size() - SAME_MAJOR_NEWEST)).forEach(evaluate::add);
            otherMajor.stream().skip(Math.max(0, otherMajor.size() - OTHER_MAJOR_NEWEST)).forEach(evaluate::add);

            List<String> gavs = new ArrayList<>();
            gavs.add(group + ":" + artifact + ":" + current);
            evaluate.forEach(v -> gavs.add(group + ":" + artifact + ":" + v));
            Map<String, List<Issue>> issues = jfrog.componentIssues(gavs);

            Map<String, Object> currentRow = row(current, issues.get(gavs.get(0)));
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Counts> countsByVersion = new LinkedHashMap<>();
            for (String v : evaluate) {
                List<Issue> found = issues.get(group + ":" + artifact + ":" + v);
                rows.add(row(v, found));
                if (found != null) {
                    countsByVersion.put(v, JfrogClient.countsOf(found));
                }
            }

            String recommended = pick(sameMajor, countsByVersion);
            String alternative = pick(otherMajor, countsByVersion);
            boolean free = recommended != null && countsByVersion.get(recommended).total() == 0;
            Map<String, Object> result = ok("dependency", group + ":" + artifact, "currentVersion", current,
                    "current", currentRow, "minimumFixedVersion", minFixed, "latestStable", latestStable,
                    "recommended", recommended, "recommendedIsVulnerabilityFree", free,
                    "candidates", rows);
            if (recommended == null && alternative != null) {
                result.put("recommended", alternative);
                result.put("majorUpgrade", true);
                result.put("note", "No same-major version found; " + alternative + " is a MAJOR upgrade - check for breaking changes and compile/test.");
            } else if (alternative != null) {
                result.put("alternativeMajorUpgrade", alternative);
            }
            if (recommended != null && !free) {
                result.put("note", "No candidate is completely vulnerability free; " + recommended
                        + " has the fewest vulnerabilities (Xray data). Check the remaining ones with xray_getVulnerabilityDetails.");
            }
            return result;
        } catch (Exception ex) {
            return fail(ex.getMessage(), "Check the coordinates (groupId/artifactId) and that the dependency exists in the Maven repository.");
        }
    }

    /** Newest version with the fewest Critical/High, then fewest total; versions Xray has no data for are skipped. */
    static String pick(List<String> versions, Map<String, Counts> countsByVersion) {
        String best = null;
        Counts bestCounts = null;
        for (String v : versions) {
            Counts c = countsByVersion.get(v);
            if (c == null) {
                continue;
            }
            if (best == null || better(v, c, best, bestCounts)) {
                best = v;
                bestCounts = c;
            }
        }
        return best;
    }

    private static boolean better(String v, Counts c, String best, Counts bestCounts) {
        int severe = c.critical() * 1000 + c.high() * 100;
        int bestSevere = bestCounts.critical() * 1000 + bestCounts.high() * 100;
        if (severe != bestSevere) {
            return severe < bestSevere;
        }
        if (c.total() != bestCounts.total()) {
            return c.total() < bestCounts.total();
        }
        return Versions.compare(v, best) > 0;   // same quality: newer wins
    }

    /** Lowest fixed version on the same major line as the current version, otherwise the lowest overall. */
    static String lowestFixed(String fixedVersions, String current) {
        if (!notBlank(fixedVersions)) {
            return null;
        }
        List<String> fixed = new ArrayList<>();
        for (String part : fixedVersions.split("[,;\\s]+")) {
            String v = part.replaceAll("[\\[\\]()\"']", "").trim();
            if (!v.isEmpty() && Character.isDigit(v.charAt(0))) {
                fixed.add(v);
            }
        }
        if (fixed.isEmpty()) {
            return null;
        }
        fixed.sort(Versions.ORDER);
        return fixed.stream().filter(v -> Versions.sameMajor(v, current) && Versions.compare(v, current) > 0)
                .findFirst().orElse(fixed.get(0));
    }

    private static Map<String, Object> row(String version, List<Issue> issues) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("version", version);
        if (issues == null) {
            row.put("xray", "no data (component not indexed)");
        } else {
            row.put("counts", countsMap(JfrogClient.countsOf(issues)));
        }
        return row;
    }
}
