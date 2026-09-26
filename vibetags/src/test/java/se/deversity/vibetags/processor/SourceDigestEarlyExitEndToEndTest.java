package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rebuild of unchanged sources can decide before the collection walk (#834).
 *
 * <p>The fingerprint short-circuit in generateFiles() fires after the walk, which on a no-op
 * rebuild is nearly all of the round's allocation. The early exit hashes every source the round
 * was given and compares that, and every other input, with what the last clean run recorded.
 * These cases are the ways that could answer "unchanged" wrongly, each of which would leave
 * guardrail files stale in silence, plus the one case where it must answer "unchanged".
 */
@Tag("e2e")
class SourceDigestEarlyExitEndToEndTest {

    private static final String EARLY_EXIT = "(source digest ";
    private static final String UNCHANGED = "inputs unchanged since last run";

    private static final String LEDGER = """
        package com.example;

        import se.deversity.vibetags.annotations.AIContext;
        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "ledger maths is audited")
        @AIContext(focus = "settlement routing")
        public class Ledger {
        }
        """;

    private static final String CLOCK = """
        package com.example;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "time source for every audit record")
        public class Clock {
        }
        """;

    @TempDir
    Path root;

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aRebuildOfUnchangedSourcesDecidesBeforeTheWalkAndWritesNothingNew() throws IOException {
        ProcessorTestHarness h = project(LEDGER);

        String first = notes(h.compileReturningDiagnostics());
        String claude = read("CLAUDE.md");
        String second = notes(h.compileReturningDiagnostics());

        assertFalse(first.contains(UNCHANGED), "the first build has nothing to compare with:\n" + first);
        assertTrue(second.contains(UNCHANGED) && second.contains(EARLY_EXIT),
            "the rebuild must take the early exit, not only the later fingerprint one:\n" + second);
        assertEquals(claude, read("CLAUDE.md"));
        assertTrue(claude.contains("ledger maths is audited"), claude);
    }

    @Test
    void aSameLengthEditWithTheOldTimestampIsStillCollected() throws IOException {
        // The failure a size-and-mtime proxy has: a tool that preserves timestamps, or an edit
        // in the same second, reads as unchanged. The digest is of the content.
        ProcessorTestHarness h = project(LEDGER);
        h.compileReturningDiagnostics();
        Path source = root.resolve("src/main/java/com/example/Ledger.java");
        FileTime stamp = Files.getLastModifiedTime(source);
        Files.writeString(source, LEDGER.replace("ledger maths is audited", "ledger maths is AUDITED"),
            StandardCharsets.UTF_8);
        Files.setLastModifiedTime(source, stamp);

        String second = notes(h.compileReturningDiagnostics());

        assertFalse(second.contains(EARLY_EXIT), second);
        assertTrue(read("CLAUDE.md").contains("ledger maths is AUDITED"), read("CLAUDE.md"));
    }

    @Test
    void aBuildThatWarnedIsNeverSkippedSoItsWarningComesBack() throws IOException {
        // Validation warnings come from the walk. A build that skipped it would drop them, and a
        // clean rebuild would then look warning-free for a source that still has the problem.
        String bare = """
            package com.example;

            import se.deversity.vibetags.annotations.AIContext;
            import se.deversity.vibetags.annotations.AILocked;

            @AILocked(reason = "ledger maths is audited")
            @AIContext
            public class Ledger {
            }
            """;
        ProcessorTestHarness h = project(bare);
        List<Diagnostic<? extends JavaFileObject>> first = h.compileReturningDiagnostics();
        long warnings = warnings(first);
        assertTrue(warnings > 0, "the fixture must warn, or this case proves nothing: " + first);

        List<Diagnostic<? extends JavaFileObject>> second = h.compileReturningDiagnostics();

        assertFalse(notes(second).contains(EARLY_EXIT), notes(second));
        assertEquals(warnings, warnings(second), "the rebuild must warn exactly as the first build did");
    }

    @Test
    void aPlatformOptedInAfterTheLastBuildIsWritten() throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        h.compileReturningDiagnostics();
        Files.createFile(root.resolve(".cursorrules"));

        String second = notes(h.compileReturningDiagnostics());

        assertFalse(second.contains(EARLY_EXIT), second);
        assertTrue(read(".cursorrules").contains("ledger maths is audited"), read(".cursorrules"));
    }

    @Test
    void aHandEditedGeneratedBlockIsRegenerated() throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        h.compileReturningDiagnostics();
        String good = read("CLAUDE.md");
        Files.writeString(root.resolve("CLAUDE.md"), good.replace("ledger maths is audited", "anything goes"),
            StandardCharsets.UTF_8);

        h.compileReturningDiagnostics();

        assertEquals(good, read("CLAUDE.md"), "a stale output file must be rewritten, not skipped");
    }

    @Test
    void checkModeStillFailsOnDriftAfterACleanBuild() throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        h.compileReturningDiagnostics();
        h.compileReturningDiagnostics();
        String good = read("CLAUDE.md");
        Files.writeString(root.resolve("CLAUDE.md"), good.replace("ledger maths is audited", "anything goes"),
            StandardCharsets.UTF_8);

        List<Diagnostic<? extends JavaFileObject>> check = h.compileReturningDiagnostics("-Avibetags.check=true");

        assertTrue(check.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            "check mode must still compare, and fail on the drift: " + check);
    }

    @Test
    void aRoundShownOnlySomeSourcesIsNotAnswerable() throws IOException {
        // Invariant 17: a round that was not shown every annotated source writes nothing and says
        // so. A subset of unchanged files must not read as "everything unchanged".
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>m</artifactId></project>",
            StandardCharsets.UTF_8);
        ProcessorTestHarness h = project(LEDGER);
        h.writeSourceFile("src/main/java/com/example/Clock.java", CLOCK);
        h.compileReturningDiagnostics();
        h.compileReturningDiagnostics();

        ProcessorTestHarness partial = new ProcessorTestHarness(root, false);
        partial.addSourceFile(root.resolve("src/main/java/com/example/Ledger.java"));
        String notes = notes(partial.compileReturningDiagnostics());

        assertFalse(notes.contains(EARLY_EXIT), "a subset of the sources is not the recorded set:\n" + notes);
        assertTrue(read("CLAUDE.md").contains("time source for every audit record"),
            "the unseen source's guardrail must survive:\n" + read("CLAUDE.md"));
    }

    @Test
    void theEarlyExitIsLoggedAsItsOwnSkipReason() throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        h.compileReturningDiagnostics("-Avibetags.log.level=DEBUG");
        h.compileReturningDiagnostics("-Avibetags.log.level=DEBUG");
        VibeTagsLogger.shutdown();

        String log = read("vibetags.log");

        assertTrue(log.contains("round.skip reason=sources-unchanged"), log);
    }

    private ProcessorTestHarness project(String ledger) throws IOException {
        Files.writeString(root.resolve("CLAUDE.md"), "", StandardCharsets.UTF_8);
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.writeSourceFile("src/main/java/com/example/Ledger.java", ledger);
        return h;
    }

    private String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static String notes(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            sb.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
        }
        return sb.toString();
    }

    private static long warnings(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .filter(d -> d.getMessage(Locale.ROOT).contains("VibeTags") || d.getMessage(Locale.ROOT).contains("@AI"))
            .count();
    }
}
