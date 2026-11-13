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

    private static final String ORPHAN = "@AILocked used but .aiexclude (hard guardrail) is missing";

    @Test
    void aRebuildThatTakesTheEarlyExitRepeatsTheOrphanWarning() throws IOException {
        // The orphan check runs after the fingerprint short-circuit in generateFiles(), and the
        // early exit never reaches generateFiles() at all, so a -Werror build failed cold and
        // passed every rebuild after it (#860). Compared as sets of lines: the replay prints the
        // recorded warnings in the first round, ahead of anything the cold build printed before
        // them. The count and the text are what -Werror and a reader see.
        Files.writeString(root.resolve("GEMINI.md"), "", StandardCharsets.UTF_8);
        ProcessorTestHarness h = project(LEDGER);
        List<String> first = vibeTagsWarnings(h.compileReturningDiagnostics());
        assertTrue(first.stream().anyMatch(w -> w.contains(ORPHAN)),
            "the fixture must raise the orphan warning, or this proves nothing: " + first);

        List<Diagnostic<? extends JavaFileObject>> second = h.compileReturningDiagnostics();

        assertTrue(notes(second).contains(EARLY_EXIT), "this case is about the early exit:\n" + notes(second));
        assertEquals(sorted(first), sorted(vibeTagsWarnings(second)),
            "the rebuild must warn exactly as the first build did");
    }

    /**
     * The console is not the only record: invariant 15 makes {@code vibetags.log} one too. The
     * fingerprint short-circuit shuts the log down before it returns, and the orphan check that
     * runs after it wrote to the detached logger, so the rebuild's warning reached the console and
     * never the log.
     */
    @Test
    void aRebuildStoppedByTheFingerprintLogsTheOrphanWarning() throws IOException {
        Files.writeString(root.resolve("GEMINI.md"), "", StandardCharsets.UTF_8);
        ProcessorTestHarness h = project(LEDGER);
        h.compileReturningDiagnostics();
        VibeTagsLogger.shutdown();
        long before = logLinesContaining(ORPHAN);
        assertTrue(before > 0, "the fixture must log the orphan warning, or this proves nothing");
        Files.writeString(root.resolve("src/main/java/com/example/Ledger.java"), LEDGER + "// touched\n",
            StandardCharsets.UTF_8);

        String second = notes(h.compileReturningDiagnostics());
        VibeTagsLogger.shutdown();

        assertTrue(second.contains("(fingerprint "), "this case is about the fingerprint short-circuit:\n" + second);
        assertTrue(logLinesContaining(ORPHAN) > before,
            "the fingerprint-stopped build printed the orphan warning but did not log it");
    }

    private long logLinesContaining(String text) throws IOException {
        Path log = root.resolve("vibetags.log");
        if (!Files.exists(log)) {
            return 0;
        }
        try (java.util.stream.Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
            return lines.filter(l -> l.contains(text)).count();
        }
    }

    @Test
    void aRebuildStoppedByTheFingerprintRepeatsTheOrphanWarning() throws IOException {
        // A comment changes the source digest but no annotation, so the rebuild walks and then
        // stops at the fingerprint short-circuit inside generateFiles(), ahead of the orphan check.
        Files.writeString(root.resolve("GEMINI.md"), "", StandardCharsets.UTF_8);
        ProcessorTestHarness h = project(LEDGER);
        List<String> first = vibeTagsWarnings(h.compileReturningDiagnostics());
        assertTrue(first.stream().anyMatch(w -> w.contains(ORPHAN)),
            "the fixture must raise the orphan warning, or this proves nothing: " + first);
        Files.writeString(root.resolve("src/main/java/com/example/Ledger.java"), LEDGER + "// touched\n",
            StandardCharsets.UTF_8);

        List<Diagnostic<? extends JavaFileObject>> second = h.compileReturningDiagnostics();
        List<Diagnostic<? extends JavaFileObject>> third = h.compileReturningDiagnostics();

        assertTrue(notes(second).contains("(fingerprint "),
            "this case is about the fingerprint short-circuit:\n" + notes(second));
        assertEquals(sorted(first), sorted(vibeTagsWarnings(second)),
            "the build stopped by the fingerprint must warn as the first build did");
        assertTrue(notes(third).contains(EARLY_EXIT), "the build after it takes the early exit:\n" + notes(third));
        assertEquals(sorted(first), sorted(vibeTagsWarnings(third)),
            "and repeats what the fingerprint-stopped build recorded");
    }

    @Test
    void creatingTheMissingFileSilencesTheOrphanWarningForGood() throws IOException {
        // The replayed record must not outlive its cause: creating .aiexclude is an opt-in change,
        // so the next build walks, and neither it nor the skipped build after it may warn.
        Files.writeString(root.resolve("GEMINI.md"), "", StandardCharsets.UTF_8);
        ProcessorTestHarness h = project(LEDGER);
        assertTrue(vibeTagsWarnings(h.compileReturningDiagnostics()).stream().anyMatch(w -> w.contains(ORPHAN)),
            "the fixture must raise the orphan warning, or this proves nothing");
        Files.writeString(root.resolve(".aiexclude"), "", StandardCharsets.UTF_8);

        List<String> fixed = vibeTagsWarnings(h.compileReturningDiagnostics());
        List<Diagnostic<? extends JavaFileObject>> after = h.compileReturningDiagnostics();

        assertTrue(fixed.stream().noneMatch(w -> w.contains(ORPHAN)), "the file exists now: " + fixed);
        assertTrue(notes(after).contains(EARLY_EXIT), notes(after));
        assertTrue(vibeTagsWarnings(after).stream().noneMatch(w -> w.contains(ORPHAN)),
            "a replay of the warning whose cause is gone: " + vibeTagsWarnings(after));
    }

    private static List<String> sorted(List<String> lines) {
        return lines.stream().sorted().toList();
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

    /**
     * Beside CLAUDE.md, AGENTS.md is active only once it carries a marker pair (invariant 4), and
     * the first build's NOTE tells the user to paste one. That is a change to the file's content,
     * not its presence, so an early exit that hashes only presence skipped the very build the NOTE
     * asked for and left the pasted pair empty until some source changed.
     */
    @Test
    void pastingAMarkerPairIntoAgentsMdBesideClaudeMdIsWritten() throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        Files.writeString(root.resolve("AGENTS.md"), "# Pointer\n\nRead CLAUDE.md.\n", StandardCharsets.UTF_8);
        h.compileReturningDiagnostics();
        assertFalse(read("AGENTS.md").contains("ledger maths is audited"),
            "precondition: an unmarked AGENTS.md beside CLAUDE.md is left alone");
        Files.writeString(root.resolve("AGENTS.md"),
            "# Pointer\n\nRead CLAUDE.md.\n\n<!-- VIBETAGS-START -->\n<!-- VIBETAGS-END -->\n",
            StandardCharsets.UTF_8);

        String second = notes(h.compileReturningDiagnostics());

        assertFalse(second.contains(EARLY_EXIT), second);
        assertTrue(read("AGENTS.md").contains("ledger maths is audited"), read("AGENTS.md"));
    }

    /**
     * A write that fails warns once and returns; the build is still recorded as clean. A file
     * the writer never managed to write had no cache entry, and an absent entry counts as stable,
     * so once the cause was fixed (here: a CLAUDE.md saved in Latin-1, re-saved as UTF-8; equally
     * a file locked by an editor on Windows, or a full disk) every later build with unchanged
     * sources took the exit and the file never received its guardrails.
     */
    @Test
    void aFileThatFailedToWriteIsRetriedByTheNextBuild() throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        Files.writeString(root.resolve("CLAUDE.md"), "# Notes\n\nCafé rules.\n", StandardCharsets.ISO_8859_1);
        String first = notes(h.compileReturningDiagnostics());
        assertTrue(first.contains("Failed to write AI rules file"),
            "precondition: a file that is not valid UTF-8 cannot be merged into, so it is not written\n" + first);
        Files.writeString(root.resolve("CLAUDE.md"), "# Notes\n\nCafé rules.\n", StandardCharsets.UTF_8);

        String second = notes(h.compileReturningDiagnostics());

        assertFalse(second.contains(EARLY_EXIT), first + "\n---\n" + second);
        assertTrue(read("CLAUDE.md").contains("ledger maths is audited"), read("CLAUDE.md"));
        assertTrue(read("CLAUDE.md").contains("Café rules."), "hand-authored text must survive");
    }

    /**
     * The same shape reached without an I/O failure: the writer declines a file it cannot merge
     * into without guessing, warns, and returns. Nothing was recorded for the file, an absent entry
     * counts as stable, and the opt-in is still "a file", so once the developer did what the warning
     * asked, every later build with unchanged sources took the exit and the file never received
     * its guardrails. Three files, one for each way of declining.
     */
    @Test
    void aFileLeftUntouchedOverAStrayStartMarkerIsWrittenOnceTheMarkerIsRemoved() throws IOException {
        assertDeclinedFileIsWrittenOnceRepaired("CLAUDE.md",
            "# Notes\n\nPaste this line to opt in:\n\n<!-- VIBETAGS-START -->\n\n## Build\n\nmvn verify\n",
            "# Notes\n\n## Build\n\nmvn verify\n");
    }

    @Test
    void aGreptileConfigLeftUntouchedAsMalformedIsWrittenOnceItParses() throws IOException {
        assertDeclinedFileIsWrittenOnceRepaired("greptile.json",
            "{ \"labels\": [\"review\"], \n", "{ \"labels\": [\"review\"] }\n");
    }

    @Test
    void aPrAgentConfigLeftUntouchedAsUnmergeableIsWrittenOnceItIsMergeable() throws IOException {
        assertDeclinedFileIsWrittenOnceRepaired(".pr_agent.toml",
            "[[pr_reviewer]]\nnum_code_suggestions = 3\n", "[pr_reviewer]\nnum_code_suggestions = 3\n");
    }

    private void assertDeclinedFileIsWrittenOnceRepaired(String file, String declined, String repaired)
            throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        Files.writeString(root.resolve(file), declined, StandardCharsets.UTF_8);
        String first = notes(h.compileReturningDiagnostics());
        assertTrue(first.contains("untouched"),
            "precondition: the writer declines " + file + " and says so\n" + first);
        assertEquals(declined, read(file), "precondition: a declined file is left exactly as it was");
        Files.writeString(root.resolve(file), repaired, StandardCharsets.UTF_8);

        String second = notes(h.compileReturningDiagnostics());

        assertFalse(second.contains(EARLY_EXIT), first + "\n---\n" + second);
        assertTrue(read(file).contains("ledger maths is audited"), read(file));
    }

    /**
     * And while it is not repaired, the warning is the only thing telling the developer that a
     * file they opted in carries no guardrails. A rebuild that skips it turns a {@code -Werror}
     * build that failed cold into one that passes (#859's shape).
     */
    @Test
    void aFileStillLeftUntouchedIsWarnedAboutOnEveryRebuild() throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        Files.writeString(root.resolve("greptile.json"), "{ \"labels\": [\"review\"], \n", StandardCharsets.UTF_8);
        List<String> first = vibeTagsWarnings(h.compileReturningDiagnostics());
        assertTrue(first.stream().anyMatch(w -> w.contains("untouched")), "precondition: " + first);

        List<String> second = vibeTagsWarnings(h.compileReturningDiagnostics());

        assertEquals(first, second, "the rebuild must warn exactly as the first build did");
    }

    /**
     * Deleting an opt-in file is how a platform is opted out. The build after that has to walk,
     * since the opt-in set changed, but the one after it has nothing left to regenerate: a record
     * of a file nobody asks for any more must not keep every later build from being skipped.
     */
    @Test
    void aPlatformOptedOutDoesNotKeepLaterRebuildsFromBeingSkipped() throws IOException {
        ProcessorTestHarness h = project(LEDGER);
        Files.writeString(root.resolve("llms.txt"), "", StandardCharsets.UTF_8);
        h.compileReturningDiagnostics();
        assertTrue(read("llms.txt").contains("Ledger"), "precondition: llms.txt was written\n" + read("llms.txt"));
        Files.delete(root.resolve("llms.txt"));

        String second = notes(h.compileReturningDiagnostics());
        String third = notes(h.compileReturningDiagnostics());

        assertFalse(second.contains(EARLY_EXIT), "the opt-in set changed, so this build walks:\n" + second);
        assertFalse(Files.exists(root.resolve("llms.txt")), "an opted-out file is never recreated");
        assertTrue(third.contains(UNCHANGED), "nothing changed since the second build:\n" + third);
    }

    private static final String LEDGER_NAMING_A_CONSTANT = """
        package com.example;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = com.lib.Reasons.LEDGER)
        public class Ledger {
        }
        """;

    /**
     * An annotation value can come from outside the round: a constant in a dependency. Changing it
     * changes what the files must say and no byte of any source the round was given, so the digest
     * matched, the build was skipped, and CLAUDE.md kept the old reason for good.
     */
    @Test
    void aValueTakenFromADependencyConstantFollowsTheDependency() throws IOException {
        ProcessorTestHarness h = project(LEDGER_NAMING_A_CONSTANT);
        String[] classpath = {"-classpath", System.getProperty("java.class.path")
            + java.io.File.pathSeparator + compileReasons("ledger maths is audited")};
        h.compileReturningDiagnostics(classpath);
        assertTrue(read("CLAUDE.md").contains("ledger maths is audited"), "precondition:\n" + read("CLAUDE.md"));
        compileReasons("ledger maths moved to the settlement service");

        String second = notes(h.compileReturningDiagnostics(classpath));

        assertFalse(second.contains(EARLY_EXIT), second);
        assertTrue(read("CLAUDE.md").contains("ledger maths moved to the settlement service"), read("CLAUDE.md"));
    }

    @Test
    void aValueTakenFromASourcePathConstantFollowsThatSource() throws IOException {
        // The same constant read off -sourcepath: javac compiles Reasons.java implicitly, so it is a
        // source of the build without being a source of the round, and the digest never reads it.
        ProcessorTestHarness h = project(LEDGER_NAMING_A_CONSTANT);
        writeReasons("ledger maths is audited");
        String[] sourcePath = {"-sourcepath", root.resolve("lib-src").toString()};
        h.compileReturningDiagnostics(sourcePath);
        assertTrue(read("CLAUDE.md").contains("ledger maths is audited"), "precondition:\n" + read("CLAUDE.md"));
        writeReasons("ledger maths moved to the settlement service");

        String second = notes(h.compileReturningDiagnostics(sourcePath));

        assertFalse(second.contains(EARLY_EXIT), second);
        assertTrue(read("CLAUDE.md").contains("ledger maths moved to the settlement service"), read("CLAUDE.md"));
    }

    @Test
    void aConstantWhoseInitializerNamesAnotherIsNotVouchedFor() throws IOException {
        // Reasons is a source of the round, so its bytes are in the digest, but its value is not
        // fixed by them: it names a constant from the dependency.
        ProcessorTestHarness h = project("""
            package com.example;

            import se.deversity.vibetags.annotations.AILocked;

            @AILocked(reason = Ledger.REASON)
            public class Ledger {
                static final String REASON = "audited: " + com.lib.Reasons.LEDGER;
            }
            """);
        String[] classpath = {"-classpath", System.getProperty("java.class.path")
            + java.io.File.pathSeparator + compileReasons("first")};
        h.compileReturningDiagnostics(classpath);
        compileReasons("second");

        h.compileReturningDiagnostics(classpath);

        assertTrue(read("CLAUDE.md").contains("audited: second"), read("CLAUDE.md"));
    }

    @Test
    void valuesTheRoundsOwnSourcesDetermineStillTakeTheExit() throws IOException {
        // The guard above must not cost the exit where nothing can change behind the digest: an
        // enum constant, a class literal, a concatenation of literals, and constants declared with
        // literal initializers in this file and in another file of the same round.
        ProcessorTestHarness h = project("""
            package com.example;

            import se.deversity.vibetags.annotations.AIContext;
            import se.deversity.vibetags.annotations.AILocked;
            import se.deversity.vibetags.annotations.AITestDriven;

            @AILocked(reason = Ledger.REASON)
            @AIContext(focus = Topics.SETTLEMENT, avoids = "floating point" + " " + "money")
            @AITestDriven(coverageGoal = 60 + 30, framework = AITestDriven.Framework.JUNIT_5)
            public class Ledger {
                static final String REASON = "ledger maths " + "is audited";
            }
            """);
        h.writeSourceFile("src/main/java/com/example/Topics.java", """
            package com.example;

            final class Topics {
                static final String SETTLEMENT = "settlement routing";
            }
            """);
        h.compileReturningDiagnostics();
        assertTrue(read("CLAUDE.md").contains("ledger maths is audited"), "precondition:\n" + read("CLAUDE.md"));

        String second = notes(h.compileReturningDiagnostics());

        assertTrue(second.contains(EARLY_EXIT), second);
    }

    /**
     * An attribute left at its default takes its value from the annotations jar on the compile
     * class path, which the digest did not hash (#1061). A build whose annotations artifact moved
     * to a release with another default, the processor unchanged, was skipped and kept the old
     * text. Here the move is a shadow {@code AILocked} ahead of the real one on the class path,
     * identical but for the default.
     */
    @Test
    void aDefaultThatChangesWithTheAnnotationsJarIsNotSkippedPast() throws IOException {
        ProcessorTestHarness h = project("""
            package com.example;

            import se.deversity.vibetags.annotations.AILocked;

            @AILocked
            public class Ledger {
            }
            """);
        h.compileReturningDiagnostics();
        assertTrue(read("CLAUDE.md").contains("Do not modify this code under any circumstances."),
            "precondition: the default reason is rendered:\n" + read("CLAUDE.md"));

        Path shadow = root.resolve("shadow-src/se/deversity/vibetags/annotations/AILocked.java");
        Files.createDirectories(shadow.getParent());
        Files.writeString(shadow, """
            package se.deversity.vibetags.annotations;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.SOURCE)
            @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
            public @interface AILocked {
                String reason() default "A default from the next annotations release.";
            }
            """, StandardCharsets.UTF_8);
        Path shadowClasses = Files.createDirectories(root.resolve("shadow-classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", shadowClasses.toString(), shadow.toString()), "the shadow must compile");

        String second = notes(h.compileReturningDiagnostics("-classpath",
            shadowClasses + java.io.File.pathSeparator + System.getProperty("java.class.path")));

        assertFalse(second.contains(EARLY_EXIT), "the annotations on the class path changed:\n" + second);
        assertTrue(read("CLAUDE.md").contains("A default from the next annotations release."), read("CLAUDE.md"));
    }

    /** Writes {@code com.lib.Reasons} with {@code LEDGER = value} under {@code lib-src}. */
    private Path writeReasons(String value) throws IOException {
        Path source = root.resolve("lib-src/com/lib/Reasons.java");
        Files.createDirectories(source.getParent());
        return Files.writeString(source, "package com.lib;\n\npublic final class Reasons {\n"
            + "    public static final String LEDGER = \"" + value + "\";\n}\n", StandardCharsets.UTF_8);
    }

    /** Compiles {@link #writeReasons} into {@code lib-classes}, the dependency, and returns that directory. */
    private Path compileReasons(String value) throws IOException {
        Path source = writeReasons(value);
        Path out = Files.createDirectories(root.resolve("lib-classes"));
        int exit = javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", out.toString(), source.toString());
        assertEquals(0, exit, "the dependency must compile");
        return out;
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
