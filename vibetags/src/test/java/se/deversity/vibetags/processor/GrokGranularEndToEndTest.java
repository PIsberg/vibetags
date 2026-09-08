package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the Grok Build granular platform ({@code .grok/rules/}).
 *
 * <p>Grok Build's rule discovery, verified against xai-org/grok-build's own user guide
 * (docs/user-guide/12-project-rules.md) rather than third-party write-ups, has three properties
 * that each pin an assertion here:
 *
 * <ul>
 *   <li>It reads <em>every</em> {@code *.md} in {@code .grok/rules/}, unconditionally and in
 *       alphabetical order. Nothing is loaded on demand, so a file with any other extension is
 *       silently invisible rather than lazily loaded.</li>
 *   <li>It parses no YAML front matter — no {@code globs}, {@code description} or
 *       {@code alwaysApply}. A front-matter block would reach the model as literal text.</li>
 *   <li>It has no VibeTags aggregate file of its own: Grok reads {@code AGENTS.md} natively.
 *       Opting into {@code .grok/rules/} must therefore not collapse any other platform's
 *       aggregate to a scoped-rules index.</li>
 * </ul>
 */
@Tag("e2e")
class GrokGranularEndToEndTest {

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
    void grokGranular_lockedClassFileIsGenerated() {
        assertTrue(harness.fileExists(".grok/rules/com-example-payment-PaymentProcessor.md"),
            ".grok/rules/ must contain a file for the @AILocked class");
    }

    @Test
    void grokGranular_auditClassFileIsGenerated() {
        assertTrue(harness.fileExists(".grok/rules/com-example-database-DatabaseConnector.md"),
            ".grok/rules/ must contain a file for the @AIAudit class");
    }

    @Test
    void grokGranular_fileContainsHeading() throws IOException {
        String content = harness.readFile(".grok/rules/com-example-payment-PaymentProcessor.md");
        assertTrue(content.contains("# Rules for "),
            "Grok rule file must open with a '# Rules for <element>' heading, was:\n" + content);
    }

    @Test
    void grokGranular_fileContainsLockedContent() throws IOException {
        String content = harness.readFile(".grok/rules/com-example-payment-PaymentProcessor.md");
        assertTrue(content.contains("Locked Status") || content.contains("LOCKED")
                || content.contains("must not be modified"),
            "Grok rule file must carry the locked guardrail, was:\n" + content);
    }

    @Test
    void grokGranular_fileContainsVibeTagsMarkers() throws IOException {
        String content = harness.readFile(".grok/rules/com-example-payment-PaymentProcessor.md");
        assertTrue(content.contains("VIBETAGS-START"),
            "Grok rule file must carry the marker pair so hand-authored content survives");
    }

    /**
     * Grok parses no front matter. A {@code globs:}/{@code alwaysApply:} block copied from the
     * Cursor or Windsurf format would not scope anything; it would be read aloud as rule text.
     */
    @Test
    void grokGranular_fileHasNoYamlFrontMatter() throws IOException {
        String content = harness.readFile(".grok/rules/com-example-payment-PaymentProcessor.md");
        assertFalse(content.startsWith("---"),
            "Grok rule files must have no YAML front matter, was:\n" + content);
        assertFalse(content.contains("alwaysApply:"),
            "Grok reads no front matter, so an alwaysApply key is literal text in the model's context");
    }

    /**
     * Grok globs {@code .grok/rules/*.md}. Any other extension is not "loaded later", it is never
     * loaded at all, which is why the extension is asserted over the whole directory and not just
     * on the one file the other tests read.
     */
    @Test
    void grokGranular_everyGeneratedFileIsMarkdown() throws IOException {
        Path dir = harness.root().resolve(".grok/rules");
        assertTrue(Files.isDirectory(dir), ".grok/rules must exist after a run that opted into it");
        List<String> names;
        try (Stream<Path> entries = Files.list(dir)) {
            names = entries
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(n -> !n.equals(".vibetags"))  // the opt-in signal file, not generated output
                .toList();
        }
        assertFalse(names.isEmpty(), ".grok/rules was opted in but nothing was written to it");
        assertTrue(names.stream().allMatch(n -> n.endsWith(".md")),
            "Grok reads only *.md from .grok/rules; anything else is invisible to it: " + names);
    }

    /**
     * Grok has no VibeTags aggregate: it reads AGENTS.md natively and {@code .claude/rules/} for
     * compatibility. So {@code grok_granular} must not appear in
     * {@code GranularIndexSection.governingGranularKey}, and no aggregate may point into
     * {@code .grok/rules}. Without this, opting into Grok's directory would quietly strip a
     * Claude or Cursor user's aggregate down to an index.
     */
    @Test
    void grokGranular_doesNotCollapseAnyAggregateToAnIndex() throws IOException {
        for (String aggregate : List.of("CLAUDE.md", ".cursorrules", ".windsurfrules", "GEMINI.md")) {
            String content = harness.readFile(aggregate);
            assertFalse(content.contains(".grok/rules"),
                aggregate + " must not index Grok's scoped rules: Grok reads them itself, and a "
                    + "pointer there means the aggregate was collapsed by the wrong platform");
        }
    }
}
