package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-file decisions both writers act on (#766): which rendered files are written, and whether
 * each counts as carrying new rules. generateFiles() and checkFiles() used to compute these side by
 * side, kept in step by copying; a check verdict is only worth anything if it reproduces generation,
 * so now both read one plan.
 */
class WritePlanTest {

    private static final Path ROOT = Path.of("root");

    @Test
    void anIgnoreFileWithoutTheMergeFollowsTheRoundsOwnAnnotations() {
        // Ignore files used to count as new rules from any round, so an unannotated test-compile of
        // a single-module project, which never sees the main sources, emptied the main round's
        // exclusions (SourceSetIsolationEndToEndTest.anUnannotatedTestRoundKeepsTheMainSourcesIgnoreGlobs).
        WritePlan empty = WritePlan.of(content("cursor_ignore", "claude"), files("cursor_ignore", "claude"),
            List.of(), false, false, Set.of());
        assertFalse(write(empty, "cursor_ignore").hasNewRules(), "an empty round adds no rules to an ignore file");
        assertFalse(write(empty, "claude").hasNewRules(), "an empty round adds no rules to CLAUDE.md");

        WritePlan annotated = WritePlan.of(content("cursor_ignore"), files("cursor_ignore"),
            List.of(), false, true, Set.of());
        assertTrue(write(annotated, "cursor_ignore").hasNewRules(), "a round with annotations rewrites it");

        WritePlan emptied = WritePlan.of(content("cursor_ignore"), files("cursor_ignore"),
            List.of(), false, false, Set.of("cursor_ignore"));
        assertTrue(write(emptied, "cursor_ignore").hasNewRules(),
            "and so does a source set that has just withdrawn its last exclusion (#781)");
    }

    @Test
    void anIgnoreFileFromTheMergeIsRewrittenThoughNoModuleContributed() {
        // The first attempt at sharing this predicate wrote contributed AND not-ignore, and exclusion
        // lists stopped being rewritten; the e2e TestingMdSafety case caught it on four CI jobs. In a
        // reactor the last exclusion leaving is exactly "nobody contributed", and the merge is what
        // writes that (GuardrailLifecycleEndToEndTest.removingTheLastIgnoreInAReactor_...).
        WritePlan plan = WritePlan.of(content("cursor_ignore", "claude"), files("cursor_ignore", "claude"),
            List.of(), true, false, Set.of());

        assertTrue(write(plan, "cursor_ignore").hasNewRules(), "a merged ignore file is always rewritten");
        assertFalse(write(plan, "claude").hasNewRules(), "a file nobody contributed to is not");
    }

    @Test
    void aSingleModuleRoundFollowsItsOwnAnnotations() {
        WritePlan plan = WritePlan.of(content("claude"), files("claude"), List.of(), false, true, Set.of());

        assertTrue(write(plan, "claude").hasNewRules());
    }

    @Test
    void aMultiModuleRoundAsksEverySidecarNotItsOwnRound() {
        ModuleSidecar sibling = new ModuleSidecar("a", "a", "a");
        sibling.putBody("claude", "body");

        WritePlan plan = WritePlan.of(content("claude", "cursor"), files("claude", "cursor"),
            List.of(sibling), true, true, Set.of());

        assertTrue(write(plan, "claude").hasNewRules(), "a sibling contributed to CLAUDE.md");
        assertFalse(write(plan, "cursor").hasNewRules(),
            "nobody contributed to .cursorrules, whatever this round saw");
    }

    @Test
    void aServiceThisSourceSetJustWithdrewFromIsRewritten() {
        // #781: without this the removed rule stays in the file for good.
        WritePlan plan = WritePlan.of(content("claude"), files("claude"), List.of(), false, false, Set.of("claude"));

        assertTrue(write(plan, "claude").hasNewRules());
    }

    @Test
    void anUnmappedServiceIsNamedAndNotWritten() {
        Map<String, String> content = content("claude", "no_such_service");

        WritePlan plan = WritePlan.of(content, files("claude"), List.of(), false, true, Set.of());

        assertEquals(List.of("claude"), plan.writes().stream().map(WritePlan.Write::service).toList());
        assertEquals(List.of("no_such_service"), plan.unmapped());
    }

    @Test
    void aWriteCarriesTheContentAndPathItWasPlannedWith() {
        WritePlan.Write w = write(WritePlan.of(content("claude"), files("claude"), List.of(), false, true, Set.of()),
            "claude");

        assertEquals("rendered claude", w.content());
        assertEquals(ROOT.resolve("claude.out"), w.path());
    }

    private static WritePlan.Write write(WritePlan plan, String service) {
        return plan.writes().stream().filter(w -> w.service().equals(service)).findFirst()
            .orElseThrow(() -> new AssertionError(service + " was not planned: " + plan.writes()));
    }

    private static Map<String, String> content(String... services) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String s : services) {
            m.put(s, "rendered " + s);
        }
        return m;
    }

    private static Map<String, Path> files(String... services) {
        Map<String, Path> m = new LinkedHashMap<>();
        for (String s : services) {
            m.put(s, ROOT.resolve(s + ".out"));
        }
        return m;
    }
}
