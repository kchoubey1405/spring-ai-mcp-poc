package com.schneider.ai.service;

import com.schneider.ai.auth.SessionAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringAiChatClientServiceTest {

    private SessionAuthService sessionAuthService;
    private SpringAiChatClientService chatClientService;
    private SpringAiCopilotProvider copilotProvider;
    private SpringAiAntigravityProvider antigravityProvider;

    @BeforeEach
    void setUp() {
        sessionAuthService = mock(SessionAuthService.class);
        chatClientService = new SpringAiChatClientService(null, null, sessionAuthService);

        copilotProvider = new SpringAiCopilotProvider(
                chatClientService,
                "https://models.inference.ai.azure.com",
                "test-copilot-key",
                "gpt-5",
                true
        );

        antigravityProvider = new SpringAiAntigravityProvider(
                chatClientService,
                null,
                "https://generativelanguage.googleapis.com/v1beta/openai/",
                "test-gemini-key",
                "gemini-2.5-pro",
                true
        );
    }

    @Test
    void providersExposeCorrectFrameworkMetadata() {
        assertEquals("copilot", copilotProvider.getProviderName());
        assertEquals("GitHub Copilot", copilotProvider.getDisplayName());
        assertEquals("gpt-5", copilotProvider.getModel());
        assertTrue(copilotProvider.isAvailable());

        assertEquals("antigravity", antigravityProvider.getProviderName());
        assertEquals("Antigravity (Gemini)", antigravityProvider.getDisplayName());
        assertEquals("gemini-2.5-pro", antigravityProvider.getModel());
        assertTrue(antigravityProvider.isAvailable());
    }

    @Test
    void dynamicClientConstructedViaSpringAiOpenAiModel() {
        ChatClient client = chatClientService.getOrCreateClient(
                "copilot",
                "https://models.inference.ai.azure.com",
                "test-copilot-key",
                "gpt-5",
                "session-101"
        );
        assertNotNull(client, "Dynamic Spring AI ChatClient must be constructed successfully");
    }

    @Test
    void dynamicSessionTokenOverridesDefaultApiKey() {
        when(sessionAuthService.getToken("session-dynamic", "copilot"))
                .thenReturn(Optional.of("session-dynamic-token-abc"));

        ChatClient client = chatClientService.getOrCreateClient(
                "copilot",
                "https://models.inference.ai.azure.com",
                "default-key",
                "gpt-5",
                "session-dynamic"
        );
        assertNotNull(client);
        verify(sessionAuthService).getToken("session-dynamic", "copilot");
    }
}
