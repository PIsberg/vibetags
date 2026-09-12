package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the two files added for Gemini Code Assist and aider.
 *
 * <p>{@code .gemini/styleguide.md} is Gemini Code Assist's per-repository review style guide, which
 * is a different product from the Gemini CLI's {@code GEMINI.md}: one reviews pull requests on
 * GitHub, the other runs in a terminal, and neither reads the other's file.
 *
 * <p>{@code .aider.conf.yml} fixes a defect rather than adding reach. aider does not load
 * {@code CONVENTIONS.md} unless something names it, so the conventions file VibeTags had generated
 * since v0.5.0 was never opened by an aider session. The {@code read:} entry asserted below is the
 * whole point of the file; a test that only checked the file exists would have passed against the
 * broken state too.
 */
@Tag("e2e")
class GeminiCodeAssistAndAiderConfEndToEndTest {

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
    void bothFilesAreWritten() {
        assertTrue(harness.fileExists(".gemini/styleguide.md"), ".gemini/styleguide.md must be written");
        assertTrue(harness.fileExists(".aider.conf.yml"), ".aider.conf.yml must be written");
    }

    @Test
    void styleguideCarriesMarkdownMarkersAndTheLockedClass() throws IOException {
        String content = harness.readFile(".gemini/styleguide.md");
        assertTrue(content.contains("<!-- VIBETAGS-START"),
            ".md must get HTML-comment markers so hand-authored text around them survives, was:\n" + content);
        assertTrue(content.contains("<!-- VIBETAGS-END"), "missing closing marker, was:\n" + content);
        assertTrue(content.contains("PaymentProcessor"),
            "the @AILocked class must reach the reviewer's style guide, was:\n" + content);
    }

    /**
     * The assertion that would have failed before this platform existed: aider only loads
     * {@code CONVENTIONS.md} when something names it.
     */
    @Test
    void aiderConfNamesTheConventionsFile() throws IOException {
        String content = harness.readFile(".aider.conf.yml");
        assertTrue(content.contains("read:"), ".aider.conf.yml must declare a read: key, was:\n" + content);
        assertTrue(content.contains("- CONVENTIONS.md"),
            "read: must name CONVENTIONS.md or aider never opens it, was:\n" + content);
    }

    /** A .yml file gets hash markers, not HTML comments, or the YAML does not parse. */
    @Test
    void aiderConfUsesHashMarkers() throws IOException {
        String content = harness.readFile(".aider.conf.yml");
        assertTrue(content.contains("# VIBETAGS-START"),
            ".yml must get hash markers, was:\n" + content);
        assertFalse(content.contains("<!-- VIBETAGS-START"),
            "an HTML comment in a YAML document is a syntax error, was:\n" + content);
    }

    /** One read: key, once. A duplicate top-level key silently loses one of the two to PyYAML. */
    @Test
    void aiderConfDeclaresReadExactlyOnce() throws IOException {
        long reads = harness.readFile(".aider.conf.yml").lines()
            .filter(line -> line.strip().equals("read:"))
            .count();
        assertEquals(1, reads, "exactly one read: key, or aider keeps only the last one");
    }

    /**
     * A string-contains assertion would pass on a document aider cannot parse, so parse it for
     * real and assert on the value aider actually reads.
     */
    @Test
    void aiderConfParsesToTheReadListAiderExpects() throws IOException {
        Object parsed = new Yaml().load(harness.readFile(".aider.conf.yml"));
        assertTrue(parsed instanceof Map, "must be a YAML mapping, was: " + parsed);
        Object read = ((Map<?, ?>) parsed).get("read");
        assertTrue(read instanceof List, "read: must be a list, was: " + read);
        assertEquals(List.of("CONVENTIONS.md"), read);
    }
}
