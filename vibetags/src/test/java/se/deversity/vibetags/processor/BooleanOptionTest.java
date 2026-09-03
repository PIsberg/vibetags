package se.deversity.vibetags.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three boolean options read the same way.
 *
 * <p>Before this, -Avibetags.check and -Avibetags.baseline.update recognised only the literal
 * true and -Avibetags.cache only the literal false; anything else, including a bare
 * -Avibetags.check with no value, silently kept the default. A CI gate written as
 * -Avibetags.check=yes therefore generated instead of checking and was green forever.
 */
@Tag("e2e")
class BooleanOptionTest {

    private static final String CHECK_FAILED = "VibeTags: check failed";

    @AfterEach
    void releaseLog() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aValueThatIsNotTrueOrFalseWarnsAndKeepsTheDefault(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.A", lockedSource());
        List<Diagnostic<? extends JavaFileObject>> diags = h.compileReturningDiagnostics("-Avibetags.check=yes");

        assertTrue(warnings(diags).stream().anyMatch(m -> m.contains("vibetags.check") && m.contains("yes")),
            "the unrecognised value is named: " + warnings(diags));
        assertTrue(errors(diags).isEmpty(), "check mode did not switch on: " + errors(diags));
        assertTrue(h.readFile("CLAUDE.md").contains("com.example.A"), "the default (generate) applied");
    }

    @Test
    void aBareKeyMeansTrue(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.A", lockedSource());
        List<Diagnostic<? extends JavaFileObject>> diags = h.compileReturningDiagnostics("-Avibetags.check");

        assertTrue(errors(diags).stream().anyMatch(m -> m.contains(CHECK_FAILED)),
            "a bare -Avibetags.check switches check mode on: " + errors(diags));
        assertEquals("", h.readFile("CLAUDE.md"), "check mode writes nothing");
    }

    @Test
    void trueAndFalseAreCaseInsensitiveForEveryBooleanOption(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.A", lockedSource());
        List<Diagnostic<? extends JavaFileObject>> diags = h.compileReturningDiagnostics("-Avibetags.cache=FALSE");
        assertTrue(warnings(diags).stream().noneMatch(m -> m.contains("vibetags.cache")), warnings(diags).toString());
        assertFalse(Files.exists(tmp.resolve(".vibetags-cache")), "FALSE disables the cache");

        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp.resolve("second"));
        h2.addSource("com.example.A", lockedSource());
        List<Diagnostic<? extends JavaFileObject>> diags2 = h2.compileReturningDiagnostics("-Avibetags.cache=No");
        assertTrue(warnings(diags2).stream().anyMatch(m -> m.contains("vibetags.cache") && m.contains("No")),
            warnings(diags2).toString());
        assertTrue(Files.exists(tmp.resolve("second").resolve(".vibetags-cache")),
            "an unrecognised value keeps the default, which is a cache");
    }

    private static String lockedSource() {
        return "package com.example;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"pinned\")\n"
            + "public class A {}\n";
    }

    private static List<String> errors(List<Diagnostic<? extends JavaFileObject>> diags) {
        return diags.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null)).toList();
    }

    private static List<String> warnings(List<Diagnostic<? extends JavaFileObject>> diags) {
        return diags.stream().filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null)).toList();
    }
}
