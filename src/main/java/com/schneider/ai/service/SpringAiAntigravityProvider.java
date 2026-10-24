package com.schneider.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * Google Antigravity (Gemini) LLM provider implemented via official Spring AI ChatClient.
 * Connects to Google Gemini / Antigravity OpenAI-compatible endpoint.
 * Requires only provider, base-url, and api-key in configuration without bespoke code.
 * Seamlessly falls back to local authenticated Antigravity CLI if remote ChatClient credentials fail.
 */
@Slf4j
@Service("springAiAntigravityProvider")
public class SpringAiAntigravityProvider implements LlmProviderService {

    private final SpringAiChatClientService chatClientService;
    private final AntigravityService cliService;
    private final String baseUrl;
    private final String defaultApiKey;
    private final String model;
    private final boolean enabled;

    public SpringAiAntigravityProvider(
            SpringAiChatClientService chatClientService,
            @Autowired(required = false) AntigravityService cliService,
            @Value("${antigravity.base-url:${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai/}}") String baseUrl,
            @Value("${antigravity.api-key:${GEMINI_API_KEY:dummy-key-for-antigravity}}") String defaultApiKey,
            @Value("${antigravity.model:${GEMINI_MODEL:gemini-2.5-pro}}") String model,
            @Value("${antigravity.enabled:true}") boolean enabled) {
        this.chatClientService = chatClientService;
        this.cliService = cliService;
        this.baseUrl = baseUrl;
        this.defaultApiKey = defaultApiKey;
        this.model = model;
        this.enabled = enabled;
        log.info("Initialized Spring AI Antigravity Provider with model [{}] at [{}] (CLI fallback: {})",
                model, baseUrl, cliService != null && cliService.isAvailable());
    }

    @Override
    public String getProviderName() {
        return "antigravity";
    }

    @Override
    public String getDisplayName() {
        return "Antigravity (Gemini)";
    }

    @Override
    public boolean isAvailable() {
        return enabled || (cliService != null && cliService.isAvailable());
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String sendAndWait(String appSessionId, String prompt, List<ToolCallback> tools) throws Exception {
        try {
            ChatClient client = chatClientService.getOrCreateClient("antigravity", baseUrl, defaultApiKey, model, appSessionId);
            return chatClientService.execute(client, prompt, tools);
        } catch (Exception ex) {
            if (cliService != null && cliService.isAvailable()) {
                log.info("ChatClient call for Antigravity failed ({}), falling back to local Antigravity CLI", ex.getMessage());
                return cliService.sendAndWait(appSessionId, prompt, tools);
            }
            throw ex;
        }
    }

    @Override
    public String sendAndWait(String appSessionId, String prompt) throws Exception {
        return sendAndWait(appSessionId, prompt, null);
    }

    @Override
    public String sendAndStream(String appSessionId, String prompt, List<ToolCallback> tools, Consumer<String> chunkConsumer) throws Exception {
        try {
            ChatClient client = chatClientService.getOrCreateClient("antigravity", baseUrl, defaultApiKey, model, appSessionId);
            return chatClientService.executeStream(client, prompt, tools, chunkConsumer);
        } catch (Exception ex) {
            if (cliService != null && cliService.isAvailable()) {
                log.info("ChatClient stream for Antigravity failed ({}), falling back to local Antigravity CLI", ex.getMessage());
                return cliService.sendAndStream(appSessionId, prompt, tools, chunkConsumer);
            }
            throw ex;
        }
    }
}
