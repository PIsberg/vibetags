package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.deversity.vibetags.processor.internal.GuardrailFileWriter;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other half of the file lifecycle: what happens when a generated file is still there but no
 * longer looks the way VibeTags left it.
 *
 * <p>{@code GuardrailLifecycleEndToEndTest} covers the file being <em>deleted</em> — the opt-out.
 * {@code WriteCacheProcessorIntegrationTest} covers an edit <em>outside</em> the markers being
 * preserved. Neither covers the cases a real repository produces constantly: a merge conflict
 * resolved by deleting the marker block, a half-applied patch that left a start marker with no end,
 * a file truncated by a bad script, an edit made <em>inside</em> the block by someone who did not
 * know it was generated, and a {@code .vibetags-cache} lost to {@code git clean}.
 *
 * <p>Each one is the same shape of failure as every multi-module defect so far: the build stays
 * green, the file still exists, and the guardrails are quietly wrong. The cache short-circuit makes
 * it worse — a run that decides nothing changed never looks at the damage, so these are tests of
 * {@code WriteCache.allCachedFilesStable()} as much as of the writer.
 */
@Tag("e2e")
class GuardrailFileRecoveryEndToEndTest {

    private static final String REASON = "Reconciliation is load-bearing";

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    /**
     * The merge-conflict case: a human resolves a conflict in {@code CLAUDE.md} by deleting the
     * whole marker block. The file still exists, so the platform is still opted in, and the block
     * has to come back — without taking the hand-written half of the file with it.
     */
    @Test
    void deletingTheMarkerBlock_reappendsIt_andKeepsTheHandWrittenContent(@TempDir Path dir) throws Exception {
        compileOnce(dir);

        Files.writeString(dir.resolve("CLAUDE.md"),
            "# My Project\n\nHand-written onboarding notes that predate VibeTags.\n",
            StandardCharsets.UTF_8);
        settle(dir);

        compileOnce(dir);

        String after = Files.readString(dir.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(after.contains("Hand-written onboarding notes that predate VibeTags."),
            "deleting the marker block must not cost the human half of the file:\n" + after);
        assertEquals(1, count(after, GuardrailFileWriter.MARKER_START_MD),
            "exactly one marker block must be re-appended, not zero and not two:\n" + after);
        assertTrue(after.contains(REASON),
            "the re-appended block must carry the current guardrails, not an empty shell:\n" + after);
    }

    /**
     * The half-applied-patch case: a start marker with no end. The writer documents this as
     * "preserve the content before the start marker" and warns — an unrepaired file would keep the
     * stale generated text forever, because nothing after a missing end marker can be replaced.
     */
    @Test
    void aStartMarkerWithNoEndMarker_isRepairedAndWarnedAbout(@TempDir Path dir) throws Exception {
        compileOnce(dir);

        Files.writeString(dir.resolve("CLAUDE.md"),
            "# My Project\n\nHand-written preamble.\n\n"
                + GuardrailFileWriter.MARKER_START_MD + "\n"
                + "Guardrails from three releases ago that nothing can replace.\n",
            StandardCharsets.UTF_8);
        settle(dir);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileReturningDiagnostics(dir);

        String after = Files.readString(dir.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(after.contains("Hand-written preamble."),
            "content before the orphaned start marker is hand-authored and must survive:\n" + after);
        assertFalse(after.contains("Guardrails from three releases ago"),
            "content after an orphaned start marker is generated output that lost its end marker; "
                + "leaving it in place is how a stale guardrail outlives the annotation:\n" + after);
        assertEquals(1, count(after, GuardrailFileWriter.MARKER_START_MD),
            "the repair must leave exactly one start marker:\n" + after);
        assertEquals(1, count(after, GuardrailFileWriter.MARKER_END_MD),
            "the repair must restore the missing end marker:\n" + after);
        assertTrue(after.contains(REASON), "the repaired block must carry the current guardrails");

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.getMessage(null).contains("malformed markers")),
            "a repair that silently rewrites a file the developer edited must say so: "
                + "no 'malformed markers' diagnostic was emitted");
    }

