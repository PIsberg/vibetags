package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for Greptile: the legacy root {@code greptile.json} and the recommended
 * {@code .greptile/rules.md}.
 *
 * <p>{@code greptile.json} is the reason this test exists (#639). Real ones are richly
 * hand-configured, and a {@code .json} output used to be a whole-file overwrite, so opting in erased
 * every hand-set field on the first compile with nothing in any log to say so. The assertions here
 * are on what Greptile reads: the document parses, every field the user set is still there with the
 * same value, and the guardrails arrive inside the two string values VibeTags shares with the user.
 */
@Tag("e2e")
class GreptileEndToEndTest {

    /**
     * Shaped like {@code NVIDIA/Megatron-LM}'s real file, abridged in the issue: filters, arrays,
     * numbers, a nested object, and hand-written values in both keys VibeTags writes to. The
     * unicode escape in the instructions is there because re-encoding the user's text would change
     * its spelling while leaving the value equal, which a parse-only assertion cannot see.
     */
    static final String HAND_CONFIGURED = """
        {
            "labels": [],
            "comment": "Disclaimer: This is AI-generated.",
            "commentTypes": ["logic", "syntax", "style"],
            "instructions": "Only comment if the PR description is unchanged from the default template.\\nCaf\\u00e9 code lives under src/cafe.",
            "ignoreKeywords": "rename\\nlinter\\nprettier\\ngreptile-ignor",
            "ignorePatterns": "greptile.json\\ntesting/**/*.py\\n*.md\\n*.txt\\n*.json",
            "strictness": 3,
            "skipReview": "AUTOMATIC",
            "includeBranches": ["main"],
            "summarySection": {
                "included": true,
                "collapsible": false,
                "defaultOpen": true
            },
            "fileChangeLimit": 100
        }
        """;

    private static final String LOCKED = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "Settlement contract with the bank")
        public class PaymentLedger {}
        """;

    private static final String IGNORED = """
        package com.example;
        import se.deversity.vibetags.annotations.AIIgnore;
        @AIIgnore
        public class GeneratedMetadata {}
        """;

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static ProcessorTestHarness optedIn(Path dir, String greptileJson) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        Files.writeString(dir.resolve("greptile.json"), greptileJson, StandardCharsets.UTF_8);
        h.addSource("com.example.PaymentLedger", LOCKED);
        h.addSource("com.example.GeneratedMetadata", IGNORED);
        return h;
    }

    @Test
    void everyHandSetFieldSurvivesTheFirstCompile(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();

        Map<String, Object> before = parse(HAND_CONFIGURED);
        Map<String, Object> after = parse(h.readFile("greptile.json"));
        for (Map.Entry<String, Object> field : before.entrySet()) {
            if (field.getKey().equals("instructions") || field.getKey().equals("ignorePatterns")) {
                continue;
            }
            assertEquals(field.getValue(), after.get(field.getKey()),
                "hand-set field '" + field.getKey() + "' was changed or lost:\n" + h.readFile("greptile.json"));
        }
        assertEquals(before.keySet(), after.keySet(), "no key may be added or removed");
    }

    /** Stronger than equal values: outside the two owned values, not one byte moved. */
    @Test
    void everyByteOutsideTheTwoSharedValuesIsUnchanged(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();

        String written = h.readFile("greptile.json");
        assertEquals(withoutOwnedValues(HAND_CONFIGURED), withoutOwnedValues(written),
            "bytes outside instructions and ignorePatterns changed:\n" + written);
    }

    @Test
    void handWrittenInstructionsAreKeptAndTheGuardrailsFollowThem(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();

        String written = h.readFile("greptile.json");
        String instructions = (String) parse(written).get("instructions");
        assertTrue(instructions.startsWith(
                "Only comment if the PR description is unchanged from the default template.\nCafé code lives under src/cafe."),
            "the user's own instructions must be kept, first, unchanged:\n" + instructions);
        assertTrue(instructions.contains("PaymentLedger"),
            "the @AILocked class must reach the reviewer:\n" + instructions);
        assertTrue(instructions.contains("<!-- VIBETAGS-START -->") && instructions.contains("<!-- VIBETAGS-END -->"),
            "VibeTags' part must be delimited so the next build replaces only it:\n" + instructions);
        assertTrue(written.contains("Caf\\u00e9 code lives under src/cafe."),
            "the user's escapes must keep their spelling, not be re-encoded:\n" + written);
    }

    @Test
    void handWrittenIgnorePatternsAreKeptAndTheIgnoredClassIsAdded(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();

        String patterns = (String) parse(h.readFile("greptile.json")).get("ignorePatterns");
        List<String> lines = patterns.lines().toList();
        assertEquals(List.of("greptile.json", "testing/**/*.py", "*.md", "*.txt", "*.json"), lines.subList(0, 5),
            "the user's patterns must be kept, first, in order:\n" + patterns);
        assertTrue(lines.contains("**/GeneratedMetadata.java"),
            "the @AIIgnore class must be skipped by the reviewer:\n" + patterns);
        assertTrue(lines.contains("# VIBETAGS-START") && lines.contains("# VIBETAGS-END"),
            "the span must be delimited with .gitignore comments, which match no file:\n" + patterns);
    }

    @Test
    void aSecondCompileChangesNothing(@TempDir Path dir) throws IOException, InterruptedException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();
        String first = h.readFile("greptile.json");

        ProcessorTestHarness.awaitFilesystemTick(dir);
        Files.deleteIfExists(dir.resolve(".vibetags-cache"));
        h.compile();

        assertEquals(first, h.readFile("greptile.json"), "regeneration must be a no-op");
    }

    /** The span is replaced, never appended again: hand text appears once however many builds run. */
    @Test
    void aChangedAnnotationReplacesTheSpanWithoutRepeatingTheHandText(@TempDir Path dir)
            throws IOException, InterruptedException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();

        ProcessorTestHarness.awaitFilesystemTick(dir);
        h.clearSources();
        h.addSource("com.example.RefundLedger", LOCKED.replace("PaymentLedger", "RefundLedger"));
        h.addSource("com.example.GeneratedMetadata", IGNORED);
        h.compile();

        String instructions = (String) parse(h.readFile("greptile.json")).get("instructions");
        assertTrue(instructions.contains("RefundLedger"), "the new lock must arrive:\n" + instructions);
        assertFalse(instructions.contains("PaymentLedger"), "the old lock must leave:\n" + instructions);
        assertEquals(1, count(instructions, "Only comment if the PR description"),
            "hand text must appear exactly once:\n" + instructions);
        assertEquals(1, count(instructions, "<!-- VIBETAGS-START -->"), "exactly one span:\n" + instructions);
    }

    /** {@code touch greptile.json} is the opt-in, and an empty file is not JSON. */
    @Test
    void anEmptyOptInFileBecomesAValidDocument(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, "");
        h.compile();

        Map<String, Object> doc = parse(h.readFile("greptile.json"));
        assertEquals(java.util.Set.of("instructions", "ignorePatterns"), doc.keySet());
        assertTrue(((String) doc.get("instructions")).contains("PaymentLedger"));
    }

    /** A file VibeTags cannot read as JSON is the user's, broken or not, and is left exactly as it is. */
    @Test
    void aMalformedDocumentIsLeftUntouchedAndSaysSo(@TempDir Path dir) throws IOException {
        String broken = "{\n  \"strictness\": 2,\n  \"commentTypes\": [\"logic\"],\n}\n";
        ProcessorTestHarness h = optedIn(dir, broken);
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertEquals(broken, h.readFile("greptile.json"), "a malformed greptile.json must not be rewritten");
        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.WARNING
                && d.getMessage(null).contains("greptile.json")),
            "skipping the file must be visible in the build, was: " + diagnostics);
    }

    @Test
    void rulesMdCarriesMarkdownMarkersAndTheLockedClass(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn(".greptile/rules.md");
        h.addSource("com.example.PaymentLedger", LOCKED);
        h.compile();

        String content = h.readFile(".greptile/rules.md");
        assertTrue(content.contains("<!-- VIBETAGS-START -->") && content.contains("<!-- VIBETAGS-END -->"),
            ".md must get HTML-comment markers, was:\n" + content);
        assertTrue(content.contains("PaymentLedger"), "the @AILocked class must reach the reviewer:\n" + content);
        assertFalse(h.fileExists("greptile.json"), "opting into rules.md must not create greptile.json");
    }

    // ---------------------------------------------------------------------------------------

    /** Strict parse: JSON is YAML 1.2, and duplicate keys are rejected rather than last-wins. */
    static Map<String, Object> parse(String json) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed = new Yaml(options).load(json);
        assertTrue(parsed instanceof Map, "must be a JSON object, was: " + parsed + "\n" + json);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    /** The document with the two owned string literals replaced by a fixed token. */
    static String withoutOwnedValues(String json) {
        String literal = "\"(?:[^\"\\\\]|\\\\.)*\"";
        return json
            .replaceFirst("(\"instructions\"\\s*:\\s*)" + literal, "$1<owned>")
            .replaceFirst("(\"ignorePatterns\"\\s*:\\s*)" + literal, "$1<owned>");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
