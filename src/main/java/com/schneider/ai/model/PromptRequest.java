package com.schneider.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptRequest {

    /**
     * Provider selection:
     * - "antigravity": uses Google Antigravity CLI (Gemini)
     * - "copilot": uses GitHub Copilot SDK
     * - null or "default": uses configured default provider (ai.provider in application.properties)
     */
    private String provider;

    /**
     * The user prompt/question to send to the AI model.
     */
    private String prompt;

    /**
     * Optional: session ID for maintaining conversation context.
     * If null or empty, a new session will be created.
     * Sessions auto-expire after 5 minutes of inactivity.
     */
    private String sessionId;

    /**
     * Mode of operation:
     * - "chat": Normal informative chat (fast, no tools)
     * - "agent": Agent mode with MCP tools enabled for complex tasks
     * Defaults to "chat" if not specified.
     */
    private String mode;

    /**
     * Optional: specify which MCP tools to enable for this request.
     * Only used in "agent" mode.
     * Valid values: "git", "jfrog", "jira", "confluence", "servicenow", "all"
     * Defaults to "all" if not specified.
     */
    private String[] enabledTools;
}
