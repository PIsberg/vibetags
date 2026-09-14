package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Junie's current guidelines file, {@code .junie/AGENTS.md} (#673).
 *
 * <p>junie.jetbrains.com/docs/guidelines-and-memory.html lists the lookup order Junie CLI uses:
 * {@code .junie/AGENTS.md} first, then the root {@code AGENTS.md} with {@code .junie/rules/*.md},
 * then {@code .junie/guidelines.md}, "Junie's legacy format for guidelines (still supported)".
 *
 * <p>The file shares a name with the root {@code AGENTS.md}, which VibeTags writes only as the sole
 * AI config file or when it already carries a marker pair (tier-1 invariant 4). The two are
 * different paths with different service keys, and these tests pin that the Junie file neither
 * takes over the root file's content nor changes when the root file is written: the root file
 * behaves beside {@code .junie/AGENTS.md} exactly as it does beside {@code .junie/guidelines.md}.
 */
@Tag("e2e")
class JunieAgentsMdEndToEndTest {

    private static final String LOCKED_SOURCE =
        "package com.example.payment;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"Core payment logic - do not refactor\")\n"
            + "public class PaymentProcessor {}\n";

    private static final String POINTER_AGENTS_MD =
        "# AGENTS.md\n\nRead CLAUDE.md, this file is intentionally a pointer.\n";

    private static final String MARKED_AGENTS_MD =
        "# AGENTS.md\n\nHand-written preamble.\n\n<!-- VIBETAGS-START -->\n<!-- VIBETAGS-END -->\n";

    /** The header only a Junie rendering carries; the Codex rendering of AGENTS.md opens differently. */
    private static final String JUNIE_HEADING = "# JetBrains Junie Guidelines";

    /** The header only the Codex rendering of the root AGENTS.md carries. */
    private static final String CODEX_HEADING = "# AUTO-GENERATED AI RULES";

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void junieAgentsMdIsWrittenWithTheGuardrails(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn(".junie/AGENTS.md");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        String junie = h.readFile(".junie/AGENTS.md");
        assertTrue(junie.contains(JUNIE_HEADING), "the Junie heading:\n" + junie);
        assertTrue(junie.contains("<!-- VIBETAGS-START -->") && junie.contains("<!-- VIBETAGS-END -->"),
            "a markdown file gets the HTML comment markers:\n" + junie);
        assertTrue(junie.contains("com.example.payment.PaymentProcessor")
                && junie.contains("Core payment logic - do not refactor"),
            "the @AILocked element and its reason:\n" + junie);
        assertFalse(Files.exists(root.resolve("AGENTS.md")),
            "opting into .junie/AGENTS.md must not create the root AGENTS.md (invariant 1)");
    }

    /**
     * The root AGENTS.md beside either Junie file. Run once per Junie path: the result for the root
     * file must be the same, because to the sole-file rule the Junie file is just another opt-in.
     *
     * <p>The {@code .junie/AGENTS.md} case was red before #673, and not only because the Junie file
     * went unwritten: no service claimed that path, so a hand-written root AGENTS.md beside it
     * counted as the sole AI config file and had the Codex rendering written into it.
     */
    @ParameterizedTest
    @ValueSource(strings = {".junie/guidelines.md", ".junie/AGENTS.md"})
    void anUnmarkedRootAgentsMdStaysUntouchedBesideEitherJunieFile(String junieFile, @TempDir Path root)
            throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("AGENTS.md"), POINTER_AGENTS_MD);
        h.touchOptIn(junieFile);
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        assertEquals(POINTER_AGENTS_MD, h.readFile("AGENTS.md"),
            "an unmarked root AGENTS.md beside " + junieFile + " is a pointer and stays byte for byte");
        assertTrue(h.readFile(junieFile).contains("com.example.payment.PaymentProcessor"),
            junieFile + " is still written");
    }

    @ParameterizedTest
    @ValueSource(strings = {".junie/guidelines.md", ".junie/AGENTS.md"})
    void aMarkedRootAgentsMdKeepsItsCodexContentBesideEitherJunieFile(String junieFile, @TempDir Path root)
            throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("AGENTS.md"), MARKED_AGENTS_MD);
        h.touchOptIn(junieFile);
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        String agents = h.readFile("AGENTS.md");
        assertTrue(agents.contains(CODEX_HEADING) && agents.contains("com.example.payment.PaymentProcessor"),
            "the marked root AGENTS.md is still the Codex rendering:\n" + agents);
        assertFalse(agents.contains(JUNIE_HEADING), "no Junie content in the root file:\n" + agents);
        assertTrue(agents.contains("Hand-written preamble."), "hand-written text survives:\n" + agents);

        String junie = h.readFile(junieFile);
        assertTrue(junie.contains(JUNIE_HEADING), junieFile + " is the Junie rendering:\n" + junie);
        assertFalse(junie.contains(CODEX_HEADING), "no Codex content in " + junieFile + ":\n" + junie);
    }

    /**
     * File presence is the opt-in, so a project that has both Junie files gets both. Junie's page
     * gives a lookup order with .junie/AGENTS.md first and does not say it loads more than one of
     * them; docs/PLATFORMS.md records that and advises keeping only .junie/AGENTS.md.
     */
    @Test
    void bothJunieFilesAreWrittenWhenBothExist(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn(".junie/AGENTS.md");
        h.touchOptIn(".junie/guidelines.md");
        h.addSource("com.example.payment.PaymentProcessor", LOCKED_SOURCE);
        h.compile();

        String current = h.readFile(".junie/AGENTS.md");
        String legacy = h.readFile(".junie/guidelines.md");
        assertTrue(current.contains("com.example.payment.PaymentProcessor"), current);
        assertEquals(legacy, current, "the two Junie files carry the same rendering");
    }
}
