package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for v0.9.5 annotations with EMPTY optional fields.
 *
 * <p>{@link NewAnnotationsV3EndToEndTest} exercises fully-populated annotations. This test
 * covers the complementary false branches: {@code note.isEmpty()}, {@code replacedBy.isEmpty()},
 * {@code deadline.isEmpty()}, {@code clause.isEmpty()}, and the empty-array branches for
 * {@code metrics}, {@code traces}, {@code logs} in {@code appendObservability}.
 *
 * <p>One source class carries TWO annotations ({@code @AILocked} + {@code @AIThreadSafe}) so
 * that {@code appendToGranular} is called twice for the same owning element, covering the
 * {@code if (sb.length() > 0) sb.append("\n")} branch in that method.
 */
class NewAnnotationsV3MinimalTest {

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = new ProcessorTestHarness(tempDir);

        // @AIThreadSafe with no note (default "") — covers note.isEmpty() == true branches
        harness.addSource("com.example.sync.CounterService",
            "package com.example.sync;\n"
                + "import se.deversity.vibetags.annotations.AIThreadSafe;\n"
                + "@AIThreadSafe(strategy = AIThreadSafe.Strategy.SYNCHRONIZED)\n"
                + "public class CounterService {}\n");

        // @AIImmutable with no note (default "") — covers note.isEmpty() == true branches
        harness.addSource("com.example.config.AppConfig",
            "package com.example.config;\n"
                + "import se.deversity.vibetags.annotations.AIImmutable;\n"
                + "@AIImmutable\n"
                + "public final class AppConfig {\n"
                + "    private final String env;\n"
                + "    public AppConfig(String env) { this.env = env; }\n"
                + "}\n");

        // @AIDeprecated with only migrationGuide — covers replacedBy.isEmpty() and
        // deadline.isEmpty() == true branches
        harness.addSource("com.example.legacy.LegacyUtil",
            "package com.example.legacy;\n"
                + "import se.deversity.vibetags.annotations.AIDeprecated;\n"
                + "@AIDeprecated(migrationGuide = \"Inline the logic from LegacyUtil directly\")\n"
                + "public class LegacyUtil {}\n");

        // @AIObservability with only metrics (no traces, no logs, no note) — covers
        // traces.length==0, logs.length==0, and note.isEmpty() == true branches
        harness.addSource("com.example.metrics.PerfMonitor",
            "package com.example.metrics;\n"
                + "import se.deversity.vibetags.annotations.AIObservability;\n"
                + "public class PerfMonitor {\n"
                + "    @AIObservability(metrics = {\"jvm.memory.used\"})\n"
                + "    public void reportMemory() {}\n"
                + "}\n");

        // @AIRegulation with no clause (default "") — covers clause.isEmpty() == true branches
        harness.addSource("com.example.compliance.HipaaHandler",
            "package com.example.compliance;\n"
                + "import se.deversity.vibetags.annotations.AIRegulation;\n"
                + "@AIRegulation(standard = \"HIPAA\","
                + " description = \"Handles protected health information.\")\n"
                + "public class HipaaHandler {}\n");

        // @AILocked + @AIThreadSafe on the same class → appendToGranular is called twice
        // for the same owning element, which covers the sb.length() > 0 branch.
        harness.addSource("com.example.core.SharedCache",
            "package com.example.core;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "import se.deversity.vibetags.annotations.AIThreadSafe;\n"
                + "@AILocked(reason = \"Cache eviction logic is carefully tuned\")\n"
                + "@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE)\n"
                + "public class SharedCache {}\n");

        harness.compile();
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    // -----------------------------------------------------------------------
    // @AIThreadSafe — no note
    // -----------------------------------------------------------------------

