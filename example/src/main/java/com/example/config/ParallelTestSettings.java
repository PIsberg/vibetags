package com.example.config;

import se.deversity.vibetags.annotations.AIParallelTests;

/**
 * Demo configuration class annotated with {@link AIParallelTests}.
 * Dictates that AI-generated tests for this module must avoid shared mutable state
 * and respect parallel execution parameters.
 */
@AIParallelTests
public class ParallelTestSettings {
    public static final int THREAD_COUNT = 4;
}
