package com.schneider.ai.controller;

import com.schneider.ai.model.ChatStreamEvent;
import com.schneider.ai.model.PromptRequest;
import com.schneider.ai.model.PromptResponse;
import com.schneider.ai.service.AiOrchestrationService;
import com.schneider.ai.service.ChatMemoryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class PromptController {

    private final AiOrchestrationService aiOrchestrationService;
    private final ChatMemoryService chatMemoryService;

    /**
     * Process a prompt using the configured AI model with MCP tool access.
     * Supports session-based conversation memory (5-min TTL).
     *
     * Example request:
     * POST /api/v1/ai/prompt
     * {
     *   "prompt": "Create a Jira ticket for bug fix in payment module",
     *   "sessionId": "optional-existing-session-id",
     *   "mode": "agent",
     *   "enabledTools": ["jira", "git"]
     * }
     */
    @PostMapping("/prompt")
    public ResponseEntity<PromptResponse> processPrompt(@RequestBody PromptRequest request) {
        log.info("Received prompt request - provider: {}, session: {}, mode: {}",
            request.getProvider(), request.getSessionId(), request.getMode());
        PromptResponse response = aiOrchestrationService.processPrompt(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Reactive streaming chat endpoint (Server-Sent Events / Project Reactor Flux).
     * Streams thinking progress, MCP tool calls/results, line-by-line response chunks, and final completion.
     */
    @PostMapping(value = "/prompt/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamEvent>>> processPromptStream(
            @RequestBody PromptRequest request,
            HttpServletResponse response) {
        log.info("Received streaming prompt request - provider: {}, session: {}, mode: {}",
            request.getProvider(), request.getSessionId(), request.getMode());

        if (response != null) {
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
            response.setHeader("Connection", "keep-alive");
        }

        Flux<ServerSentEvent<ChatStreamEvent>> stream = aiOrchestrationService.processPromptStream(request);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .header("Connection", "keep-alive")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    /**
     * Create a new chat session. Returns a session ID to use in subsequent requests.
     */
    @PostMapping("/session")
    public ResponseEntity<Map<String, String>> createSession() {
        String sessionId = chatMemoryService.createSession();
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /**
     * Get session info (message count, expiry time, etc.).
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSessionInfo(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatMemoryService.getSessionInfo(sessionId));
    }

    /**
     * Delete/clear a specific session.
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, String>> clearSession(@PathVariable String sessionId) {
        aiOrchestrationService.clearProviderSession(sessionId);
        chatMemoryService.clearSession(sessionId);
        return ResponseEntity.ok(Map.of("status", "cleared", "sessionId", sessionId));
    }

    /**
     * Abort/cancel active generation or background processes for a session without clearing chat history.
     */
    @PostMapping("/session/{sessionId}/cancel")
    public ResponseEntity<Map<String, String>> cancelSession(@PathVariable String sessionId) {
        log.info("Client requested cancellation for session [{}]", sessionId);
        aiOrchestrationService.clearProviderSession(sessionId);
        return ResponseEntity.ok(Map.of("status", "cancelled", "sessionId", sessionId));
    }

    /**
     * Get count of active sessions (monitoring).
     */
    @GetMapping("/sessions/count")
    public ResponseEntity<Map<String, Integer>> getActiveSessionCount() {
        return ResponseEntity.ok(Map.of("activeSessions", chatMemoryService.getActiveSessionCount()));
    }

    /**
     * Health check endpoint to verify AI connectivity.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Spring AI MCP POC is running");
    }

    /**
     * Get available LLM providers and active configuration.
     */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getProviders() {
        return ResponseEntity.ok(aiOrchestrationService.getProviderInfo());
    }

    /**
     * List available MCP tools.
     */
    @GetMapping("/tools")
    public ResponseEntity<?> listTools() {
        return ResponseEntity.ok(aiOrchestrationService.listAvailableTools());
    }

    @GetMapping("/tools/diagnostics")
    public ResponseEntity<?> toolsDiagnostics() {
        return ResponseEntity.ok(aiOrchestrationService.getMcpDiagnostics());
    }
}
