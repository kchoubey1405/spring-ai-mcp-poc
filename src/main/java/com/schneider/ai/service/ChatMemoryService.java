package com.schneider.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory chat session memory that stores conversation history per session.
 * 
 * - Each session has its own message history
 * - Sessions auto-expire after 5 minutes of inactivity
 * - Supports multiple concurrent sessions (multi-user)
 * - Cleanup runs every minute to evict expired sessions
 */
@Slf4j
@Service
public class ChatMemoryService {

    private static final long SESSION_TTL_MILLIS = 5 * 60 * 1000; // 5 minutes
    private static final int MAX_HISTORY_PER_SESSION = 20; // Keep last 20 messages (10 exchanges)

    /**
     * Stores messages per sessionId.
     */
    private final ConcurrentHashMap<String, List<Message>> sessionMessages = new ConcurrentHashMap<>();

    /**
     * Tracks last activity time per sessionId for TTL eviction.
     */
    private final ConcurrentHashMap<String, Instant> sessionLastActivity = new ConcurrentHashMap<>();

    /**
     * Add a user message to the session history.
     */
    public void addUserMessage(String sessionId, String content) {
        touchSession(sessionId);
        getOrCreateSession(sessionId).add(new UserMessage(content));
        trimHistory(sessionId);
        log.debug("Session [{}]: Added user message. Total messages: {}", sessionId, sessionMessages.get(sessionId).size());
    }

    /**
     * Add an assistant response to the session history.
     */
    public void addAssistantMessage(String sessionId, String content) {
        touchSession(sessionId);
        getOrCreateSession(sessionId).add(new AssistantMessage(content));
        trimHistory(sessionId);
        log.debug("Session [{}]: Added assistant message. Total messages: {}", sessionId, sessionMessages.get(sessionId).size());
    }

    /**
     * Get the full conversation history for a session.
     * Returns an empty list if session doesn't exist or has expired.
     */
    public List<Message> getHistory(String sessionId) {
        if (!sessionMessages.containsKey(sessionId)) {
            return Collections.emptyList();
        }
        touchSession(sessionId);
        return Collections.unmodifiableList(sessionMessages.get(sessionId));
    }

    /**
     * Create a new session and return the generated session ID.
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessionMessages.put(sessionId, Collections.synchronizedList(new ArrayList<>()));
        sessionLastActivity.put(sessionId, Instant.now());
        log.info("Created new chat session: {}", sessionId);
        return sessionId;
    }

    /**
     * Check if a session exists and is active.
     */
    public boolean sessionExists(String sessionId) {
        return sessionId != null && sessionMessages.containsKey(sessionId);
    }

    /**
     * Manually clear a specific session.
     */
    public void clearSession(String sessionId) {
        sessionMessages.remove(sessionId);
        sessionLastActivity.remove(sessionId);
        log.info("Cleared chat session: {}", sessionId);
    }

    /**
     * Get count of active sessions.
     */
    public int getActiveSessionCount() {
        return sessionMessages.size();
    }

    /**
     * Get session info (for monitoring).
     */
    public Map<String, Object> getSessionInfo(String sessionId) {
        Map<String, Object> info = new HashMap<>();
        info.put("sessionId", sessionId);
        info.put("exists", sessionExists(sessionId));
        if (sessionExists(sessionId)) {
            info.put("messageCount", sessionMessages.get(sessionId).size());
            info.put("lastActivity", sessionLastActivity.get(sessionId));
            long remainingMs = SESSION_TTL_MILLIS -
                    (Instant.now().toEpochMilli() - sessionLastActivity.get(sessionId).toEpochMilli());
            info.put("expiresInSeconds", Math.max(0, remainingMs / 1000));
        }
        return info;
    }

    /**
     * Scheduled cleanup: runs every 60 seconds to evict expired sessions.
     */
    @Scheduled(fixedRate = 60000)
    public void evictExpiredSessions() {
        Instant cutoff = Instant.now().minusMillis(SESSION_TTL_MILLIS);
        List<String> expiredSessions = new ArrayList<>();

        sessionLastActivity.forEach((sessionId, lastActivity) -> {
            if (lastActivity.isBefore(cutoff)) {
                expiredSessions.add(sessionId);
            }
        });

        expiredSessions.forEach(sessionId -> {
            sessionMessages.remove(sessionId);
            sessionLastActivity.remove(sessionId);
            log.info("Evicted expired session: {} (inactive for 5+ minutes)", sessionId);
        });

        if (!expiredSessions.isEmpty()) {
            log.info("Evicted {} expired sessions. Active sessions remaining: {}",
                    expiredSessions.size(), sessionMessages.size());
        }
    }

    // --- Private helpers ---

    private List<Message> getOrCreateSession(String sessionId) {
        return sessionMessages.computeIfAbsent(sessionId,
                k -> Collections.synchronizedList(new ArrayList<>()));
    }

    private void touchSession(String sessionId) {
        sessionLastActivity.put(sessionId, Instant.now());
    }

    /**
     * Keep only the last MAX_HISTORY_PER_SESSION messages to prevent memory bloat.
     */
    private void trimHistory(String sessionId) {
        List<Message> messages = sessionMessages.get(sessionId);
        if (messages != null && messages.size() > MAX_HISTORY_PER_SESSION) {
            List<Message> trimmed = new ArrayList<>(messages.subList(
                    messages.size() - MAX_HISTORY_PER_SESSION, messages.size()));
            sessionMessages.put(sessionId, Collections.synchronizedList(trimmed));
        }
    }
}
