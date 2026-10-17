package com.schneider.ai.jfrog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Version parsing / ordering helpers for docker release tags and Maven versions. */
public final class Versions {

    private static final Pattern RELEASE_TAG = Pattern.compile("^v?\\d+(\\.\\d+){1,3}$");
    private static final Pattern PRE_RELEASE = Pattern.compile(
            "(?i)(^|[.\\-_])(alpha|beta|rc|cr|m\\d*|snapshot|ea|preview|dev|pre|milestone|b\\d+|a\\d+)([.\\-_\\d]|$)");
    private static final Pattern STABLE_MAVEN = Pattern.compile("^\\d+(\\.\\d+)*([.\\-](Final|RELEASE|GA|jre))?$",
            Pattern.CASE_INSENSITIVE);

    private Versions() {
    }

    /** Docker release tag: 1.90.0 or v1.90 (no -SNAPSHOT, no branch tags such as dev/vul). */
    public static boolean isReleaseTag(String tag) {
        return tag != null && RELEASE_TAG.matcher(tag).matches();
    }

    /** Stable Maven version: no alpha/beta/rc/milestone/snapshot qualifiers. */
    public static boolean isStableMaven(String version) {
        return version != null && !PRE_RELEASE.matcher(version).find() && STABLE_MAVEN.matcher(version).matches();
    }

    public static final Comparator<String> ORDER = Versions::compare;

    public static int compare(String a, String b) {
        List<String> pa = parts(a);
        List<String> pb = parts(b);
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            String x = i < pa.size() ? pa.get(i) : "0";
            String y = i < pb.size() ? pb.get(i) : "0";
            boolean nx = isNumber(x);
            boolean ny = isNumber(y);
            int c;
            if (nx && ny) {
                c = Long.compare(Long.parseLong(x), Long.parseLong(y));
            } else if (nx) {
                c = 1;   // 1.0.0 > 1.0.0-rc1
            } else if (ny) {
                c = -1;
            } else {
                c = x.compareToIgnoreCase(y);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Highest release tag, or null if there is none. */
    public static String latestRelease(List<String> tags) {
        return tags.stream().filter(Versions::isReleaseTag).max(ORDER).orElse(null);
    }

    /** Release tags, newest first. */
    public static List<String> releasesNewestFirst(List<String> tags) {
        return tags.stream().filter(Versions::isReleaseTag).sorted(ORDER.reversed()).toList();
    }

    public static boolean sameMajor(String a, String b) {
        List<String> pa = parts(a);
        List<String> pb = parts(b);
        return !pa.isEmpty() && !pb.isEmpty() && pa.get(0).equals(pb.get(0));
    }

    private static List<String> parts(String version) {
        List<String> out = new ArrayList<>();
        if (version == null) {
            return out;
        }
        String v = version.toLowerCase(Locale.ROOT);
        if (v.startsWith("v") && v.length() > 1 && Character.isDigit(v.charAt(1))) {
            v = v.substring(1);
        }
        for (String p : v.split("[.\\-_]")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty() || s.length() > 12) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
