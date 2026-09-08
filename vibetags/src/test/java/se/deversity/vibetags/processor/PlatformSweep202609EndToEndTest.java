package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the four platforms added by the 2026-09 sweep: Antigravity
 * ({@code .agents/rules/}), JetBrains AI Assistant ({@code .aiassistant/rules/}), Augment Code
 * ({@code .augment/rules/}) and goose ({@code .goosehints}).
 *
 * <p>All three granular platforms write front-matter-free Markdown, each for a reason taken from
 * the vendor's own documentation rather than from a round-up:
 *
 * <ul>
 *   <li><b>Antigravity</b> activates a rule as Always On, Glob, Model Decision or Manual, and its
 *       docs describe no front-matter format for choosing between them.</li>
 *   <li><b>JetBrains AI Assistant</b> selects the rule type in IDE settings, not in the file.</li>
 *   <li><b>Augment Code</b> documents {@code type: always_apply | agent_requested} front matter and
 *       defaults a file without it to {@code always_apply}. Always-applied is the correct default
 *       for a guardrail: {@code agent_requested} hands the decision to the model, and a locked-file
 *       rule that might not load is worse than one that always does.</li>
 * </ul>
 */
@Tag("e2e")
class PlatformSweep202609EndToEndTest {

    /** Antigravity caps a single rule file at 12,000 characters. */
    private static final int ANTIGRAVITY_RULE_LIMIT = 12_000;

    private static final List<String> GRANULAR_DIRS =
        List.of(".agents/rules", ".aiassistant/rules", ".augment/rules");

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = ProcessorTestHarness.withExampleSources(tempDir);
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void everyGranularDirectoryGetsAFileForTheLockedClass() {
        for (String dir : GRANULAR_DIRS) {
            assertTrue(harness.fileExists(dir + "/com-example-payment-PaymentProcessor.md"),
                dir + "/ must contain a file for the @AILocked class");
        }
    }

    @Test
    void everyGranularFileCarriesItsHeadingMarkersAndLockedContent() throws IOException {
        for (String dir : GRANULAR_DIRS) {
            String content = harness.readFile(dir + "/com-example-payment-PaymentProcessor.md");
            assertTrue(content.contains("# Rules for "), dir + ": missing heading, was:\n" + content);
            assertTrue(content.contains("VIBETAGS-START"),
                dir + ": missing marker pair, so hand-authored content would not survive");
            assertTrue(content.contains("Locked Status") || content.contains("LOCKED")
                    || content.contains("must not be modified"),
                dir + ": missing the locked guardrail, was:\n" + content);
        }
    }

    /**
     * None of the three parses front matter for activation. A {@code globs:} or
     * {@code alwaysApply:} block copied from the Cursor format would scope nothing and would land
     * in the model's context as literal rule text.
     */
    @Test
    void noGranularFileCarriesFrontMatter() throws IOException {
        for (String dir : GRANULAR_DIRS) {
            String content = harness.readFile(dir + "/com-example-payment-PaymentProcessor.md");
            assertFalse(content.startsWith("---"), dir + ": unexpected front matter:\n" + content);
            assertFalse(content.contains("alwaysApply:"), dir + ": Cursor-shaped key leaked in");
            assertFalse(content.contains("agent_requested"),
                dir + ": agent_requested leaves loading to the model; always-applied is the "
                    + "documented default for a front-matter-free Augment rule and the safe one here");
        }
    }

    /**
     * Antigravity caps a rule file at 12,000 characters. #609 flagged this as the one constraint the
     * codebase had never had to respect, and unlike the other assertions here it is a property of
     * the <em>content</em>, so it can start failing later from an annotation change alone rather
     * than from a code change. Asserted over every file in the directory for that reason.
     */
    @Test
    void noAntigravityRuleExceedsTheDocumentedCharacterCap() throws IOException {
        Path dir = harness.root().resolve(".agents/rules");
        assertTrue(Files.isDirectory(dir), ".agents/rules must exist after an opted-in run");
        List<String> oversized = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path file : entries.toList()) {
                String name = file.getFileName().toString();
                if (name.equals(".vibetags")) {
                    continue;
                }
                int length = Files.readString(file, StandardCharsets.UTF_8).length();
                if (length > ANTIGRAVITY_RULE_LIMIT) {
                    oversized.add(name + " (" + length + " chars)");
                }
            }
        }
        assertTrue(oversized.isEmpty(),
            "Antigravity truncates or rejects a rule file over " + ANTIGRAVITY_RULE_LIMIT
                + " characters, so these would be silently incomplete guardrails: " + oversized
                + ". Role grouping via .vibetags-roles is the likely cause when this fires; splitting "
                + "the role is the fix, not raising this number.");
    }

    @Test
    void goosehintsIsGeneratedWithMarkersAndLockedContent() throws IOException {
        assertTrue(harness.fileExists(".goosehints"), ".goosehints must be generated when opted in");
        String content = harness.readFile(".goosehints");
        assertTrue(content.contains("VIBETAGS-START"), ".goosehints must carry the marker pair");
        assertTrue(content.contains("PaymentProcessor"),
            ".goosehints must name the @AILocked element, was:\n" + content);
    }

    /**
     * None of the four has a VibeTags aggregate sibling, so none may appear in
     * {@code GranularIndexSection.governingGranularKey}. If one did, opting into it would strip a
     * Claude or Cursor user's aggregate down to a scoped-rules index as a side effect.
     */
    @Test
    void noneOfTheseCollapsesAnotherPlatformsAggregate() throws IOException {
        for (String aggregate : List.of("CLAUDE.md", ".cursorrules", ".windsurfrules", "GEMINI.md")) {
            String content = harness.readFile(aggregate);
            for (String dir : GRANULAR_DIRS) {
                assertFalse(content.contains(dir),
                    aggregate + " must not index " + dir + ": these platforms read their own "
                        + "directories, and a pointer here means the aggregate was collapsed by the "
                        + "wrong platform");
            }
        }
    }
}
