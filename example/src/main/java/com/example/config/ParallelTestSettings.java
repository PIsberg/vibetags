package com.example.config;

import se.deversity.vibetags.annotations.AIParallelTests;

/**
 * Demo configuration class annotated with {@link AIParallelTests}.
 * Dictates that AI-generated tests for this module must avoid shared mutable state
 * and respect parallel execution parameters.
 */
@AIParallelTests(reason = "Tests here bind to fixed port 8080; a shared static counter caused flaky CI in build #4471 — keep cases isolated")
public class ParallelTestSettings {
    public static final int THREAD_COUNT = 4;
}
