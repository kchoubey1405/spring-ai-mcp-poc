package com.schneider.ai.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionAuthServiceTest {

    private SessionAuthService authService;

    @BeforeEach
    void setUp() {
        authService = new SessionAuthService();
        ReflectionTestUtils.setField(authService, "configuredLlmProviders", "copilot,antigravity");
        ReflectionTestUtils.setField(authService, "defaultLlmProvider", "copilot");
        ReflectionTestUtils.setField(authService, "jiraUrl", "https://jira.se.com");
        ReflectionTestUtils.setField(authService, "githubEnterpriseUrl", "https://github.schneider-electric.com");
        ReflectionTestUtils.setField(authService, "githubEnterpriseApiUrl", "https://github.schneider-electric.com/api/v3");
        ReflectionTestUtils.setField(authService, "githubOrg", "ifw-microservices");
        ReflectionTestUtils.setField(authService, "jfrogUrl", "https://global-artifacts.se.com");
        ReflectionTestUtils.setField(authService, "serviceNowUrl", "https://schneider.service-now.com");
        ReflectionTestUtils.setField(authService, "confluenceUrl", "https://confluence.se.com");
        ReflectionTestUtils.setField(authService, "kafkaConnectUrl", "https://uat.emh.ei.se.com/api/kafka-connect-1");
        ReflectionTestUtils.setField(authService, "kafkaConnectAuth", "Basic U2VFbWhUZWFtOldlbGNvbWUx");

        // Leave fallbacks empty to verify dynamic session token requirement
        ReflectionTestUtils.setField(authService, "fallbackCopilotToken", "");
        ReflectionTestUtils.setField(authService, "fallbackGithubEnterpriseToken", "");
        ReflectionTestUtils.setField(authService, "fallbackJiraToken", "");
        ReflectionTestUtils.setField(authService, "fallbackConfluenceToken", "");
        ReflectionTestUtils.setField(authService, "fallbackJfrogToken", "");
        ReflectionTestUtils.setField(authService, "fallbackServiceNowToken", "");
    }

    @Test
    void testDynamicSessionTokenLifecycle() {
        String sessionId = "test-session-123";

        // Initial state: not authenticated
        assertFalse(authService.isAuthenticated(sessionId, "jira"));
        assertEquals(Optional.empty(), authService.getToken(sessionId, "jira"));

        // Save dynamic token
        authService.saveSessionToken(sessionId, "jira", "pat_jira_secret_token", "user1");
        assertTrue(authService.isAuthenticated(sessionId, "jira"));
        assertEquals(Optional.of("pat_jira_secret_token"), authService.getToken(sessionId, "jira"));

        // Other sessions must not see this token
        assertFalse(authService.isAuthenticated("other-session", "jira"));

        // Purge session tokens on logout/close
        authService.clearSession(sessionId);
        assertFalse(authService.isAuthenticated(sessionId, "jira"));
        assertEquals(Optional.empty(), authService.getToken(sessionId, "jira"));
    }

    @Test
    void testFallbackToEnvWhenSessionTokenMissing() {
        String sessionId = "test-session-fallback";
        ReflectionTestUtils.setField(authService, "fallbackJiraToken", "env_jira_fallback_token");

        // Uses fallback
        assertEquals(Optional.of("env_jira_fallback_token"), authService.getToken(sessionId, "jira"));

        // Dynamic session token overrides fallback
        authService.saveSessionToken(sessionId, "jira", "session_jira_override", "user2");
        assertEquals(Optional.of("session_jira_override"), authService.getToken(sessionId, "jira"));
    }

    @Test
    void testLlmProvidersStatus() {
        String sessionId = "test-session-llm";
        authService.saveSessionToken(sessionId, "copilot", "ghp_copilot_token", "user1");

        List<Map<String, Object>> providers = authService.getLlmProvidersStatus(sessionId);
        assertNotNull(providers);
        assertEquals(2, providers.size());

        Map<String, Object> copilot = providers.stream()
                .filter(p -> "copilot".equals(p.get("id")))
                .findFirst().orElseThrow();
        assertEquals(true, copilot.get("authenticated"));
        assertEquals("SESSION", copilot.get("authSource"));
        assertEquals(true, copilot.get("isDefault"));

        Map<String, Object> antigravity = providers.stream()
                .filter(p -> "antigravity".equals(p.get("id")))
                .findFirst().orElseThrow();
        assertEquals(false, antigravity.get("authenticated"));
    }

    @Test
    void testDynamicKafkaCurlGeneration() {
        String curl = authService.buildDynamicKafkaRestartCurl("OracleAqSinkConnector", 2);
        assertNotNull(curl);
        assertTrue(curl.contains("https://uat.emh.ei.se.com/api/kafka-connect-1/connectors/OracleAqSinkConnector/tasks/2/restart"));
        assertTrue(curl.contains("Authorization: Basic U2VFbWhUZWFtOldlbGNvbWUx"));
    }

    @Test
    void testMcpServersStatusAndUnauthenticatedList() {
        String sessionId = "test-session-mcp";
        authService.saveSessionToken(sessionId, "jira", "token_jira", "user1");
        authService.saveSessionToken(sessionId, "jfrog", "token_jfrog", "user1");

        List<Map<String, Object>> servers = authService.getMcpServersStatus(sessionId);
        assertNotNull(servers);
        assertEquals(6, servers.size());

        List<String> unauthenticated = authService.getUnauthenticatedMcpServers(sessionId);
        assertNotNull(unauthenticated);
        assertFalse(unauthenticated.isEmpty(), "Should list unauthenticated servers (Git, ServiceNow, Outlook, Confluence)");
        assertFalse(unauthenticated.contains("Jira Issue Tracking"), "Jira is authenticated");
        assertFalse(unauthenticated.contains("JFrog Artifactory & Xray"), "JFrog is authenticated");
    }
}
