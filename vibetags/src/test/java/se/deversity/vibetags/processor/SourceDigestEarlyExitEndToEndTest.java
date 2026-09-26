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

    /**
     * Warns on every anchor shape validation uses: a type through its annotation mirror, a method,
     * a field, a nested type, and a record's component field.
     */
    private static final String WARNS = """
        package com.example;

        import se.deversity.vibetags.annotations.AIContext;
        import se.deversity.vibetags.annotations.AIImmutable;
        import se.deversity.vibetags.annotations.AILocked;
        import se.deversity.vibetags.annotations.AIPure;

        @AILocked(reason = "ledger maths is audited")
        @AIContext
        public class Ledger {
            @AIPure
            public void tick() {
            }

            @AIImmutable
            public static final class Snapshot {
                private final int[] values = new int[0];
            }

            @AIContext
            static final class Inner {
            }

            @AIImmutable
            public record Frame(int[] data) {
            }
        }
        """;

    @Test
    void aBuildThatWarnedIsSkippedAndRepeatsEveryWarningWhereItWas() throws IOException {
        // Validation warnings come from the walk, so a skipped build has to repeat them from the
        // record the warned build left (#856): same count, same text, same file, line and column.
        // Before that, a build that warned was never skipped, and a project that keeps one warning
        // around walked every element on every rebuild.
        ProcessorTestHarness h = project(WARNS);
        List<String> first = vibeTagsWarnings(h.compileReturningDiagnostics());
        assertTrue(first.size() >= 5, "the fixture must warn on every anchor shape, or this proves little: " + first);
        assertTrue(first.stream().noneMatch(w -> w.contains(" -:-1:-1 ")),
            "every validation warning of the cold build is anchored to its element: " + first);

        List<Diagnostic<? extends JavaFileObject>> second = h.compileReturningDiagnostics();

        assertTrue(notes(second).contains(EARLY_EXIT), "the build that warned must now be skipped:\n" + notes(second));
        assertEquals(first, vibeTagsWarnings(second), "the rebuild must warn exactly as the first build did");
    }

    @Test
    void aWerrorBuildThatFailedOnAWarningFailsOnTheRebuildToo() throws IOException {
        ProcessorTestHarness h = project(WARNS);
        List<Diagnostic<? extends JavaFileObject>> first = h.compileReturningDiagnostics("-Werror");
        assertTrue(first.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            "-Werror must fail the build that warns, or this proves nothing: " + notes(first));

        List<Diagnostic<? extends JavaFileObject>> second = h.compileReturningDiagnostics("-Werror");

        assertTrue(second.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            "a rebuild must not start passing -Werror because its warnings were skipped: " + notes(second));
        assertEquals(vibeTagsWarnings(first), vibeTagsWarnings(second));
    }

    @Test
    void aBuildThatPrintedMoreThanTheReplayCapWalksAgain() throws IOException {
        // The record is repeated on every rebuild, so it is bounded; past the bound the build is not
        // recorded as skippable, and the rebuild walks and warns for itself.
        StringBuilder source = new StringBuilder(
            "package com.example;\n\nimport se.deversity.vibetags.annotations.AIPure;\n\npublic class Ledger {\n");
        for (int i = 0; i <= AIGuardrailProcessor.MAX_REPLAYED_DIAGNOSTICS; i++) {
            source.append("    @AIPure public void tick").append(i).append("() {}\n");
        }
        ProcessorTestHarness h = project(source.append("}\n").toString());
        List<String> first = vibeTagsWarnings(h.compileReturningDiagnostics("-Xmaxwarns", "5000"));
        assertTrue(first.size() > AIGuardrailProcessor.MAX_REPLAYED_DIAGNOSTICS,
            "the fixture must pass the cap, or this proves nothing: " + first.size());

        List<Diagnostic<? extends JavaFileObject>> second = h.compileReturningDiagnostics("-Xmaxwarns", "5000");

        assertFalse(notes(second).contains(EARLY_EXIT), "past the cap the rebuild must walk");
        assertEquals(first, vibeTagsWarnings(second));
    }

    @Test
    void theRecordIsReplacedByTheNextCleanBuild() throws IOException {
        // Fixing the warning is an edit, so the next build walks and records no warning; the build
        // after that is skipped and must not bring the old warning back.
        ProcessorTestHarness h = project(WARNS);
        h.compileReturningDiagnostics();
        Files.writeString(root.resolve("src/main/java/com/example/Ledger.java"), LEDGER, StandardCharsets.UTF_8);
        List<String> fixed = vibeTagsWarnings(h.compileReturningDiagnostics());

        List<Diagnostic<? extends JavaFileObject>> after = h.compileReturningDiagnostics();

        assertEquals(List.of(), fixed, "the fixed source warns about nothing");
        assertTrue(notes(after).contains(EARLY_EXIT), notes(after));
        assertEquals(List.of(), vibeTagsWarnings(after), "a replay of the warning that was fixed");
    }

    @Test
    void aRebuildRepeatsTheDeprecatedOutputWarning() throws IOException {
        // resolveActiveServices warns about a deprecated opt-in ahead of the fingerprint
        // short-circuit, so every no-op rebuild printed it until the early exit skipped
        // generateFiles() whole. A -Werror build then failed cold and passed on the rebuild.
        Files.writeString(root.resolve("gemini_instructions.md"), "", StandardCharsets.UTF_8);
        // Without it the orphan warning joins in, and that one is raised after the fingerprint
        // short-circuit, so no rebuild has ever repeated it: a separate question (#860).
        Files.writeString(root.resolve(".aiexclude"), "", StandardCharsets.UTF_8);
        ProcessorTestHarness h = project(LEDGER);
        List<String> first = vibeTagsWarnings(h.compileReturningDiagnostics());
        assertTrue(first.stream().anyMatch(w -> w.contains("deprecated")),
            "the fixture must warn about the deprecated output, or this proves nothing: " + first);

        List<Diagnostic<? extends JavaFileObject>> second = h.compileReturningDiagnostics();

        assertTrue(notes(second).contains(EARLY_EXIT), "this case is about the early exit:\n" + notes(second));
        assertEquals(first, vibeTagsWarnings(second), "the rebuild must warn exactly as the first build did");
    }

    @Test
    void aRebuildRepeatsTheUnidentifiedModuleWarning() throws IOException {
        // The same shape for warnIfModuleUnidentifiable: sources outside the VibeTags root beside
        // another module's sidecar. It too ran ahead of the fingerprint short-circuit.
        Path vibetagsRoot = Files.createDirectories(root.resolve("guardrails"));
        Files.writeString(vibetagsRoot.resolve("CLAUDE.md"), "", StandardCharsets.UTF_8);
        Files.createDirectories(vibetagsRoot.resolve("other"));
        se.deversity.vibetags.processor.internal.ModuleSidecar sibling =
            new se.deversity.vibetags.processor.internal.ModuleSidecar("other", "other");
        sibling.putBody("claude", "the other module's guardrails");
        sibling.save(vibetagsRoot);
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>outside</artifactId></project>",
            StandardCharsets.UTF_8);
        ProcessorTestHarness h = new ProcessorTestHarness(vibetagsRoot, false);
        Path source = root.resolve("src/main/java/com/example/Ledger.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, LEDGER, StandardCharsets.UTF_8);
        h.addSourceFile(source);
        List<String> first = vibeTagsWarnings(h.compileReturningDiagnostics());
        assertTrue(first.stream().anyMatch(w -> w.contains("could not identify the compiling module")),
            "the fixture must warn about the module, or this proves nothing: " + first);

        List<Diagnostic<? extends JavaFileObject>> second = h.compileReturningDiagnostics();

        assertTrue(notes(second).contains(EARLY_EXIT), "this case is about the early exit:\n" + notes(second));
        assertEquals(first, vibeTagsWarnings(second), "the rebuild must warn exactly as the first build did");
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

    /** Every VibeTags warning, in order, with the file and line javac anchored it to. */
    private static List<String> vibeTagsWarnings(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .filter(d -> d.getMessage(Locale.ROOT).contains("VibeTags") || d.getMessage(Locale.ROOT).contains("@AI"))
            .map(d -> d.getKind() + " " + (d.getSource() == null ? "-" : d.getSource().getName())
                + ":" + d.getLineNumber() + ":" + d.getColumnNumber() + " " + d.getMessage(Locale.ROOT))
            .toList();
    }
}
