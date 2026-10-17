package com.schneider.ai.service;

import org.springframework.ai.tool.ToolCallback;
import java.util.List;

/**
 * Common abstraction for LLM providers (GitHub Copilot, Google Antigravity, etc.).
 */
public interface LlmProviderService {

    /**
     * Unique identifier for this provider: e.g. "copilot", "antigravity".
     */
    String getProviderName();

    /**
     * Human-friendly display label: e.g. "GitHub Copilot", "Antigravity (Gemini)".
     */
    default String getDisplayName() {
        return getProviderName();
    }

    /**
     * Whether the provider is enabled and ready to handle requests.
     */
    boolean isAvailable();

    /**
     * Configured model name for this provider.
     */
    String getModel();

    /**
     * Send prompt with available tools for agent mode.
     */
    String sendAndWait(String appSessionId, String prompt, List<ToolCallback> tools) throws Exception;

    /**
     * Send prompt without tools for chat mode.
     */
    String sendAndWait(String appSessionId, String prompt) throws Exception;

    /**
     * Optional streaming execution: sends prompt with tools and invokes chunkConsumer for text chunks or lines.
     * Default implementation delegates to sendAndWait and splits output line-by-line for smooth streaming.
     */
    default String sendAndStream(String appSessionId, String prompt, List<ToolCallback> tools, java.util.function.Consumer<String> chunkConsumer) throws Exception {
        String result = sendAndWait(appSessionId, prompt, tools);
        if (chunkConsumer != null && result != null && !result.isEmpty()) {
            String[] lines = result.split("(?<=\\n)");
            for (String line : lines) {
                chunkConsumer.accept(line);
            }
        }
        return result;
    }

    /**
     * Clean up session resources on expiry.
     */
    default void deleteSession(String appSessionId) {}
}
