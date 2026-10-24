package com.schneider.ai.service;

import com.schneider.ai.auth.SessionAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Enterprise Spring AI ChatClient Service.
 * Unifies all LLM interactions through Spring AI's official ChatClient and ChatModel.
 * Allows switching providers (GitHub Copilot, Google Antigravity / Gemini, Azure OpenAI, etc.)
 * purely via configuration (base-url, api-key, model) without any Java code modifications.
 */
@Slf4j
@Service
public class SpringAiChatClientService {

    private final ChatClient defaultChatClient;
    private final ChatModel defaultChatModel;
    private final SessionAuthService sessionAuthService;

    // Cache of dynamically constructed ChatClients per (provider:token)
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    @Autowired
    public SpringAiChatClientService(
            @Autowired(required = false) ChatClient defaultChatClient,
            @Autowired(required = false) ChatModel defaultChatModel,
            @Autowired(required = false) SessionAuthService sessionAuthService) {
        this.defaultChatClient = defaultChatClient;
        this.defaultChatModel = defaultChatModel;
        this.sessionAuthService = sessionAuthService;
    }

    /**
     * Resolves or dynamically constructs a Spring AI ChatClient for the given provider,
     * base URL, API key, model, and active session credentials.
     */
    public ChatClient getOrCreateClient(String provider, String baseUrl, String defaultApiKey, String model, String sessionId) {
        String effectiveApiKey = defaultApiKey;
        if (sessionAuthService != null && sessionId != null) {
            String sessionToken = sessionAuthService.getToken(sessionId, provider).orElse(null);
            if (sessionToken != null && !sessionToken.isBlank()) {
                effectiveApiKey = sessionToken;
            }
        }

        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            effectiveApiKey = "dummy-key-for-" + provider;
        }

        final String apiKeyToUse = effectiveApiKey;
        String cacheKey = provider + ":" + baseUrl + ":" + model + ":" + effectiveApiKey.hashCode();
        return clientCache.computeIfAbsent(cacheKey, k -> {
            try {
                log.info("Building Spring AI ChatClient for provider [{}] at [{}] with model [{}]",
                        provider, baseUrl, model);
                OpenAiApi api = OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKeyToUse)
                        .build();

                OpenAiChatModel chatModel = OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                        .build();

                return ChatClient.builder(chatModel).build();
            } catch (Exception ex) {
                log.warn("Failed to build dynamic ChatClient for provider [{}]: {}. Using default ChatClient.",
                        provider, ex.getMessage());
                return defaultChatClient != null ? defaultChatClient : (defaultChatModel != null ? ChatClient.builder(defaultChatModel).build() : null);
            }
        });
    }

    /**
     * Executes prompt synchronously using Spring AI ChatClient.
     */
    public String execute(ChatClient client, String prompt, List<ToolCallback> tools) throws Exception {
        if (client == null) {
            throw new IllegalStateException("Spring AI ChatClient is not configured.");
        }

        String effectivePrompt = (tools != null && !tools.isEmpty())
                ? LlmPromptUtils.buildPromptWithToolContext(prompt, tools)
                : prompt;

        try {
            var requestSpec = client.prompt().user(effectivePrompt);
            if (tools != null && !tools.isEmpty()) {
                try {
                    requestSpec.tools((org.springframework.ai.tool.ToolCallbackProvider) () -> tools.toArray(new ToolCallback[0]));
                } catch (Exception toolRegEx) {
                    log.debug("Using prompt-based tool context for ChatClient ({}): {}",
                            toolRegEx.getClass().getSimpleName(), toolRegEx.getMessage());
                }
            }
            String content = requestSpec.call().content();
            return content != null ? content : "";
        } catch (Exception ex) {
            // If execution failed, retry with pure prompt tool context (handles OpenAI/Gemini/Copilot tool schema differences)
            if (tools != null && !tools.isEmpty()) {
                try {
                    log.info("Spring AI ChatClient execution with native tool callbacks hit ({}). Retrying with prompt-based tool directive...", ex.getMessage());
                    String retryContent = client.prompt().user(effectivePrompt).call().content();
                    if (retryContent != null && !retryContent.isBlank()) {
                        return retryContent;
                    }
                } catch (Exception retryEx) {
                    log.warn("ChatClient retry without native tools failed: {}", retryEx.getMessage());
                }
            }
            log.error("Spring AI ChatClient call failed: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Executes prompt with streaming using Spring AI ChatClient.
     */
    public String executeStream(ChatClient client, String prompt, List<ToolCallback> tools, Consumer<String> chunkConsumer) throws Exception {
        if (client == null) {
            throw new IllegalStateException("Spring AI ChatClient is not configured.");
        }

        String effectivePrompt = (tools != null && !tools.isEmpty())
                ? LlmPromptUtils.buildPromptWithToolContext(prompt, tools)
                : prompt;

        try {
            var requestSpec = client.prompt().user(effectivePrompt);
            if (tools != null && !tools.isEmpty()) {
                try {
                    requestSpec.tools((org.springframework.ai.tool.ToolCallbackProvider) () -> tools.toArray(new ToolCallback[0]));
                } catch (Exception toolRegEx) {
                    log.debug("Using prompt-based tool context for ChatClient stream: {}", toolRegEx.getMessage());
                }
            }

            StringBuilder fullResponse = new StringBuilder();
            requestSpec.stream().content()
                    .doOnNext(chunk -> {
                        fullResponse.append(chunk);
                        if (chunkConsumer != null) {
                            chunkConsumer.accept(chunk);
                        }
                    })
                    .blockLast();

            return fullResponse.toString();
        } catch (Exception ex) {
            log.warn("Streaming with ChatClient failed ({}), falling back to synchronous execute: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            String fallback = execute(client, prompt, tools);
            if (chunkConsumer != null && fallback != null && !fallback.isEmpty()) {
                String[] lines = fallback.split("(?<=\\n)");
                for (String line : lines) {
                    chunkConsumer.accept(line);
                }
            }
            return fallback != null ? fallback : "";
        }
    }
}
