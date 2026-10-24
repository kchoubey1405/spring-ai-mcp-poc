package com.schneider.ai.auth;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing user session authentication, dynamic access token cache,
 * and external service connection states for LLM providers and MCP servers.
 *
 * All access tokens are held in-memory scoped to the active sessionId.
 * When the session is closed/reset, all associated tokens are purged.
 */
@Slf4j
@Service
public class SessionAuthService {

    public record TokenRecord(String service, String token, String username, Instant createdAt) {}

    // Map<SessionId, Map<ServiceId, TokenRecord>>
    private final Map<String, Map<String, TokenRecord>> sessionTokenStore = new ConcurrentHashMap<>();

    // Configurable LLM Providers (supports .env LLM_PROVIDERS or application.properties llm.providers)
    @Value("${llm.providers:${LLM_PROVIDERS:copilot,antigravity}}")
    private String configuredLlmProviders;

    @Value("${ai.provider:copilot}")
    private String defaultLlmProvider;

    // Configurable Hostnames & URLs (populated from .env or defaults)
    @Value("${dashboard.jira.base-url:${JIRA_URL:https://jira.se.com}}")
    private String jiraUrl;

    @Value("${dashboard.github.web-url:${GITHUB_ENTERPRISE_HOST:https://github.schneider-electric.com}}")
    private String githubEnterpriseUrl;

    @Value("${dashboard.github.base-url:${GITHUB_ENTERPRISE_API_URL:https://github.schneider-electric.com/api/v3}}")
    private String githubEnterpriseApiUrl;

    @Value("${dashboard.github.org:${GITHUB_ORG:ifw-microservices}}")
    private String githubOrg;

    @Value("${jfrog.base-url:${JFROG_URL:https://global-artifacts.se.com}}")
    private String jfrogUrl;

    @Value("${servicenow.base-url:${SERVICENOW_URL:https://schneider.service-now.com}}")
    private String serviceNowUrl;

    @Value("${confluence.base-url:${CONFLUENCE_URL:https://confluence.se.com}}")
    private String confluenceUrl;

    @Value("${dashboard.kafka.base-url:${KAFKA_CONNECT_URL:https://uat.emh.ei.se.com/api/kafka-connect-1}}")
    private String kafkaConnectUrl;

    @Value("${dashboard.kafka.auth:${KAFKA_CONNECT_AUTH:Basic U2VFbWhUZWFtOldlbGNvbWUx}}")
    private String kafkaConnectAuth;

    // Optional Fallback Tokens from .env (for fallback when popup is closed)
    @Value("${COPILOT_GITHUB_TOKEN:${GITHUB_TOKEN:}}")
    private String fallbackCopilotToken;

    @Value("${GITHUB_ENTERPRISE_TOKEN:}")
    private String fallbackGithubEnterpriseToken;

    @Value("${JIRA_API_TOKEN:}")
    private String fallbackJiraToken;

    @Value("${CONFLUENCE_API_TOKEN:}")
    private String fallbackConfluenceToken;

    @Value("${JFROG_ACCESS_TOKEN:${JFROG_API_TOKEN:}}")
    private String fallbackJfrogToken;

    @Value("${SERVICENOW_TOKEN:}")
    private String fallbackServiceNowToken;

    @Value("${ANTIGRAVITY_TOKEN:}")
    private String fallbackAntigravityToken;

    @Value("${OUTLOOK_USER_EMAIL:}")
    private String fallbackOutlookEmail;

    private final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private volatile String latestActiveSessionId;

