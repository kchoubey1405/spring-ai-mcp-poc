package com.schneider.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * GitHub Copilot LLM provider implemented via official Spring AI ChatClient.
 * Connects to GitHub Models / Copilot OpenAI-compatible endpoint.
 */
@Slf4j
@Primary
@Service("springAiCopilotProvider")
public class SpringAiCopilotProvider implements LlmProviderService {

    private final SpringAiChatClientService chatClientService;
    private final String baseUrl;
    private final String defaultApiKey;
    private final String model;
    private final boolean enabled;

    public SpringAiCopilotProvider(
            SpringAiChatClientService chatClientService,
            @Value("${spring.ai.openai.base-url:${COPILOT_BASE_URL:https://models.inference.ai.azure.com}}") String baseUrl,
            @Value("${spring.ai.openai.api-key:${COPILOT_GITHUB_TOKEN:${GITHUB_TOKEN:dummy-key-for-copilot}}}") String defaultApiKey,
            @Value("${spring.ai.openai.chat.options.model:${COPILOT_MODEL:gpt-5}}") String model,
            @Value("${copilot.enabled:true}") boolean enabled) {
        this.chatClientService = chatClientService;
        this.baseUrl = baseUrl;
        this.defaultApiKey = defaultApiKey;
        this.model = model;
        this.enabled = enabled;
        log.info("Initialized Spring AI Copilot Provider with model [{}] at [{}]", model, baseUrl);
    }

    @Override
    public String getProviderName() {
        return "copilot";
    }

    @Override
    public String getDisplayName() {
        return "GitHub Copilot";
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String sendAndWait(String appSessionId, String prompt, List<ToolCallback> tools) throws Exception {
        ChatClient client = chatClientService.getOrCreateClient("copilot", baseUrl, defaultApiKey, model, appSessionId);
        return chatClientService.execute(client, prompt, tools);
    }

    @Override
    public String sendAndWait(String appSessionId, String prompt) throws Exception {
        return sendAndWait(appSessionId, prompt, null);
    }

    @Override
    public String sendAndStream(String appSessionId, String prompt, List<ToolCallback> tools, Consumer<String> chunkConsumer) throws Exception {
        ChatClient client = chatClientService.getOrCreateClient("copilot", baseUrl, defaultApiKey, model, appSessionId);
        return chatClientService.executeStream(client, prompt, tools, chunkConsumer);
    }
}
