package com.schneider.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Event payload emitted over Server-Sent Events (SSE) during a streaming chat prompt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatStreamEvent {

    /**
     * Event type:
     * - "thinking": thought process / status update (e.g. "Analyzing Jira query", "Prefetching ticket")
     * - "tool_call": an MCP tool is being invoked
     * - "tool_result": an MCP tool finished execution
     * - "chunk": incremental output text token / line
     * - "done": streaming complete, carries the final PromptResponse
     * - "error": error occurred during execution
     */
    private String type;

    /** Text content: thought message, text chunk, or tool result summary */
    private String content;

    /** Associated session ID */
    private String sessionId;

    /** LLM provider name */
    private String provider;

    /** LLM model name */
    private String model;

    /** Name of the tool (for tool_call / tool_result) */
    private String toolName;

    /** Tool invocation arguments or status */
    private Map<String, Object> toolPayload;

    /** Cumulative list of tools used */
    private List<String> toolsUsed;

    /** Full final PromptResponse (populated on 'done' event) */
    private PromptResponse response;

    /** Event creation timestamp */
    @Builder.Default
    private Instant timestamp = Instant.now();
}
