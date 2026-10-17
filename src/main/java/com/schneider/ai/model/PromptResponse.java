package com.schneider.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptResponse {

    /**
     * The AI-generated response text.
     */
    private String response;

    /**
     * The session ID for this conversation.
     * Use this in subsequent requests to maintain context.
     */
    private String sessionId;

    /**
     * The provider that handled the request.
     */
    private String provider;

    /**
     * The model used for generation.
     */
    private String model;

    /**
     * List of MCP tools that were invoked during the response.
     */
    private List<String> toolsUsed;

    /**
     * Metadata about tool call results.
     */
    private List<Map<String, Object>> toolResults;

    /**
     * Timestamp of the response.
     */
    private Instant timestamp;
}
