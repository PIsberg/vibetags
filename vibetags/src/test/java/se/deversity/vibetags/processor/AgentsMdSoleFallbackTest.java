package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the AGENTS.md "sole-file fallback" rule, in both directions:
 *
 * <ul>
 *   <li><b>Sole file</b> — when {@code AGENTS.md} is the only AI config file present, VibeTags
 *       manages it and writes the Codex guardrail content.</li>
 *   <li><b>Coexisting</b> — when {@code AGENTS.md} sits alongside another AI config file
 *       (e.g. {@code CLAUDE.md}), it is treated as a likely pointer and left untouched, while the
 *       other file is still generated.</li>
 * </ul>
 */
class AgentsMdSoleFallbackTest {

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static final String LOCKED_SOURCE =
        "package com.example.payment;\n" +
        "import se.deversity.vibetags.annotations.AILocked;\n" +
        "@AILocked(reason = \"Core payment logic - do not refactor\")\n" +
        "public class PaymentProcessor {}\n";

    // -----------------------------------------------------------------------
    // Sole file → AGENTS.md IS written
    // -----------------------------------------------------------------------

    @Test
    void agentsMdAloneIsWritten(@TempDir Path tempDir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("AGENTS.md");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        String agents = h.readFile("AGENTS.md");
        assertFalse(agents.isBlank(),
            "AGENTS.md must be generated when it is the only AI config file");
        assertTrue(agents.contains("PaymentProcessor"),
            "AGENTS.md must list the @AILocked element");
        assertTrue(agents.contains("LOCKED FILES"),
            "AGENTS.md must contain the Codex locked-files section");
    }

    // -----------------------------------------------------------------------
    // Coexisting with another AI file → AGENTS.md is left untouched
    // -----------------------------------------------------------------------

    @Test
    void agentsMdWithClaudeIsSkipped(@TempDir Path tempDir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("AGENTS.md");
        h.touchOptIn("CLAUDE.md");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        assertTrue(h.readFile("AGENTS.md").isEmpty(),
            "AGENTS.md must be left untouched when another AI config file is present");
        String claude = h.readFile("CLAUDE.md");
        assertFalse(claude.isBlank(), "CLAUDE.md must still be generated");
        assertTrue(claude.contains("PaymentProcessor"),
            "CLAUDE.md must list the @AILocked element");
    }

    @Test
    void agentsMdWithIgnoreFileOnlyIsAlsoSkipped(@TempDir Path tempDir) throws IOException {
        // Even a single non-AGENTS opt-in file (here an ignore file) counts as "another AI file".
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("AGENTS.md");
        h.touchOptIn(".cursorrules");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        assertTrue(h.readFile("AGENTS.md").isEmpty(),
            "AGENTS.md must be skipped whenever any other AI config file opts in");
        assertFalse(h.readFile(".cursorrules").isBlank(),
            ".cursorrules must still be generated");
    }

    // -----------------------------------------------------------------------
    // The Codex sidecar config follows AGENTS.md (skipped when coexisting)
    // -----------------------------------------------------------------------

    @Test
    void codexSidecarConfigSkippedWhenAgentsMdSkipped(@TempDir Path tempDir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("AGENTS.md");
        h.touchOptIn("CLAUDE.md");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        // Codex is disabled entirely, so its sidecar config is never created.
        assertFalse(h.fileExists(".codex/config.toml"),
            "Codex sidecar config must not be generated when AGENTS.md is skipped");
    }
}