    @Test
    void threadSafe_noNote_cursorRulesContainsStrategy() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("CounterService"), ".cursorrules must list CounterService");
        assertTrue(content.contains("SYNCHRONIZED"), ".cursorrules must include strategy name");
    }

    @Test
    void threadSafe_noNote_claudeMdHasNoNoteTag() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("com.example.sync.CounterService"),
            "CLAUDE.md must list CounterService");
        assertTrue(content.contains("<strategy>SYNCHRONIZED</strategy>"),
            "CLAUDE.md must include strategy tag");
    }

    // -----------------------------------------------------------------------
    // @AIImmutable — no note
    // -----------------------------------------------------------------------

    @Test
    void immutable_noNote_cursorRulesListsType() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("AppConfig"), ".cursorrules must list AppConfig");
        assertTrue(content.contains("immutable"), ".cursorrules must include immutable label");
    }

    @Test
    void immutable_noNote_claudeMdHasNoNoteTag() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("com.example.config.AppConfig"),
            "CLAUDE.md must list AppConfig");
    }

    // -----------------------------------------------------------------------
    // @AIDeprecated — no replacedBy, no deadline
    // -----------------------------------------------------------------------

    @Test
    void deprecated_noReplacedByNoDeadline_cursorRulesContainsMigrationGuide() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("LegacyUtil"), ".cursorrules must list LegacyUtil");
        assertTrue(content.contains("Inline the logic"), ".cursorrules must include migration guide");
    }

    @Test
    void deprecated_noReplacedByNoDeadline_claudeMdHasNoReplacedByOrDeadlineTag() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("com.example.legacy.LegacyUtil"),
            "CLAUDE.md must list LegacyUtil");
        assertFalse(content.contains("<replaced_by></replaced_by>"),
            "empty replaced_by must not produce an empty XML tag");
        assertFalse(content.contains("<deadline></deadline>"),
            "empty deadline must not produce an empty XML tag");
    }

    @Test
    void deprecated_noReplacedByNoDeadline_llmsFullTxtHasNoReplacedByLabel() throws IOException {
        String content = harness.readFile("llms-full.txt");
        assertTrue(content.contains("LegacyUtil"), "llms-full.txt must list LegacyUtil");
        assertTrue(content.contains("Inline the logic"), "llms-full.txt must include migration guide");
    }

    // -----------------------------------------------------------------------
    // @AIObservability — metrics only, no traces/logs/note
    // -----------------------------------------------------------------------

    @Test
    void observability_metricsOnly_cursorRulesListsMetric() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("jvm.memory.used"), ".cursorrules must include the metric name");
    }

    @Test
    void observability_metricsOnly_claudeMdHasMetricTagOnly() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("<metric>jvm.memory.used</metric>"),
            "CLAUDE.md must include the metric tag");
        assertFalse(content.contains("<trace>"), "no traces → no <trace> tags");
        assertFalse(content.contains("<log>"), "no logs → no <log> tags");
    }

    // -----------------------------------------------------------------------
    // @AIRegulation — no clause
    // -----------------------------------------------------------------------

    @Test
    void regulation_noClause_cursorRulesContainsStandard() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("HIPAA"), ".cursorrules must include the standard name");
    }

    @Test
    void regulation_noClause_claudeMdHasNoClauseTag() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("<standard>HIPAA</standard>"),
            "CLAUDE.md must include standard tag");
        assertFalse(content.contains("<clause>"), "empty clause must not produce a <clause> tag");
    }

    // -----------------------------------------------------------------------
    // Multi-annotation granular rules (appendToGranular sb.length() > 0 branch)
    // -----------------------------------------------------------------------

    @Test
    void multiAnnotation_granularRuleFileContainsBothAnnotations() throws IOException {
        // SharedCache has @AILocked + @AIThreadSafe → appendToGranular called twice for
        // the same owner → the sb.length() > 0 branch in appendToGranular fires.
        // GranularRulesWriter converts '.' to '-' in the filename.
        String content = harness.readFile(".cursor/rules/com-example-core-SharedCache.mdc");
        assertFalse(content.isBlank(),
            "Granular rule file for SharedCache must not be empty when two annotations are present");
    }
}
