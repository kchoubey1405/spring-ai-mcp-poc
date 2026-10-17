package com.schneider.ai.service;

import com.schneider.ai.model.ChatSession;
import com.schneider.ai.model.ChatSession.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages in-memory chat sessions with automatic expiry.
 * - Each user gets independent session(s)
 * - Sessions expire after configurable TTL (default 5 minutes)
 * - Cleanup runs every 60 seconds
 */
@Slf4j
@Service
public class ChatSessionService {

    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Duration sessionTtl;

    public ChatSessionService(
            @Value("${ai.session.ttl-minutes:5}") int ttlMinutes) {
        this.sessionTtl = Duration.ofMinutes(ttlMinutes);
        log.info("Chat session TTL set to {} minutes", ttlMinutes);
    }

    /**
     * Create a new session for a user.
     */
    public ChatSession createSession(String userId, String userName) {
        String sessionId = UUID.randomUUID().toString();
        ChatSession session = ChatSession.builder()
                .sessionId(sessionId)
                .userId(userId)
                .userName(userName)
                .messages(new ArrayList<>())
                .createdAt(Instant.now())
                .lastAccessedAt(Instant.now())
                .build();
        sessions.put(sessionId, session);
        log.info("Created session {} for user {}", sessionId, userId);
        return session;
    }

    /**
     * Get an existing session, or create one if it doesn't exist.
     */
    public ChatSession getOrCreateSession(String sessionId, String userId, String userName) {
        if (sessionId != null && sessions.containsKey(sessionId)) {
            ChatSession session = sessions.get(sessionId);
            if (!isExpired(session)) {
                session.touch();
                return session;
            } else {
                log.info("Session {} expired, creating new one", sessionId);
                sessions.remove(sessionId);
            }
        }
        return createSession(userId, userName);
    }

    /**
     * Get session by ID. Returns null if expired or not found.
     */
    public ChatSession getSession(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) return null;
        if (isExpired(session)) {
            sessions.remove(sessionId);
            log.info("Session {} expired and removed on access", sessionId);
            return null;
        }
        session.touch();
        return session;
    }

    /**
     * Add a message to a session's history.
     */
    public void addMessage(String sessionId, String role, String content) {
        ChatSession session = sessions.get(sessionId);
        if (session != null) {
            session.addMessage(ChatMessage.builder()
                    .role(role)
                    .content(content)
                    .timestamp(Instant.now())
                    .build());
        }
    }

    /**
     * Get conversation history for a session, formatted for the AI model.
     * Returns the last N messages to keep context window manageable.
     */
    public List<ChatMessage> getConversationHistory(String sessionId, int maxMessages) {
        ChatSession session = sessions.get(sessionId);
        if (session == null) return Collections.emptyList();

        List<ChatMessage> messages = session.getMessages();
        if (messages.size() <= maxMessages) return messages;

        return messages.subList(messages.size() - maxMessages, messages.size());
    }

    /**
     * Get all active sessions for a user.
     */
    public List<ChatSession> getUserSessions(String userId) {
        return sessions.values().stream()
                .filter(s -> s.getUserId().equals(userId) && !isExpired(s))
                .sorted(Comparator.comparing(ChatSession::getLastAccessedAt).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Delete a specific session.
     */
    public void deleteSession(String sessionId) {
        ChatSession removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("Deleted session {} for user {}", sessionId, removed.getUserId());
        }
    }

    /**
     * Scheduled cleanup: remove expired sessions every 60 seconds.
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredSessions() {
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> isExpired(entry.getValue()));
        int removed = before - sessions.size();
        if (removed > 0) {
            log.info("Cleaned up {} expired sessions. Active sessions: {}", removed, sessions.size());
        }
    }

    /**
     * Get count of active sessions.
     */
    public int getActiveSessionCount() {
        return (int) sessions.values().stream().filter(s -> !isExpired(s)).count();
    }

    private boolean isExpired(ChatSession session) {
        return Duration.between(session.getLastAccessedAt(), Instant.now()).compareTo(sessionTtl) > 0;
    }
}
