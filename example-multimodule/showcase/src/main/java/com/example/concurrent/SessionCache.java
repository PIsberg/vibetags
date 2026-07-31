package com.example.concurrent;

import se.deversity.vibetags.annotations.AIThreadSafe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session cache shared across all request threads.
 *
 * <p>Demonstrates {@code @AIThreadSafe} — the class declares its concurrency strategy
 * (lock-free, backed by {@link ConcurrentHashMap}). AI assistants must preserve that
 * invariant when modifying the class.
 */
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.LOCK_FREE,
    note = "All mutations go through ConcurrentHashMap; never introduce a synchronized block on the cache map."
)
public class SessionCache {

    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    public void put(String sessionId, String userId) {
        sessions.put(sessionId, userId);
    }

    public String get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void invalidate(String sessionId) {
        sessions.remove(sessionId);
    }
}
