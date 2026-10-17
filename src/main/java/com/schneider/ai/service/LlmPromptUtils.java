package com.schneider.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;

/**
 * Shared utility methods for building LLM prompts with tool schemas and parameter descriptions.
 */
public final class LlmPromptUtils {

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    private LlmPromptUtils() {}

    /**
     * Appends available enterprise tools to the prompt so any LLM can understand and invoke them.
     */
    public static String buildPromptWithToolContext(String prompt, List<ToolCallback> tools) {
        if (tools == null || tools.isEmpty()) {
            return prompt;
        }
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

    /** Compact signature, e.g. (repo*, path, startLine); '*' marks required parameters. */
    public static String describeParameters(ToolCallback tool) {
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

    /** Parameter descriptions are listed for in-process tools only (MCP tools are documented in the system prompt). */
    public static String describeParameterDetails(ToolCallback tool) {
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

    public static JsonNode schemaRoot(ToolCallback tool) {
        try {
            String schema = tool.getToolDefinition().inputSchema();
            return schema == null || schema.isBlank() ? SCHEMA_MAPPER.createObjectNode() : SCHEMA_MAPPER.readTree(schema);
        } catch (Exception ex) {
            return SCHEMA_MAPPER.createObjectNode();
        }
    }

    public static JsonNode schemaProperties(ToolCallback tool) {
        JsonNode properties = schemaRoot(tool).path("properties");
        return properties.isObject() ? properties : null;
    }
}
