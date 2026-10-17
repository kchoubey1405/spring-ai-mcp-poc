package com.schneider.ai.service;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.MessageOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CopilotSdkService implements LlmProviderService {

    @Override
    public String getProviderName() {
        return "copilot";
    }

    @Override
    public String getDisplayName() {
        return "GitHub Copilot";
    }

    private final boolean enabled;
    private final String model;
    private final String githubToken;

    private CopilotClient client;
    private volatile boolean started;

    public CopilotSdkService(
            @Value("${copilot.sdk.enabled:false}") boolean enabled,
            @Value("${copilot.sdk.model:gpt-5}") String model,
            @Value("${COPILOT_GITHUB_TOKEN:${GITHUB_TOKEN:}}") String githubToken) {
        this.enabled = enabled;
        this.model = model;
        this.githubToken = githubToken;
    }

    @PostConstruct
    public void initialize() {
        if (!enabled) {
            log.info("Copilot SDK provider is disabled (copilot.sdk.enabled=false)");
            return;
        }

        try {
            this.client = new CopilotClient();
            this.client.start().get(30, TimeUnit.SECONDS);
            this.started = true;
            log.info("Copilot SDK provider started with model [{}]", model);
        } catch (Exception ex) {
            this.started = false;
            log.error("Failed to start Copilot SDK provider: {}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("Error closing Copilot SDK client: {}", ex.getMessage());
            }
        }
    }

    public boolean isAvailable() {
        return enabled && started && client != null;
    }

    public String getModel() {
        return model;
    }

    /**
     * Send prompt with optional MCP tools (for agent mode).
     * Chat mode: no tools
     * Agent mode: tools available for Copilot to use
     */
    public String sendAndWait(String appSessionId, String prompt, List<ToolCallback> tools) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("Copilot SDK is not available. Enable it with copilot.sdk.enabled=true and ensure Copilot auth is available on this machine.");
        }

        String effectivePrompt = prompt;
        if (tools != null && !tools.isEmpty()) {
            effectivePrompt = buildPromptWithToolContext(prompt, tools);
        }

        try (var session = client.createSession(
            buildSessionConfig(appSessionId)
        ).get(30, TimeUnit.SECONDS)) {

            var response = session.sendAndWait(new MessageOptions().setPrompt(effectivePrompt))
                    .get(120, TimeUnit.SECONDS);

            return response.getData().content();
        }
    }

    /**
     * Send prompt without tools (for chat mode - faster).
     */
    public String sendAndWait(String appSessionId, String prompt) throws Exception {
        return sendAndWait(appSessionId, prompt, Collections.emptyList());
    }

    public void deleteSession(String appSessionId) {
        if (!isAvailable()) {
            return;
        }

        try {
            client.deleteSession(appSessionId).get(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.debug("Unable to delete Copilot session [{}]: {}", appSessionId, ex.getMessage());
        }
    }

    private SessionConfig buildSessionConfig(String appSessionId) {
        SessionConfig config = new SessionConfig()
                .setSessionId(appSessionId)
                .setOnPermissionRequest(PermissionHandler.APPROVE_ALL);

        if (model != null && !model.isBlank()) {
            config.setModel(model);
        }

        if (githubToken != null && !githubToken.isBlank()) {
            config.setGitHubToken(githubToken);
        }

        return config;
    }

    private String buildPromptWithToolContext(String prompt, List<ToolCallback> tools) {
        StringBuilder builder = new StringBuilder();
        builder.append(prompt);
        builder.append("\n\n[Available enterprise integrations]\n");
        for (ToolCallback tool : tools) {
            builder.append("- ")
                    .append(tool.getToolDefinition().name())
                    .append(describeParameters(tool))
                    .append(": ")
                    .append(tool.getToolDefinition().description())
                    .append("\n");
            builder.append(describeParameterDetails(tool));
        }
        builder.append("\nUse these integrations to fetch live data before answering. Do not claim integrations are unavailable.");
        return builder.toString();
    }

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    /** Compact signature, e.g. (repo*, path, startLine); '*' marks required parameters. */
    static String describeParameters(ToolCallback tool) {
        JsonNode properties = schemaProperties(tool);
        if (properties == null || properties.isEmpty()) {
            return "()";
        }
        Set<String> required = new HashSet<>();
        JsonNode requiredNode = schemaRoot(tool).path("required");
        if (requiredNode.isArray()) {
            requiredNode.forEach(n -> required.add(n.asText()));
        }
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(n -> names.add(n + (required.contains(n) ? "*" : "")));
        return "(" + String.join(", ", names) + ")";
    }

    /** Parameter descriptions are listed for the in-process tools only (MCP tools are documented in the system prompt). */
    static String describeParameterDetails(ToolCallback tool) {
        String name = tool.getToolDefinition().name();
        if (name.contains("mcp_client")) {
            return "";
        }
        JsonNode properties = schemaProperties(tool);
        if (properties == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        properties.fields().forEachRemaining(entry -> {
            String description = entry.getValue().path("description").asText("");
            if (!description.isBlank()) {
                out.append("    ").append(entry.getKey()).append(": ").append(description).append("\n");
            }
        });
        return out.toString();
    }

    private static JsonNode schemaRoot(ToolCallback tool) {
        try {
            String schema = tool.getToolDefinition().inputSchema();
            return schema == null || schema.isBlank() ? SCHEMA_MAPPER.createObjectNode() : SCHEMA_MAPPER.readTree(schema);
        } catch (Exception ex) {
            return SCHEMA_MAPPER.createObjectNode();
        }
    }

    private static JsonNode schemaProperties(ToolCallback tool) {
        JsonNode properties = schemaRoot(tool).path("properties");
        return properties.isObject() ? properties : null;
    }
}