    public void setCurrentSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            this.currentSessionId.set(sessionId.trim());
            this.latestActiveSessionId = sessionId.trim();
        }
    }

    public String getCurrentSessionId() {
        String sid = currentSessionId.get();
        return (sid != null && !sid.isBlank()) ? sid : latestActiveSessionId;
    }

    public void clearCurrentSessionId() {
        currentSessionId.remove();
    }

    public Optional<String> getUsername(String sessionId, String service) {
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId : getCurrentSessionId();
        if (sid != null) {
            Map<String, TokenRecord> sessionMap = sessionTokenStore.get(sid);
            if (sessionMap != null) {
                TokenRecord record = sessionMap.get(service.trim().toLowerCase(Locale.ROOT));
                if (record != null && record.username() != null && !record.username().isBlank()) {
                    return Optional.of(record.username());
                }
            }
        }
        return Optional.empty();
    }

    public String getUserEmail(String sessionId) {
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId : getCurrentSessionId();
        if (sid != null) {
            Map<String, TokenRecord> sessionMap = sessionTokenStore.get(sid);
            if (sessionMap != null) {
                TokenRecord outlook = sessionMap.get("outlook");
                if (outlook != null) {
                    if (outlook.username() != null && outlook.username().contains("@")) {
                        return outlook.username().trim();
                    }
                    if (outlook.token() != null && !outlook.token().isBlank()) {
                        Optional<String> jwtEmail = extractEmailFromJwt(outlook.token());
                        if (jwtEmail.isPresent() && jwtEmail.get().contains("@")) {
                            return jwtEmail.get();
                        }
                    }
                }
                for (TokenRecord rec : sessionMap.values()) {
                    if (rec.username() != null && rec.username().contains("@")) {
                        return rec.username().trim();
                    }
                    if (rec.token() != null && !rec.token().isBlank()) {
                        Optional<String> jwtEmail = extractEmailFromJwt(rec.token());
                        if (jwtEmail.isPresent() && jwtEmail.get().contains("@")) {
                            return jwtEmail.get();
                        }
                    }
                }
            }
        }
        return fallbackOutlookEmail != null && !fallbackOutlookEmail.isBlank() ? fallbackOutlookEmail.trim() : "";
    }

    /**
     * Extracts email address from JWT token claims (upn, email, preferred_username, unique_name, mail)
     * commonly present in Microsoft Azure AD / Entra ID access tokens.
     */
    public static Optional<String> extractEmailFromJwt(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String clean = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
        String[] parts = clean.split("\\.");
        if (parts.length >= 2) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(decoded);
                List<String> claimKeys = List.of("upn", "email", "preferred_username", "unique_name", "mail");
                for (String key : claimKeys) {
                    if (json.has(key)) {
                        String val = json.path(key).asText("");
                        if (val.contains("@")) {
                            return Optional.of(val.trim());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    @PostConstruct
    public void initFallbackTokensFromEnvFile() {
        try {
            Path envPath = Paths.get(".env");
            if (Files.exists(envPath)) {
                List<String> lines = Files.readAllLines(envPath);
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.startsWith("#")) {
                        line = line.substring(1).trim();
                    }
                    if (line.contains("=")) {
                        int eq = line.indexOf('=');
                        String k = line.substring(0, eq).trim();
                        String v = line.substring(eq + 1).trim();
                        if (!v.isBlank()) {
                            if ("JIRA_API_TOKEN".equalsIgnoreCase(k) && (fallbackJiraToken == null || fallbackJiraToken.isBlank())) {
                                fallbackJiraToken = v;
                                log.info("Loaded JIRA_API_TOKEN fallback from .env: length={}", v.length());
                            } else if ("CONFLUENCE_API_TOKEN".equalsIgnoreCase(k) && (fallbackConfluenceToken == null || fallbackConfluenceToken.isBlank())) {
                                fallbackConfluenceToken = v;
                            } else if ("GITHUB_ENTERPRISE_TOKEN".equalsIgnoreCase(k) && (fallbackGithubEnterpriseToken == null || fallbackGithubEnterpriseToken.isBlank())) {
                                fallbackGithubEnterpriseToken = v;
                            } else if ("SERVICENOW_TOKEN".equalsIgnoreCase(k) && (fallbackServiceNowToken == null || fallbackServiceNowToken.isBlank())) {
                                fallbackServiceNowToken = v;
                            } else if ("COPILOT_GITHUB_TOKEN".equalsIgnoreCase(k) && (fallbackCopilotToken == null || fallbackCopilotToken.isBlank())) {
                                fallbackCopilotToken = v;
                            } else if ("ANTIGRAVITY_TOKEN".equalsIgnoreCase(k) && (fallbackAntigravityToken == null || fallbackAntigravityToken.isBlank())) {
                                fallbackAntigravityToken = v;
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("Fallback token initialization from .env completed: {}", ex.getMessage());
        }
    }

    /**
     * Store a dynamic access token for a given session and service.
     */
    public void saveSessionToken(String sessionId, String service, String token, String username) {
        if (sessionId == null || sessionId.isBlank() || service == null || service.isBlank()) {
            return;
        }
        String svcKey = service.trim().toLowerCase(Locale.ROOT);
        String cleanToken = token != null ? token.trim() : "";
        String resolvedUsername = username;
        if ("outlook".equals(svcKey) && (resolvedUsername == null || !resolvedUsername.contains("@"))) {
            resolvedUsername = extractEmailFromJwt(cleanToken).orElse(resolvedUsername);
        }
        sessionTokenStore.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(svcKey, new TokenRecord(svcKey, cleanToken, resolvedUsername, Instant.now()));
        log.info("Saved dynamic session token for service [{}] in session [{}] (username: {})",
                svcKey, sessionId, username != null ? username : "anonymous");
    }

    /**
     * Retrieve access token: checks dynamic session store first, falls back to .env if configured.
     */
    public Optional<String> getToken(String sessionId, String service) {
        String svcKey = service != null ? service.trim().toLowerCase(Locale.ROOT) : "";
        if (sessionId != null) {
            Map<String, TokenRecord> sessionMap = sessionTokenStore.get(sessionId);
            if (sessionMap != null) {
                TokenRecord record = sessionMap.get(svcKey);
                if (record != null && !record.token().isBlank()) {
                    return Optional.of(record.token());
                }
            }
        }
        // Fallback to .env configuration if present
        String fallback = getFallbackTokenForService(svcKey);
        if (fallback != null && !fallback.isBlank()) {
            return Optional.of(fallback);
        }
        return Optional.empty();
    }

    /**
     * Check if a specific service is authenticated for the session.
     */
    public boolean isAuthenticated(String sessionId, String service) {
        return getToken(sessionId, service).isPresent();
    }

    /**
     * Purge all tokens for a session when the session is closed/reset.
     */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            sessionTokenStore.remove(sessionId);
            log.info("Purged dynamic session tokens for session [{}]", sessionId);
        }
    }

    /**
     * Return list of configured LLM providers with active session auth status.
     * Dynamically supports third-party LLM providers configured in .env or application.properties.
     */
    public List<Map<String, Object>> getLlmProvidersStatus(String sessionId) {
        String[] providers = (configuredLlmProviders != null ? configuredLlmProviders : "copilot,antigravity").split(",");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (String p : providers) {
            String id = p.trim().toLowerCase(Locale.ROOT);
            if (id.isBlank() || !seenIds.add(id)) continue;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("isDefault", id.equalsIgnoreCase(defaultLlmProvider));

            if ("copilot".equals(id) || "github".equals(id)) {
                item.put("name", "GitHub Copilot");
                item.put("loginType", "GITHUB_OAUTH");
                item.put("loginUrl", "https://github.com/login");
                item.put("description", "GitHub Copilot Enterprise LLM");
                item.put("authenticated", isAuthenticated(sessionId, "copilot") || isAuthenticated(sessionId, "github"));
                item.put("authSource", getAuthSource(sessionId, "copilot"));
            } else if ("antigravity".equals(id) || "gemini".equals(id)) {
                item.put("name", "Google Antigravity (Gemini)");
                item.put("loginType", "GOOGLE_OAUTH");
                item.put("loginUrl", "https://accounts.google.com");
                item.put("description", "Google DeepMind Antigravity / Gemini 2.5 Pro");
                item.put("authenticated", isAuthenticated(sessionId, "antigravity"));
                item.put("authSource", getAuthSource(sessionId, "antigravity"));
            } else if ("openai".equals(id) || "chatgpt".equals(id)) {
                item.put("name", "OpenAI ChatGPT");
                item.put("loginType", "OPENAI_OAUTH");
                item.put("loginUrl", "https://platform.openai.com/login");
                item.put("description", "OpenAI GPT-4o / Reasoning Models");
                item.put("authenticated", isAuthenticated(sessionId, id));
                item.put("authSource", getAuthSource(sessionId, id));
            } else if ("claude".equals(id) || "anthropic".equals(id)) {
                item.put("name", "Anthropic Claude");
                item.put("loginType", "ANTHROPIC_OAUTH");
                item.put("loginUrl", "https://console.anthropic.com/login");
                item.put("description", "Anthropic Claude 3.5 Sonnet");
                item.put("authenticated", isAuthenticated(sessionId, id));
                item.put("authSource", getAuthSource(sessionId, id));
            } else if ("azure".equals(id) || "azure-openai".equals(id)) {
                item.put("name", "Azure OpenAI");
                item.put("loginType", "AZURE_OAUTH");
                item.put("loginUrl", "https://portal.azure.com");
                item.put("description", "Microsoft Azure OpenAI Service");
                item.put("authenticated", isAuthenticated(sessionId, id));
                item.put("authSource", getAuthSource(sessionId, id));
            } else {
                item.put("name", capitalize(id) + " LLM");
                item.put("loginType", "API_KEY");
                item.put("loginUrl", "https://" + id + ".com/login");
                item.put("description", capitalize(id) + " Enterprise Language Model");
                item.put("authenticated", isAuthenticated(sessionId, id));
                item.put("authSource", getAuthSource(sessionId, id));
            }
            result.add(item);
        }
        return result;
    }

    /**
     * Return list of all MCP servers configured in .env with active session auth status.
     */
    public List<Map<String, Object>> getMcpServersStatus(String sessionId) {
        List<Map<String, Object>> servers = new ArrayList<>();

        servers.add(buildServerStatus("jira", "Jira Issue Tracking", jiraUrl,
                jiraUrl + "/login.jsp", sessionId));
        servers.add(buildServerStatus("github_enterprise", "Organization GitHub (" + githubOrg + ")",
                githubEnterpriseUrl, githubEnterpriseUrl + "/login", sessionId));
        servers.add(buildServerStatus("jfrog", "JFrog Artifactory & Xray", jfrogUrl,
                jfrogUrl + "/ui/login/", sessionId));
        servers.add(buildServerStatus("servicenow", "ServiceNow IT Service Management", serviceNowUrl,
                serviceNowUrl + "/navpage.do", sessionId));
        servers.add(buildServerStatus("outlook", "Outlook Mail Intelligence", "https://outlook.office.com",
                "https://outlook.office.com", sessionId));
        servers.add(buildServerStatus("confluence", "Confluence Knowledge Base", confluenceUrl,
                confluenceUrl + "/login.action", sessionId));

        return servers;
    }

    /**
     * Return Kafka Connect info with dynamically constructed curl commands.
     */
    public Map<String, Object> getKafkaConnectInfo() {
        String base = kafkaConnectUrl.replaceAll("/+$", "");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("url", base);
        info.put("authConfigured", kafkaConnectAuth != null && !kafkaConnectAuth.isBlank());
        info.put("restartTaskCurlTemplate", String.format(
                "curl --location --request POST '%s/connectors/{connector}/tasks/{taskId}/restart' \\\n--header 'Authorization: %s'",
                base, kafkaConnectAuth));
        info.put("restartConnectorCurlTemplate", String.format(
                "curl --location --request POST '%s/connectors/{connector}/restart' \\\n--header 'Authorization: %s'",
                base, kafkaConnectAuth));
        return info;
    }

    /**
     * Generate dynamic curl command for a specific connector and task ID.
     */
    public String buildDynamicKafkaRestartCurl(String connector, int taskId) {
        String base = kafkaConnectUrl.replaceAll("/+$", "");
        return String.format("curl --location --request POST '%s/connectors/%s/tasks/%d/restart' \\\n--header 'Authorization: %s'",
                base, connector, taskId, kafkaConnectAuth);
    }

    /**
     * List any MCP applications that failed or lack authentication for this session.
     */
    public List<String> getUnauthenticatedMcpServers(String sessionId) {
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> servers = getMcpServersStatus(sessionId);
        for (Map<String, Object> server : servers) {
            if (!Boolean.TRUE.equals(server.get("authenticated"))) {
                missing.add(String.valueOf(server.get("name")));
            }
        }
        return missing;
    }

    private Map<String, Object> buildServerStatus(String id, String name, String hostUrl, String loginUrl, String sessionId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("hostUrl", hostUrl);
        map.put("loginUrl", loginUrl);
        boolean authenticated = isAuthenticated(sessionId, id);
        map.put("authenticated", authenticated);
        map.put("authSource", getAuthSource(sessionId, id));
        return map;
    }

    private String getAuthSource(String sessionId, String service) {
        if (sessionId != null) {
            Map<String, TokenRecord> map = sessionTokenStore.get(sessionId);
            if (map != null && map.containsKey(service) && !map.get(service).token().isBlank()) {
                return "SESSION";
            }
        }
        String fallback = getFallbackTokenForService(service);
        if (fallback != null && !fallback.isBlank()) {
            return "ENV_FALLBACK";
        }
        return "NONE";
    }

    public String getFallbackTokenForService(String service) {
        if (service == null) return null;
        return switch (service.toLowerCase(Locale.ROOT)) {
            case "copilot", "github" -> (fallbackCopilotToken != null && !fallbackCopilotToken.isBlank())
                    ? fallbackCopilotToken : fallbackGithubEnterpriseToken;
            case "antigravity", "gemini" -> (fallbackAntigravityToken != null && !fallbackAntigravityToken.isBlank())
                    ? fallbackAntigravityToken : null;
            case "github_enterprise", "git" -> fallbackGithubEnterpriseToken;
            case "jira" -> fallbackJiraToken;
            case "confluence" -> fallbackConfluenceToken;
            case "jfrog" -> fallbackJfrogToken;
            case "servicenow" -> fallbackServiceNowToken;
            default -> null;
        };
    }

    public String getJiraUrl() { return jiraUrl != null ? jiraUrl : "https://jira.se.com"; }
    public String getGithubEnterpriseUrl() { return githubEnterpriseUrl != null ? githubEnterpriseUrl : "https://github.schneider-electric.com"; }
    public String getGithubEnterpriseApiUrl() { return githubEnterpriseApiUrl != null ? githubEnterpriseApiUrl : "https://github.schneider-electric.com/api/v3"; }
    public String getGithubOrg() { return githubOrg != null ? githubOrg : "ifw-microservices"; }
    public String getJfrogUrl() { return jfrogUrl != null ? jfrogUrl : "https://global-artifacts.se.com"; }
    public String getServiceNowUrl() { return serviceNowUrl != null ? serviceNowUrl : "https://schneider.service-now.com"; }
    public String getConfluenceUrl() { return confluenceUrl != null ? confluenceUrl : "https://confluence.se.com"; }
    public String getKafkaConnectUrl() { return kafkaConnectUrl != null ? kafkaConnectUrl : "https://uat.emh.ei.se.com/api/kafka-connect-1"; }
    public String getKafkaConnectAuth() { return kafkaConnectAuth != null ? kafkaConnectAuth : ""; }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
