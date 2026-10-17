package com.schneider.ai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceToolsGuardTest {

    @Test
    void repoNamesAreValidated() {
        assertEquals("mdm-integration", WorkspaceTools.validateRepo(" mdm-integration "));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateRepo("../etc"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateRepo("a/b"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateRepo(""));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateRepo(null));
    }

    @Test
    void onlyFeatureBranchesAreAllowed() {
        assertEquals("feature/vulnerability-fix-1.90.0", WorkspaceTools.validateFeatureBranch("feature/vulnerability-fix-1.90.0"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateFeatureBranch("develop"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateFeatureBranch("master"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateFeatureBranch("release/1.0"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateFeatureBranch("feature/../x"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.validateFeatureBranch("feature/x y"));
    }

    @Test
    void protectedBranchesAreRecognised() {
        assertTrue(WorkspaceTools.isProtectedBranch("develop"));
        assertTrue(WorkspaceTools.isProtectedBranch("MAIN"));
        assertTrue(WorkspaceTools.isProtectedBranch("release/2.0"));
        assertTrue(WorkspaceTools.isProtectedBranch("hotfix/abc"));
        assertFalse(WorkspaceTools.isProtectedBranch("feature/abc"));
    }

    @Test
    void pathsCannotEscapeTheRepository(@TempDir Path repo) throws Exception {
        Path root = repo.toRealPath();
        assertEquals(root.resolve("pom.xml"), WorkspaceTools.resolveInside(root, "pom.xml"));
        assertEquals(root.resolve("a").resolve("b.txt"), WorkspaceTools.resolveInside(root, "a/b.txt"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.resolveInside(root, "../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.resolveInside(root, "/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.resolveInside(root, "C:\\Windows\\win.ini"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.resolveInside(root, ".git/config"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.resolveInside(root, " "));
        Files.createDirectories(root.resolve("src"));
        assertNotNull(WorkspaceTools.resolveInside(root, "src/Main.java"));
    }

    @Test
    void mavenArgumentsUseAllowList() {
        List<String> args = WorkspaceTools.buildMavenArguments("clean install", "-DskipTests -pl module-a -U");
        assertEquals(List.of("-B", "-ntp", "clean", "install", "-DskipTests", "-pl", "module-a", "-U"), args);

        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.buildMavenArguments("deploy", null));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.buildMavenArguments("exec:exec", null));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.buildMavenArguments("install", "-s evil.xml"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.buildMavenArguments("install", "-Duser.home=/x"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.buildMavenArguments("install", "-pl"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceTools.buildMavenArguments("", null));
    }

    @Test
    void secretsInAddedLinesAreDetected() {
        String clean = "+++ b/pom.xml\n+<version>2.17.1</version>\n-<version>2.14.0</version>";
        assertNull(WorkspaceTools.findSecret(clean));

        String leaked = "+++ b/application.properties\n+token=ghp_abcdefghijklmnopqrstuvwxyz0123456789\n";
        String hit = WorkspaceTools.findSecret(leaked);
        assertNotNull(hit);
        assertTrue(hit.endsWith("..."));
        assertFalse(hit.contains("abcdefghijklmnopqrstuvwxyz"));

        // removed lines are not reported
        assertNull(WorkspaceTools.findSecret("-token=ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
    }
}
