package com.example.config;

import se.deversity.vibetags.annotations.AIImmutable;

/**
 * Configuration value object for the async test runner.
 *
 * <p>Demonstrates {@code @AIImmutable} — declares the type as immutable so AI assistants
 * never introduce setters or non-final fields. The processor warns at compile time if
 * any instance field is non-final.
 */
@AIImmutable(note = "Used by every test runner; safe to share across threads without copies.")
public final class AsyncTestConfig {

    private final int timeoutMs;
    private final int maxRetries;
    private final String runnerName;

    public AsyncTestConfig(int timeoutMs, int maxRetries, String runnerName) {
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.runnerName = runnerName;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getRunnerName() {
        return runnerName;
    }
}
