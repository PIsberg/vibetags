package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
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
 *   <li><b>Marker escape hatch</b> — unless the coexisting {@code AGENTS.md} already carries a
 *       VibeTags marker pair, which means VibeTags wrote it and can safely refresh it.</li>
 * </ul>
 */
@Tag("e2e")
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

    /** Hand-authored text that must survive every regeneration. */
    private static final String HAND_WRITTEN = "Hand-written preamble that must survive.";

    private static final String MARKED_AGENTS_MD =
        "# AGENTS.md\n\n"
        + HAND_WRITTEN + "\n\n"
        + "<!-- VIBETAGS-START -->\n"
        + "<!-- VIBETAGS-END -->\n";

    private static final String POINTER_AGENTS_MD =
        "# AGENTS.md\n\nRead CLAUDE.md — this file is intentionally a pointer.\n";

    // -----------------------------------------------------------------------
    // Sole file → AGENTS.md IS written
    // -----------------------------------------------------------------------

    private static final String SECOND_LOCKED_SOURCE =
        "package com.example.ledger;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"Ledger totals are reconciled nightly\")\n"
            + "public class Ledger {}\n";

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

    /**
     * Regression guard for the marker escape hatch: a hand-authored pointer with real prose but
     * <em>no</em> markers must still be protected. Only the marker pair opts a file in.
     */
    @Test
    void handWrittenPointerWithoutMarkersIsStillProtected(@TempDir Path tempDir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        Files.writeString(h.root().resolve("AGENTS.md"), POINTER_AGENTS_MD);
        h.touchOptIn("CLAUDE.md");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        assertEquals(POINTER_AGENTS_MD, h.readFile("AGENTS.md"),
            "An unmarked AGENTS.md pointer must be left byte-for-byte untouched");
        assertFalse(h.readFile("CLAUDE.md").isBlank(), "CLAUDE.md must still be generated");
    }

    // -----------------------------------------------------------------------
    // Marker escape hatch → a marked AGENTS.md is managed even alongside CLAUDE.md
    // -----------------------------------------------------------------------

    @Test
    void markedAgentsMdIsWrittenAlongsideClaude(@TempDir Path tempDir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        Files.writeString(h.root().resolve("AGENTS.md"), MARKED_AGENTS_MD);
        h.touchOptIn("CLAUDE.md");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        String agents = h.readFile("AGENTS.md");
        assertTrue(agents.contains("PaymentProcessor"),
            "A marked AGENTS.md must be regenerated even when CLAUDE.md is also present");
        assertTrue(agents.contains(HAND_WRITTEN),
            "Hand-authored content outside the markers must survive regeneration");
        assertFalse(h.readFile("CLAUDE.md").isBlank(), "CLAUDE.md must still be generated");
    }

    /**
     * The escape hatch reached the way a project actually reaches it, over time rather than by
     * hand. AGENTS.md is written while it is the sole AI config file, which leaves it carrying a
     * marker pair; a second platform is opted in later, so it is no longer sole. The marker pair
     * is what decides from then on, and it is already there.
     *
     * <p>Without this the rule reads as "sole file" and a project that adds a second platform
     * would find AGENTS.md silently frozen on whatever it said that day — still generated-looking,
     * markers and all, and no longer true.
     */
    @Test
    void agentsMdWrittenWhileSole_keepsUpdatingOnceASecondPlatformArrives(@TempDir Path tempDir)
            throws IOException {
        ProcessorTestHarness first = new ProcessorTestHarness(tempDir, false);
        first.touchOptIn("AGENTS.md");
        first.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        first.compile();
        assertTrue(first.readFile("AGENTS.md").contains("PaymentProcessor"),
            "precondition: sole config file, so it is written and now carries markers");
        VibeTagsLogger.shutdown();

        ProcessorTestHarness second = new ProcessorTestHarness(tempDir, false);
        second.touchOptIn("AGENTS.md");
        second.touchOptIn("CLAUDE.md");
        second.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        second.addSource("com.example.ledger.Ledger", SECOND_LOCKED_SOURCE);
        second.compile();

        String agents = second.readFile("AGENTS.md");
        assertTrue(agents.contains("Ledger"),
            "an AGENTS.md that already carries a marker pair must keep updating once it stops "
                + "being the sole config file, not freeze on what it said that day: " + agents);
        assertTrue(second.readFile("CLAUDE.md").contains("Ledger"),
            "and the newly opted-in platform must be written too");
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
