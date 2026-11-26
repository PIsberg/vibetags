package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One annotation attribute holding an unpaired UTF-16 surrogate must not cost the module its
 * guardrails (#1141).
 *
 * <p>A string literal whose unicode escape names a lone high surrogate (U+D800) is legal Java: a
 * mistyped escape, or text pasted out of a broken source. The writer encoded strictly, so the
 * whole aggregate failed with {@code MalformedInputException} ("Input length = 1") before a byte
 * was written: every other element's rule went missing with it, the warning named neither the
 * element nor the character, and the build then reported "no changes". The character is now
 * written as {@code ?}, the replacement {@code String.getBytes} already gives it in the module
 * sidecar, and a validation warning names the element and attribute so the author can fix the
 * source.
 */
@Tag("e2e")
class UnpairedSurrogateEndToEndTest {

    private static final String ODD = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "frozen \\uD800 here")
        public class Odd {}
        """;

    private static final String NORMAL = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "normal reason text")
        public class Normal {}
        """;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void oneBadAttributeNoLongerDropsTheModulesAggregates() throws IOException {
        ProcessorTestHarness h = optedIn();
        h.addSource("com.example.Odd", ODD);
        h.addSource("com.example.Normal", NORMAL);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        for (String aggregate : List.of("CLAUDE.md", ".cursorrules")) {
            String content = strictUtf8(tempDir.resolve(aggregate));
            assertTrue(content.contains("com.example.Normal"),
                aggregate + " lost the module's other guardrails. Was:\n" + content);
            assertTrue(content.contains("frozen ? here"),
                aggregate + " must carry the bad element's rule with the character replaced. Was:\n" + content);
        }
        assertFalse(messages(diagnostics).stream().anyMatch(m -> m.contains("Failed to write")),
            "no aggregate may fail to write: " + messages(diagnostics));
    }

    @Test
    void granularRuleFileIsWrittenToo() throws IOException {
        ProcessorTestHarness h = optedIn();
        h.touchOptIn(".cursor/rules/.vibetags");
        h.addSource("com.example.Odd", ODD);

        h.compile();

        try (Stream<Path> files = Files.list(tempDir.resolve(".cursor/rules"))) {
            List<Path> rules = files.filter(p -> p.getFileName().toString().endsWith(".mdc")).toList();
            assertFalse(rules.isEmpty(), "the element's granular rule file was not written");
            boolean carried = false;
            for (Path rule : rules) {
                carried |= strictUtf8(rule).contains("frozen ? here");
            }
            assertTrue(carried, "no granular rule file carries the replaced reason: " + rules);
        }
    }

    @Test
    void warningNamesTheElementAndTheAttribute() throws IOException {
        ProcessorTestHarness h = optedIn();
        h.addSource("com.example.Odd", ODD);
        h.addSource("com.example.Normal", NORMAL);

        List<String> warnings = h.compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .map(d -> d.getMessage(Locale.ROOT))
            .filter(m -> m.contains("surrogate"))
            .toList();

        assertEquals(1, warnings.size(), "one warning, for the one bad attribute: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.contains("com.example.Odd"), "the warning names the element: " + warning);
        assertTrue(warning.contains("@AILocked"), "the warning names the annotation: " + warning);
        assertTrue(warning.contains("'reason'"), "the warning names the attribute: " + warning);
    }

    @Test
    void aPairedSurrogateIsNeitherReplacedNorWarnedAbout() throws IOException {
        ProcessorTestHarness h = optedIn();
        h.addSource("com.example.Emoji", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "rocket \\uD83D\\uDE80 launch")
            public class Emoji {}
            """);

        List<String> messages = messages(h.compileReturningDiagnostics());

        String content = strictUtf8(tempDir.resolve("CLAUDE.md"));
        assertTrue(content.contains("rocket " + Character.toString(0x1F680) + " launch"),
            "a well-formed supplementary character is written as it is. Was:\n" + content);
        assertFalse(messages.stream().anyMatch(m -> m.contains("surrogate")), messages.toString());
    }

    private ProcessorTestHarness optedIn() throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".cursorrules");
        return h;
    }

    private static List<String> messages(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream().map(d -> d.getMessage(Locale.ROOT)).toList();
    }

    /** The file's text, failing the test when its bytes are not valid UTF-8. */
    private static String strictUtf8(Path file) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException e) {
            throw new AssertionError(file + " is not valid UTF-8", e);
        }
    }
}