    /** A file truncated to nothing is still an opt-in signal, so the next compile must refill it. */
    @Test
    void truncatingAGeneratedFileToEmpty_repopulatesIt(@TempDir Path dir) throws Exception {
        compileOnce(dir);

        Files.writeString(dir.resolve("CLAUDE.md"), "", StandardCharsets.UTF_8);
        settle(dir);

        compileOnce(dir);

        String after = Files.readString(dir.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(after.contains(REASON),
            "an emptied file is an empty opt-in signal, exactly like a freshly touched one — "
                + "the next compile must repopulate it:\n" + after);
        assertEquals(1, count(after, GuardrailFileWriter.MARKER_START_MD),
            "and must do so with one marker block:\n" + after);
    }

    /**
     * An edit <em>inside</em> the markers is not hand-authored content — it is a developer editing
     * generated output, usually without realising it. The next compile has to take it back, or the
     * generated file and the annotations disagree with no way to tell which is right.
     *
     * <p>This is the case the fingerprint short-circuit is most likely to swallow: the annotations
     * did not change, so the only thing standing between the tampered file and a skipped run is
     * {@code allCachedFilesStable()}.
     */
    @Test
    void anEditInsideTheMarkers_isRevertedOnTheNextCompile(@TempDir Path dir) throws Exception {
        compileOnce(dir);

        Path claude = dir.resolve("CLAUDE.md");
        String generated = Files.readString(claude, StandardCharsets.UTF_8);
        assertTrue(generated.contains(REASON), "precondition: the reason is generated in the first place");
        Files.writeString(claude, generated.replace(REASON, "TAMPERED BY HAND"), StandardCharsets.UTF_8);
        settle(dir);

        compileOnce(dir);

        String after = Files.readString(claude, StandardCharsets.UTF_8);
        assertTrue(after.contains(REASON),
            "the annotation is the source of truth; an in-block edit must be overwritten:\n" + after);
        assertFalse(after.contains("TAMPERED BY HAND"),
            "an unchanged annotation set must not let the fingerprint short-circuit skip past a "
                + "modified output file:\n" + after);
    }

    /**
     * {@code .vibetags-cache} is a build artifact — {@code git clean}, a CI cold clone or a
     * {@code target/} wipe removes it routinely. Losing it must cost a rebuild, not a diff: if the
     * cache is what makes output stable, then every cold CI run rewrites files and every developer
     * with a warm cache disagrees with CI about what is committed.
     */
    @Test
    void deletingTheWriteCache_reproducesByteIdenticalOutput(@TempDir Path dir) throws Exception {
        compileOnce(dir);
        String before = Files.readString(dir.resolve("CLAUDE.md"), StandardCharsets.UTF_8);

        assertTrue(Files.deleteIfExists(dir.resolve(".vibetags-cache")),
            "precondition: the cache file must exist to be deleted");
        settle(dir);

        compileOnce(dir);

        assertEquals(before, Files.readString(dir.resolve("CLAUDE.md"), StandardCharsets.UTF_8),
            "output must be a function of the annotations, not of a surviving cache file");
        assertTrue(Files.exists(dir.resolve(".vibetags-cache")),
            "the cache must be rebuilt so the run after a cold one can short-circuit again");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void compileOnce(Path dir) throws IOException {
        harness(dir).compile();
        VibeTagsLogger.shutdown();
    }

    private static List<Diagnostic<? extends JavaFileObject>> compileReturningDiagnostics(Path dir)
            throws IOException {
        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            harness(dir).compileReturningDiagnostics();
        VibeTagsLogger.shutdown();
        return diagnostics;
    }

    /** {@code CLAUDE.md} as the sole opt-in, so the assertions are about one file and one writer. */
    private static ProcessorTestHarness harness(Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        h.addSource("com.example.Ledger",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"" + REASON + "\")\n"
                + "public class Ledger {}\n");
        return h;
    }

    private static void settle(Path dir) throws IOException, InterruptedException {
        ProcessorTestHarness.awaitFilesystemTick(dir);
        VibeTagsLogger.shutdown();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
