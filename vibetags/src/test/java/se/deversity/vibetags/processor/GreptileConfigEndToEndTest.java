package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.deversity.vibetags.processor.GreptileEndToEndTest.parse;

/**
 * End-to-end tests for Greptile's {@code .greptile/config.json} (#651).
 *
 * <p>In Greptile's recommended {@code .greptile/} form, file exclusions live in {@code config.json}
 * under {@code ignorePatterns}, a newline-separated {@code .gitignore}-syntax string, next to review
 * settings the user sets by hand. Before this, a project on that form got {@code @AIIgnore} elements
 * only as prose in {@code rules.md}: the reviewer was told to treat them as non-existent and still
 * reviewed them. The assertions are on what Greptile reads: the document parses, every other byte
 * is the user's, and the ignored class is a pattern inside {@code ignorePatterns}.
 */
@Tag("e2e")
class GreptileConfigEndToEndTest {

    private static final String CONFIG = ".greptile/config.json";

    /** Shaped like Greptile's own {@code config.json} reference example, with structured rules. */
    static final String HAND_CONFIGURED = """
        {
          "strictness": 2,
          "commentTypes": ["logic", "syntax"],
          "instructions": "Review for correctness first.",
          "ignorePatterns": "dist/**\\nnode_modules/**",
          "rules": [
            {"id": "no-stdout", "rule": "Do not log with System.out", "scope": ["src/**/*.java"], "severity": "high"}
          ],
          "disabledRules": []
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

    private static ProcessorTestHarness optedIn(Path dir, String configJson) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        Files.createDirectories(dir.resolve(".greptile"));
        Files.writeString(dir.resolve(CONFIG), configJson, StandardCharsets.UTF_8);
        h.addSource("com.example.PaymentLedger", LOCKED);
        h.addSource("com.example.GeneratedMetadata", IGNORED);
        return h;
    }

    @Test
    void theIgnoredClassBecomesAPatternAfterTheUsersOwn(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();

        String patterns = (String) parse(h.readFile(CONFIG)).get("ignorePatterns");
        List<String> lines = patterns.lines().toList();
        assertEquals(List.of("dist/**", "node_modules/**"), lines.subList(0, 2),
            "the user's patterns must be kept, first, in order:\n" + patterns);
        assertTrue(lines.contains("**/GeneratedMetadata.java"),
            "the @AIIgnore class must be a path the reviewer skips:\n" + patterns);
        assertTrue(lines.contains("# VIBETAGS-START") && lines.contains("# VIBETAGS-END"),
            "the span must be delimited with .gitignore comments, which match no file:\n" + patterns);
    }

    /** Outside ignorePatterns, not one byte moves, and instructions is not VibeTags' key here. */
    @Test
    void everyByteOutsideIgnorePatternsIsUnchanged(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();

        String written = h.readFile(CONFIG);
        assertEquals(GreptileEndToEndTest.withoutOwnedValues(HAND_CONFIGURED),
            GreptileEndToEndTest.withoutOwnedValues(written),
            "bytes outside the owned values changed:\n" + written);
        assertTrue(written.contains("\"instructions\": \"Review for correctness first.\","),
            "config.json's instructions belong to the user; the guardrails go to rules.md:\n" + written);
        assertFalse(written.contains("PaymentLedger"),
            "a locked class is a rule, not an exclusion, and has no place in config.json:\n" + written);
    }

    @Test
    void aSecondCompileChangesNothing(@TempDir Path dir) throws IOException, InterruptedException {
        ProcessorTestHarness h = optedIn(dir, HAND_CONFIGURED);
        h.compile();
        String first = h.readFile(CONFIG);

        ProcessorTestHarness.awaitFilesystemTick(dir);
        Files.deleteIfExists(dir.resolve(".vibetags-cache"));
        h.compile();

        assertEquals(first, h.readFile(CONFIG), "regeneration must be a no-op");
    }

    /** {@code touch .greptile/config.json} is the opt-in, and an empty file is not JSON. */
    @Test
    void anEmptyOptInFileBecomesADocumentHoldingOnlyIgnorePatterns(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, "");
        h.compile();

        Map<String, Object> doc = parse(h.readFile(CONFIG));
        assertEquals(Set.of("ignorePatterns"), doc.keySet(), "VibeTags owns one key in config.json");
        assertTrue(((String) doc.get("ignorePatterns")).contains("**/GeneratedMetadata.java"));
    }

    /** config.json alone is a valid opt-in: exclusions without rules, and no other file created. */
    @Test
    void optingIntoConfigJsonAloneCreatesNoOtherGreptileFile(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, "{}");
        h.compile();

        assertTrue(h.readFile(CONFIG).contains("**/GeneratedMetadata.java"), "the opt-in must be honoured");
        assertFalse(h.fileExists(".greptile/rules.md"), "opting into config.json must not create rules.md");
        assertFalse(h.fileExists("greptile.json"),
            "and must never create greptile.json, which Greptile ignores beside .greptile/ anyway");
    }

    @Test
    void aMalformedDocumentIsLeftUntouchedAndSaysSo(@TempDir Path dir) throws IOException {
        String broken = "{\n  \"strictness\": 2,\n  // a comment is not JSON\n}\n";
        ProcessorTestHarness h = optedIn(dir, broken);
        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertEquals(broken, h.readFile(CONFIG), "a malformed config.json must not be rewritten");
        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.WARNING
                && d.getMessage(null).contains("config.json")),
            "skipping the file must be visible in the build, was: " + diagnostics);
    }

    /**
     * {@code config.json} is far too common a name to merge by name alone. A {@code config.json}
     * outside {@code .greptile/}, here the {@code .cody/config.json} 1.x used to write, is not
     * Greptile's and must be left exactly as it is.
     */
    @Test
    void anotherConfigJsonIsNotMistakenForGreptiles(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = optedIn(dir, "{}");
        String other = "{\"customCommands\": []}\n";
        Files.createDirectories(dir.resolve(".cody"));
        Files.writeString(dir.resolve(".cody/config.json"), other, StandardCharsets.UTF_8);
        h.compile();

        assertEquals(other, h.readFile(".cody/config.json"), "another config.json must not grow a span or be rewritten");
        assertTrue(h.readFile(CONFIG).contains("# VIBETAGS-START"), "while Greptile's gets its span");
    }
}
