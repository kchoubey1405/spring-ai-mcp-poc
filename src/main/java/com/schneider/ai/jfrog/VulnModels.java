package com.schneider.ai.jfrog;

import java.time.Instant;
import java.util.List;

/** Immutable data carriers shared by the JFrog/Xray services, REST controller and agent tools. */
public final class VulnModels {

    private VulnModels() {
    }

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NOT_SCANNED = "NOT_SCANNED";
    public static final String STATUS_NO_RELEASE = "NO_RELEASE";
    public static final String STATUS_TAG_NOT_FOUND = "TAG_NOT_FOUND";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ERROR = "ERROR";

    /** Vulnerability counts by severity. {@code fixable} = issues for which a fixed version exists. */
    public record Counts(int critical, int high, int medium, int low, int unknown, int total, int fixable) {

        public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0, 0);

        public Counts plus(Counts other) {
            return new Counts(critical + other.critical, high + other.high, medium + other.medium, low + other.low,
                    unknown + other.unknown, total + other.total, fixable + other.fixable);
        }
    }

    /** One Xray finding on an image (compact form, descriptions are dropped to keep memory small). */
    public record Issue(String issueId, String cve, String severity, String summary, String component,
                        String version, String pkgType, List<String> fixedVersions, String impactPath) {
    }

    /** Scan result of one image tag. */
    public record ImageScan(String image, String tag, String status, String message, Counts counts,
                            List<Issue> issues, Instant fetchedAt) {

        public static ImageScan failed(String image, String tag, String status, String message) {
            return new ImageScan(image, tag, status, message, Counts.EMPTY, List.of(), Instant.now());
        }

        public boolean ok() {
            return STATUS_OK.equals(status);
        }
    }

    /** Release tags of one image (releases newest first; otherTags = SNAPSHOT, dev, base-image tags...). */
    public record TagInfo(String image, String latestRelease, List<String> releases, List<String> otherTags,
                          Instant fetchedAt) {
    }

    public record ModuleSummary(String module, String version, String status, String message, Counts counts) {
    }

    public record ProjectSummary(String project, String url, String release, Counts totals, int moduleCount,
                                 int scannedModules, List<ModuleSummary> modules) {
    }

    public record Summary(String state, String message, int done, int total, Instant generatedAt, Counts totals,
                          int projectCount, int moduleCount, int scannedModules, List<ProjectSummary> projects) {
    }

    /** Resolved subject of a chat request: a whole project and/or one module. */
    public record Target(String project, String module) {
        public boolean isEmpty() {
            return project == null && module == null;
        }

        public String label() {
            if (module != null && project != null && !module.equals(project)) {
                return module + " (" + project + ")";
            }
            return module != null ? module : String.valueOf(project);
        }
    }
}
