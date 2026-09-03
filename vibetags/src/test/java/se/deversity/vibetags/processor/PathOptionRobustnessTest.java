package se.deversity.vibetags.processor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A path option the filesystem rejects must not fail the build.
 *
 * <p>USAGE.md promises that VibeTags never fails a compile over its own output, and process()
 * keeps that promise with a catch around generation. init() had no such catch, and every path
 * option went straight through Paths.get or Path.resolve there: an illegal character in
 * -Avibetags.manifest.dir or -Avibetags.log.path threw InvalidPathException out of init(), and
 * javac reported "annotation processor threw an uncaught exception" for the whole compilation.
 * A NUL byte is illegal on every platform, which is what makes this reproducible outside Windows.
 * The root option is covered by {@link PathOptionUnitTest}: overriding it in the harness would
 * point generation at the working directory.
 */
@Tag("e2e")
class PathOptionRobustnessTest {

    private static final String NUL = String.valueOf((char) 0);

    @AfterEach
    void releaseLog() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aManifestDirTheFilesystemRejectsIsIgnoredWithAWarning(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.A", lockedSource());
        List<Diagnostic<? extends JavaFileObject>> diags =
            h.compileReturningDiagnostics("-Avibetags.manifest.dir=bad" + NUL + "dir");

        assertTrue(errors(diags).isEmpty(), "the compile must not fail: " + errors(diags));
        assertTrue(warnings(diags).stream().anyMatch(m -> m.contains("vibetags.manifest.dir") && m.contains("not a valid path")),
            "the option is named in a warning: " + warnings(diags));
        assertTrue(h.readFile("CLAUDE.md").contains("com.example.A"), "generation went ahead without the option");
    }

    @Test
    void aLogPathTheFilesystemRejectsIsIgnoredWithAWarning(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.A", lockedSource());
        List<Diagnostic<? extends JavaFileObject>> diags =
            h.compileReturningDiagnostics("-Avibetags.log.path=bad" + NUL + "log");

        assertTrue(errors(diags).isEmpty(), "the compile must not fail: " + errors(diags));
        assertTrue(warnings(diags).stream().anyMatch(m -> m.contains("vibetags.log.path") && m.contains("not a valid path")),
            warnings(diags).toString());
        assertTrue(h.readFile("CLAUDE.md").contains("com.example.A"));
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
