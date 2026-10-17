package com.schneider.ai.jfrog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void releaseTagsAcceptOnlyNumericVersions() {
        assertTrue(Versions.isReleaseTag("1.90.0"));
        assertTrue(Versions.isReleaseTag("v2.1"));
        assertFalse(Versions.isReleaseTag("1.90.0-SNAPSHOT"));
        assertFalse(Versions.isReleaseTag("dev"));
        assertFalse(Versions.isReleaseTag("vul-test"));
        assertFalse(Versions.isReleaseTag(null));
    }

    @Test
    void stableMavenRejectsPreReleases() {
        assertTrue(Versions.isStableMaven("2.17.1"));
        assertTrue(Versions.isStableMaven("5.3.30.RELEASE"));
        assertTrue(Versions.isStableMaven("33.0.0-jre"));
        assertFalse(Versions.isStableMaven("3.0.0-alpha1"));
        assertFalse(Versions.isStableMaven("3.0.0-RC2"));
        assertFalse(Versions.isStableMaven("1.2.3-SNAPSHOT"));
        assertFalse(Versions.isStableMaven("2.0.0.M1"));
    }

    @Test
    void comparesNumerically() {
        assertTrue(Versions.compare("1.10.0", "1.9.9") > 0);
        assertTrue(Versions.compare("1.2", "1.2.0") == 0);
        assertTrue(Versions.compare("1.0.0", "1.0.0-rc1") > 0);
        assertTrue(Versions.compare("v1.2.0", "1.1.9") > 0);
    }

    @Test
    void latestReleaseIgnoresNonReleaseTags() {
        List<String> tags = List.of("dev", "1.8.4", "1.8.5", "1.10.0", "1.9.9", "1.11.0-SNAPSHOT", "latest");
        assertEquals("1.10.0", Versions.latestRelease(tags));
        assertEquals(List.of("1.10.0", "1.9.9", "1.8.5", "1.8.4"), Versions.releasesNewestFirst(tags));
        assertNull(Versions.latestRelease(List.of("dev", "latest")));
    }

    @Test
    void sameMajorComparesFirstSegment() {
        assertTrue(Versions.sameMajor("2.3.4", "2.9.0"));
        assertFalse(Versions.sameMajor("2.3.4", "3.0.0"));
    }
}
