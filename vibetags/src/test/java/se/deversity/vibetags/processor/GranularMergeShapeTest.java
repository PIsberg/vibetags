package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.content.GranularContribution;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The exact contribution {@link ModuleSidecar#mergeGranular} and
 * {@link ModuleSidecar#mergeModuleGranular} hand the granular writer, compared whole where
 * {@code GranularContributionMergeTest} checks one property per case.
 *
 * <p>Written before the merge moved out of {@code ModuleSidecar}, to pin what the move must not
 * change: the region order inside a case-fold group (the order stems were first met, then regions
 * per stem, which is not the order the sidecars were read in), the de-duplication of one region's
 * identical contribution recorded under two spellings, the joined heading and description, and
 * the key order of the returned map.
 */
class GranularMergeShapeTest {

    private static ModuleSidecar sidecar(String moduleId, String regionId) {
        return new ModuleSidecar(moduleId, "", regionId);
    }

    private static GranularContribution plain(String body, String... globs) {
        return new GranularContribution(List.of(globs), body);
    }

    private static GranularContribution named(String body, String displayName, String subject,
                                              String... globs) {
        return new GranularContribution(List.of(globs), body, displayName,
            GranularContribution.DESCRIPTION_PREFIX + subject);
    }

    @Test
    void aCaseFoldGroupListsRegionsInTheOrderItsStemsWereFirstMet() {
        ModuleSidecar a = sidecar("a", "a");
        a.putGranularContribution("foo", plain("A", "**/a/**"));
        ModuleSidecar b = sidecar("b", "b");
        b.putGranularContribution("Foo", plain("B", "**/b/**"));
        ModuleSidecar c = sidecar("c", "c");
        c.putGranularContribution("foo", plain("C", "**/c/**"));

        Map<String, GranularContribution> merged = ModuleSidecar.mergeGranular(List.of(a, b, c));

        assertEquals(List.of("foo", "Foo"), List.copyOf(merged.keySet()));
        assertSame(merged.get("foo"), merged.get("Foo"),
            "both spellings name one physical file on a case-insensitive filesystem");
        GranularContribution shared = merged.get("foo");
        assertEquals("""
            <!-- VIBETAGS-MODULE: a -->
            A
            <!-- VIBETAGS-MODULE-END: a -->
            <!-- VIBETAGS-MODULE: c -->
            C
            <!-- VIBETAGS-MODULE-END: c -->
            <!-- VIBETAGS-MODULE: b -->
            B
            <!-- VIBETAGS-MODULE-END: b -->""", shared.body());
        assertEquals(List.of("**/a/**", "**/c/**", "**/b/**"), shared.globs());
        assertEquals("", shared.displayName());
        assertEquals("", shared.description());
    }

    @Test
    void oneRegionsIdenticalContributionUnderTwoSpellingsIsKeptOnce() {
        ModuleSidecar a = sidecar("a", "a");
        a.putGranularContribution("Foo", plain("A", "**/a/**"));
        a.putGranularContribution("foo", plain("A", "**/a/**"));
        ModuleSidecar b = sidecar("b", "b");
        b.putGranularContribution("foo", plain("A", "**/a/**"));

        GranularContribution shared = ModuleSidecar.mergeGranular(List.of(a, b)).get("foo");

        assertEquals("""
            <!-- VIBETAGS-MODULE: a -->
            A
            <!-- VIBETAGS-MODULE-END: a -->
            <!-- VIBETAGS-MODULE: b -->
            A
            <!-- VIBETAGS-MODULE-END: b -->""", shared.body(),
            "identical contributions are kept once per region, never across regions");
        assertEquals(List.of("**/a/**"), shared.globs());
    }

    @Test
    void namesAndSubjectsAreJoinedOnceEachAndUnnamedContributionsAddNone() {
        ModuleSidecar a = sidecar("a", "a");
        a.putGranularContribution("role", named("A", "Instrumentation", "the benchmark package", "**/a/**"));
        ModuleSidecar aTest = sidecar("a__test", "a");
        aTest.putGranularContribution("role", plain("A-test", "**/a/**", "**/t/**"));
        ModuleSidecar b = sidecar("b", "b");
        b.putGranularContribution("role", named("B", "Instrumentation", "the agent package", "**/b/**"));
        ModuleSidecar c = sidecar("c", "c");
        c.putGranularContribution("role", named("C", "Probes", "the agent package"));

        GranularContribution shared = ModuleSidecar.mergeGranular(List.of(a, aTest, b, c)).get("role");

        assertEquals("Instrumentation, Probes", shared.displayName());
        assertEquals("AI rules for the benchmark package, the agent package", shared.description());
        assertEquals(List.of("**/a/**", "**/t/**", "**/b/**"), shared.globs());
        assertEquals("""
            <!-- VIBETAGS-MODULE: a -->
            A

            A-test
            <!-- VIBETAGS-MODULE-END: a -->
            <!-- VIBETAGS-MODULE: b -->
            B
            <!-- VIBETAGS-MODULE-END: b -->
            <!-- VIBETAGS-MODULE: c -->
            C
            <!-- VIBETAGS-MODULE-END: c -->""", shared.body());
    }

    @Test
    void eachSourceSetsBodyIsStrippedBeforeTheyAreJoined() {
        ModuleSidecar a = sidecar("a", "a");
        a.putGranularContribution("kept", plain("\n  body-a  \n\n", "**/a/**"));
        ModuleSidecar aTest = sidecar("a__test", "a");
        aTest.putGranularContribution("kept", plain(" body-test\n", "**/t/**"));

        Map<String, GranularContribution> merged = ModuleSidecar.mergeGranular(List.of(a, aTest));

        assertEquals("body-a\n\nbody-test", merged.get("kept").body());
        assertEquals(List.of("**/a/**", "**/t/**"), merged.get("kept").globs());
    }

    @Test
    void theReturnedMapListsFoldGroupsInFirstMetOrderWithEachGroupsSpellingsTogether() {
        ModuleSidecar a = sidecar("a", "a");
        a.putGranularContribution("Zeta", plain("Z"));
        a.putGranularContribution("alpha", plain("A"));
        ModuleSidecar b = sidecar("b", "b");
        b.putGranularContribution("ALPHA", plain("A2"));
        b.putGranularContribution("zeta", plain("Z2"));
        b.putGranularContribution("mid", plain("M"));

        Map<String, GranularContribution> merged = ModuleSidecar.mergeGranular(List.of(a, b));

        assertEquals(List.of("Zeta", "zeta", "alpha", "ALPHA", "mid"), List.copyOf(merged.keySet()),
            "fold groups in the order their first spelling was met, each group's spellings together");
        assertSame(merged.get("alpha"), merged.get("ALPHA"));
        assertSame(merged.get("Zeta"), merged.get("zeta"));
        assertEquals("""
            <!-- VIBETAGS-MODULE: a -->
            A
            <!-- VIBETAGS-MODULE-END: a -->
            <!-- VIBETAGS-MODULE: b -->
            A2
            <!-- VIBETAGS-MODULE-END: b -->""", merged.get("ALPHA").body());
    }

    @Test
    void aModulesOwnMergeJoinsItsSourceSetsWithoutSubMarkersAndIgnoresOtherRegions() {
        ModuleSidecar main = sidecar("core", "core");
        main.putModuleGranularContribution("Role", named("main", "Role", "core", "**/m/**"));
        ModuleSidecar test = sidecar("core__test", "core");
        test.putModuleGranularContribution("role", plain("test", "**/t/**"));
        ModuleSidecar other = sidecar("cli", "cli");
        other.putModuleGranularContribution("role", plain("other", "**/o/**"));
        other.putModuleGranularContribution("cliOnly", plain("other"));

        Map<String, GranularContribution> merged =
            ModuleSidecar.mergeModuleGranular(List.of(main, test, other), "core");

        assertEquals(List.of("Role", "role"), List.copyOf(merged.keySet()));
        GranularContribution shared = merged.get("role");
        assertEquals("main\n\ntest", shared.body());
        assertEquals(List.of("**/m/**", "**/t/**"), shared.globs());
        assertEquals("Role", shared.displayName());
        assertEquals("AI rules for core", shared.description());
    }

    @Test
    void theReactorMergeReadsUnitsAndTheModuleMergeReadsModuleUnits() {
        ModuleSidecar a = sidecar("a", "a");
        a.putGranularContribution("role", plain("reactor"));
        a.putModuleGranularContribution("role", plain("module"));

        assertEquals("reactor", ModuleSidecar.mergeGranular(List.of(a)).get("role").body());
        assertEquals("module", ModuleSidecar.mergeModuleGranular(List.of(a), "a").get("role").body());
    }
}
