package com.schneider.ai.service;

import com.schneider.ai.model.ChatStreamEvent;
import com.schneider.ai.model.PromptRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.codec.ServerSentEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmProviderServiceTest {

    private AntigravityService antigravityService;
    private MockLlmProvider copilotMock;
    private AiOrchestrationService orchestrationService;

    static class MockLlmProvider implements LlmProviderService {
        private final String name;
        private final boolean available;
        private final String model;

        MockLlmProvider(String name, boolean available, String model) {
            this.name = name;
            this.available = available;
            this.model = model;
        }

        @Override public String getProviderName() { return name; }
        @Override public String getDisplayName() { return name.toUpperCase(); }
        @Override public boolean isAvailable() { return available; }
        @Override public String getModel() { return model; }
        @Override public String sendAndWait(String s, String p, List<ToolCallback> t) { return "mock-response"; }
        @Override public String sendAndWait(String s, String p) { return "mock-response"; }
    }

    @BeforeEach
    void setUp() {
        antigravityService = new AntigravityService(true, "agy", "gemini-3.8-flash-high", 30);
        antigravityService.initialize();
        copilotMock = new MockLlmProvider("copilot", true, "gpt-5");

        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();

        orchestrationService = new AiOrchestrationService(
                Collections.emptyList(),
                context,
                null,
                List.of(copilotMock, antigravityService),
                "copilot",
                "gpt-5",
                "ifw-microservices",
                new ByteArrayResource("system-prompt".getBytes())
        );
    }

    @Test
    void resolvesExplicitAntigravityProvider() {
        LlmProviderService resolved = orchestrationService.resolveProvider("antigravity");
        assertNotNull(resolved);
        assertEquals("antigravity-cli", resolved.getProviderName());
        assertEquals("gemini-3.8-flash-high", resolved.getModel());
    }

    @Test
    void resolvesExplicitCopilotProvider() {
        LlmProviderService resolved = orchestrationService.resolveProvider("copilot");
        assertNotNull(resolved);
        assertEquals("copilot", resolved.getProviderName());
        assertEquals("gpt-5", resolved.getModel());
    }

    @Test
    void resolvesConfiguredDefaultWhenDefaultOrNullRequested() {
        LlmProviderService resolvedNull = orchestrationService.resolveProvider(null);
        assertNotNull(resolvedNull);
        assertEquals("copilot", resolvedNull.getProviderName());

        LlmProviderService resolvedDefault = orchestrationService.resolveProvider("default");
        assertNotNull(resolvedDefault);
        assertEquals("copilot", resolvedDefault.getProviderName());
    }

    @Test
    void fallbackToAvailableProviderWhenDefaultUnavailable() {
        MockLlmProvider unavailableCopilot = new MockLlmProvider("copilot", false, "gpt-5");
        MockLlmProvider availableAntigravity = new MockLlmProvider("antigravity", true, "gemini-3.8-flash-high");

        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();

        AiOrchestrationService service = new AiOrchestrationService(
                Collections.emptyList(),
                context,
                null,
                List.of(unavailableCopilot, availableAntigravity),
                "copilot", // configured default is unavailable copilot
                "gpt-5",
                "ifw-microservices",
                new ByteArrayResource("system-prompt".getBytes())
        );

        LlmProviderService resolved = service.resolveProvider(null);
        assertNotNull(resolved);
        assertEquals("antigravity", resolved.getProviderName(), "Should fall back to available antigravity provider");
    }

    @Test
    void returnsProviderInfoWithAllRegisteredProviders() {
        Map<String, Object> info = orchestrationService.getProviderInfo();
        assertEquals("copilot", info.get("configuredProvider"));
        assertEquals("copilot", info.get("activeProvider"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providers = (List<Map<String, Object>>) info.get("providers");
        assertEquals(2, providers.size());
    }

    @Test
    void sendAndStreamEmitsLinesThroughConsumer() throws Exception {
        MockLlmProvider provider = new MockLlmProvider("copilot", true, "gpt-5") {
            @Override
            public String sendAndWait(String s, String p, List<ToolCallback> t) {
                return "line 1\nline 2\nline 3";
            }
        };

        List<String> receivedChunks = new ArrayList<>();
        String result = provider.sendAndStream("session-1", "prompt", Collections.emptyList(), receivedChunks::add);

        assertEquals("line 1\nline 2\nline 3", result);
        assertEquals(3, receivedChunks.size());
        assertEquals("line 1\n", receivedChunks.get(0));
        assertEquals("line 2\n", receivedChunks.get(1));
        assertEquals("line 3", receivedChunks.get(2));
    }

    @Test
    void processPromptStreamEmitsThinkingChunkAndDoneEvents() {
        ChatMemoryService chatMemoryService = new ChatMemoryService();
        StaticApplicationContext context = new StaticApplicationContext();
        context.refresh();

        AiOrchestrationService service = new AiOrchestrationService(
                Collections.emptyList(),
                context,
                chatMemoryService,
                List.of(copilotMock),
                "copilot",
                "gpt-5",
                "ifw-microservices",
                new ByteArrayResource("system-prompt".getBytes())
        );

        PromptRequest request = PromptRequest.builder()
                .prompt("Hello")
                .mode("chat")
                .build();

        List<ServerSentEvent<ChatStreamEvent>> events = service.processPromptStream(request).collectList().block();
        assertNotNull(events);
        assertFalse(events.isEmpty());

        List<String> eventTypes = events.stream().map(ServerSentEvent::event).toList();
        assertTrue(eventTypes.contains("thinking"), "Stream should emit thinking events");
        assertTrue(eventTypes.contains("chunk"), "Stream should emit chunk events");
        assertTrue(eventTypes.contains("done"), "Stream should emit done event");

        ServerSentEvent<ChatStreamEvent> doneEvent = events.stream()
                .filter(e -> "done".equals(e.event()))
                .findFirst()
                .orElse(null);
        assertNotNull(doneEvent);
        assertNotNull(doneEvent.data());
        assertNotNull(doneEvent.data().getResponse());
        assertTrue(doneEvent.data().getResponse().getResponse().contains("Hello, I am EMH AI ASSISTANT"));
    }

    @Test
    void antigravityServiceExecutesPromptSuccessfully() throws Exception {
        if (!antigravityService.isAvailable()) {
            return;
        }
        String response = antigravityService.sendAndWait("test-live", "Reply with exactly: PONG");
        assertNotNull(response);
        assertFalse(response.isBlank());
        assertTrue(response.contains("PONG"));
    }
}
