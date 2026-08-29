package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reason an author writes on an annotation has to survive all the way to the rule file an
 * agent actually opens (issue #506).
 *
 * <p>This is the reported reproduction run against a real javac: one class carrying a distinct
 * marker per annotation, compiled, and the generated {@code .claude/rules/} file searched for each
 * marker. The unit-level checks in {@code GranularReasonRenderedTest} could all pass while the file
 * on disk carried none of them, because two things sit between the renderer and the file: the
 * section collapser, which hoists the lines a section shares across its stanzas, and the write
 * cache, which skips the whole generate phase when the build fingerprint has not moved.
 */
@Tag("e2e")
class GranularReasonEndToEndTest {

    /** The rule file the Probe class generates. */
    private static final String PROBE_FILE = ".claude/rules/com-example-probe-Probe.md";

    /** The rule file the cache probe generates. */
    private static final String CACHED_FILE = ".claude/rules/com-example-probe-Cached.md";

    /** Every annotation on {@link #PROBE_SOURCE}, by marker suffix. */
    private static final List<String> PROBE = List.of(
        "STRICTTYPES", "STRICTEXCEPTIONS", "STRICTCLASSPATH", "SCHEMASAFE", "PROTOTYPE",
        "PARALLELTESTS", "PUBLICAPI", "INTERNATIONALIZED", "LEGACYBRIDGE", "SANDBOXONLY",
        "PURE", "IGNORE", "LOCKED", "IDEMPOTENT");

    /** Every annotation on {@link #CACHED_SOURCE}, by marker suffix. */
    private static final List<String> CACHED = List.of(
        "STRICTTYPES", "STRICTEXCEPTIONS", "STRICTCLASSPATH", "SCHEMASAFE",
        "PARALLELTESTS", "PUBLICAPI", "INTERNATIONALIZED", "LEGACYBRIDGE", "IGNORE");

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    @DisplayName("every reason written on the source reaches the generated rule file")
    void reasonsReachTheRuleFile(@TempDir Path tmp) throws IOException {
        ProcessorTestHarness harness = compile(tmp, "com.example.probe.Probe", PROBE_SOURCE, "FIRST");

        assertTrue(harness.fileExists(PROBE_FILE), PROBE_FILE + " must exist after compiling Probe");
        String rules = harness.readFile(PROBE_FILE);
        assertEquals(List.of(), missing(rules, "FIRST", PROBE),
            "these annotations accept a reason and it never reached the rule file, so what an "
                + "agent reads is boilerplate identical for every use of the annotation in every "
                + "project. The file that was written:" + System.lineSeparator() + rules);
    }

    /**
     * A reason-only edit has to regenerate the file, which it does only if the reason feeds the
     * build fingerprint.
     *
     * <p>{@link #CACHED_SOURCE} deliberately carries none of {@code @AILocked},
     * {@code @AIIdempotent}, {@code @AIPure}, {@code @AISandboxOnly} or {@code @AIPrototype}. Those
     * five already fed their reason into the fingerprint, so one of them anywhere on the class
     * moves the hash on its own and hides the defect in the other nine. Which nine those were is
     * pinned generically, off {@code GuardrailAnnotations.ALL}, by
     * {@code BuildFingerprintMutationTest.everyDeclaredReasonFeedsTheFingerprint}; this test is the
     * proof that the consequence on disk is a stale file rather than a theoretical hash gap.
     */
    @Test
    @DisplayName("editing only the reason regenerates the rule file")
    void changingOnlyTheReasonRegenerates(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness harness = compile(tmp, "com.example.probe.Cached", CACHED_SOURCE, "FIRST");
        assertTrue(harness.fileExists(CACHED_FILE), CACHED_FILE + " must exist after the first compile");
        assertEquals(List.of(), missing(harness.readFile(CACHED_FILE), "FIRST", CACHED),
            "precondition: the first compile must carry every marker");

        ProcessorTestHarness.awaitFilesystemTick(tmp);
        harness.clearSources();
        harness.addSource("com.example.probe.Cached", CACHED_SOURCE.replace("RUNID", "SECOND"));
        harness.compile();

        String rules = harness.readFile(CACHED_FILE);
        assertEquals(List.of(), missing(rules, "SECOND", CACHED),
            "the reason was the only thing that changed, so a fingerprint that does not carry it "
                + "matches, the generate phase is skipped, and the committed rule file keeps "
                + "quoting the previous sentence with nothing failing to say so. The file that "
                + "was written:" + System.lineSeparator() + rules);
        assertFalse(rules.contains("VIBETAGS_REASON_FIRST"),
            "the superseded reason is still in the file: it was appended rather than replaced");
    }

    /** The markers of {@code run} that {@code rules} does not carry. */
    private static List<String> missing(String rules, String run, List<String> expected) {
        List<String> absent = new ArrayList<>();
        for (String annotation : expected) {
            if (!rules.contains("VIBETAGS_REASON_" + run + "_" + annotation)) {
                absent.add(annotation);
            }
        }
        return absent;
    }

    private static ProcessorTestHarness compile(Path tmp, String className, String source, String run)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(tmp, false);
        harness.touchOptIn("CLAUDE.md");
        harness.touchOptIn(".claude/rules/.vibetags");
        harness.addSource(className, source.replace("RUNID", run));
        harness.compile();
        return harness;
    }

    /**
     * The probe class. Every reason carries a marker unique to the run, so a stale file and a
     * regenerated one are told apart by content rather than by mtime.
     */
    private static final String PROBE_SOURCE = """
        package com.example.probe;

        import se.deversity.vibetags.annotations.AIIdempotent;
        import se.deversity.vibetags.annotations.AIIgnore;
        import se.deversity.vibetags.annotations.AIInternationalized;
        import se.deversity.vibetags.annotations.AILegacyBridge;
        import se.deversity.vibetags.annotations.AILocked;
        import se.deversity.vibetags.annotations.AIParallelTests;
        import se.deversity.vibetags.annotations.AIPrototype;
        import se.deversity.vibetags.annotations.AIPublicAPI;
        import se.deversity.vibetags.annotations.AIPure;
        import se.deversity.vibetags.annotations.AISandboxOnly;
        import se.deversity.vibetags.annotations.AISchemaSafe;
        import se.deversity.vibetags.annotations.AIStrictClasspath;
        import se.deversity.vibetags.annotations.AIStrictExceptions;
        import se.deversity.vibetags.annotations.AIStrictTypes;

        @AIStrictTypes(reason = "VIBETAGS_REASON_RUNID_STRICTTYPES")
        @AIStrictExceptions(reason = "VIBETAGS_REASON_RUNID_STRICTEXCEPTIONS")
        @AIStrictClasspath(reason = "VIBETAGS_REASON_RUNID_STRICTCLASSPATH")
        @AISchemaSafe(reason = "VIBETAGS_REASON_RUNID_SCHEMASAFE")
        @AIPrototype(reason = "VIBETAGS_REASON_RUNID_PROTOTYPE")
        @AIParallelTests(reason = "VIBETAGS_REASON_RUNID_PARALLELTESTS")
        @AIPublicAPI(reason = "VIBETAGS_REASON_RUNID_PUBLICAPI")
        @AIInternationalized(reason = "VIBETAGS_REASON_RUNID_INTERNATIONALIZED")
        @AILegacyBridge(reason = "VIBETAGS_REASON_RUNID_LEGACYBRIDGE")
        @AISandboxOnly(reason = "VIBETAGS_REASON_RUNID_SANDBOXONLY")
        public final class Probe {

            @AILocked(reason = "VIBETAGS_REASON_RUNID_LOCKED")
            public static int one() { return 1; }

            @AIPure(reason = "VIBETAGS_REASON_RUNID_PURE")
            public static int two() { return 2; }

            @AIIdempotent(reason = "VIBETAGS_REASON_RUNID_IDEMPOTENT")
            public static int three() { return 3; }

            @AIIgnore(reason = "VIBETAGS_REASON_RUNID_IGNORE")
            public static int four() { return 4; }
        }
        """;

    /** The same probe reduced to the annotations whose reason the fingerprint used to ignore. */
    private static final String CACHED_SOURCE = """
        package com.example.probe;

        import se.deversity.vibetags.annotations.AIIgnore;
        import se.deversity.vibetags.annotations.AIInternationalized;
        import se.deversity.vibetags.annotations.AILegacyBridge;
        import se.deversity.vibetags.annotations.AIParallelTests;
        import se.deversity.vibetags.annotations.AIPublicAPI;
        import se.deversity.vibetags.annotations.AISchemaSafe;
        import se.deversity.vibetags.annotations.AIStrictClasspath;
        import se.deversity.vibetags.annotations.AIStrictExceptions;
        import se.deversity.vibetags.annotations.AIStrictTypes;

        @AIStrictTypes(reason = "VIBETAGS_REASON_RUNID_STRICTTYPES")
        @AIStrictExceptions(reason = "VIBETAGS_REASON_RUNID_STRICTEXCEPTIONS")
        @AIStrictClasspath(reason = "VIBETAGS_REASON_RUNID_STRICTCLASSPATH")
        @AISchemaSafe(reason = "VIBETAGS_REASON_RUNID_SCHEMASAFE")
        @AIParallelTests(reason = "VIBETAGS_REASON_RUNID_PARALLELTESTS")
        @AIPublicAPI(reason = "VIBETAGS_REASON_RUNID_PUBLICAPI")
        @AIInternationalized(reason = "VIBETAGS_REASON_RUNID_INTERNATIONALIZED")
        @AILegacyBridge(reason = "VIBETAGS_REASON_RUNID_LEGACYBRIDGE")
        public final class Cached {

            @AIIgnore(reason = "VIBETAGS_REASON_RUNID_IGNORE")
            public static int four() { return 4; }
        }
        """;
}
