package com.schneider.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a chat session with in-memory message history.
 * Each user gets their own session. Sessions expire after 5 minutes of inactivity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    private String sessionId;
    private String userId;
    private String userName;

    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private Instant lastAccessedAt = Instant.now();

    public void addMessage(ChatMessage message) {
        this.messages.add(message);
        this.lastAccessedAt = Instant.now();
    }

    public void touch() {
        this.lastAccessedAt = Instant.now();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatMessage {
        private String role;    // "user" or "assistant"
        private String content;

        @Builder.Default
        private Instant timestamp = Instant.now();
    }
}
