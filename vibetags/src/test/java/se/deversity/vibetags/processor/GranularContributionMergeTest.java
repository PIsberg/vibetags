package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.ModuleSidecar;
import se.deversity.vibetags.processor.internal.content.GranularContribution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the granular merge primitives behind
 * <a href="https://github.com/PIsberg/vibetags/issues/365">issue #365</a>: the per-file
 * contribution record and {@link ModuleSidecar#mergeGranular}, which is what stops the last module
 * to compile from replacing a rule file several modules write.
 */
class GranularContributionMergeTest {

    private static ModuleSidecar sidecar(String moduleId, String regionId, String stem,
                                         List<String> globs, String body) {
        ModuleSidecar s = new ModuleSidecar(moduleId, "", regionId);
        s.putGranularContribution(stem, new GranularContribution(globs, body));
        return s;
    }

    // -----------------------------------------------------------------------
    // The record's wire form
    // -----------------------------------------------------------------------

    @Test
    void contributionRoundTripsThroughItsWireForm() {
        GranularContribution original = new GranularContribution(
            List.of("**/benchmark/**", "**/agent/**"), "## Core\n\n### com.example.A\n- **Note**: x");

        GranularContribution parsed = GranularContribution.parse(original.serialize());

        assertNotNull(parsed);
        assertEquals(original.globs(), parsed.globs());
        assertEquals(original.body(), parsed.body());
    }

    @Test
    void contributionWithNoGlobsRoundTrips() {
        GranularContribution parsed = GranularContribution.parse(
            new GranularContribution(List.of(), "body").serialize());

        assertNotNull(parsed);
        assertEquals(List.of(), parsed.globs());
        assertEquals("body", parsed.body());
    }

    @Test
    void aValueWithNoHeaderLineIsRejectedRatherThanGuessedAt() {
        assertNull(GranularContribution.parse("no-newline-anywhere"),
            "a malformed entry must be dropped so the caller falls back to its own rendering");
    }

    // -----------------------------------------------------------------------
    // The merge
    // -----------------------------------------------------------------------

    @Test
    void oneContributorsBodyIsReturnedVerbatim() {
        Map<String, GranularContribution> merged = ModuleSidecar.mergeGranular(
            List.of(sidecar("core", "core", "instrumentation", List.of("**/a/**"), "body-a")));

        assertEquals("body-a", merged.get("instrumentation").body(),
            "a lone contributor must keep the single-module output byte-for-byte");
    }

    @Test
    void severalModulesAreWrappedInSubMarkersAndTheirGlobsUnioned() {
        Map<String, GranularContribution> merged = ModuleSidecar.mergeGranular(List.of(
            sidecar("core", "core", "instrumentation", List.of("**/a/**"), "body-a"),
            sidecar("cli", "cli", "instrumentation", List.of("**/b/**"), "body-b")));

        GranularContribution role = merged.get("instrumentation");
        assertEquals(List.of("**/a/**", "**/b/**"), role.globs());
        assertEquals("""
            <!-- VIBETAGS-MODULE: core -->
            body-a
            <!-- VIBETAGS-MODULE-END: core -->
            <!-- VIBETAGS-MODULE: cli -->
            body-b
            <!-- VIBETAGS-MODULE-END: cli -->""", role.body());
    }

    @Test
    void twoSourceSetsOfOneModuleAreOneContributor() {
        Map<String, GranularContribution> merged = ModuleSidecar.mergeGranular(List.of(
            sidecar("core", "core", "instrumentation", List.of("**/a/**"), "body-main"),
            sidecar("core__test", "core", "instrumentation", List.of("**/a/**"), "body-test")));

        String body = merged.get("instrumentation").body();
        assertEquals("body-main\n\nbody-test", body,
            "one module compiled twice is one contributor — no sub-markers: " + body);
    }

    @Test
    void moduleGranularMergeIsScopedToOneRegionAndCarriesNoSubMarkers() {
        ModuleSidecar mine = new ModuleSidecar("core", "", "core");
        mine.putModuleGranularContribution("instrumentation",
            new GranularContribution(List.of("**/a/**"), "body-main"));
        ModuleSidecar myTests = new ModuleSidecar("core__test", "", "core");
        myTests.putModuleGranularContribution("instrumentation",
            new GranularContribution(List.of("**/a/**"), "body-test"));
        ModuleSidecar sibling = new ModuleSidecar("cli", "", "cli");
        sibling.putModuleGranularContribution("instrumentation",
            new GranularContribution(List.of("**/b/**"), "body-other-module"));

        Map<String, GranularContribution> merged =
            ModuleSidecar.mergeModuleGranular(List.of(mine, myTests, sibling), "core");

        assertEquals("body-main\n\nbody-test", merged.get("instrumentation").body(),
            "a module's own rules directory holds that module's guardrails only");
    }

    @Test
    void aStemNoModuleContributedIsAbsentFromTheMerge() {
        Map<String, GranularContribution> merged = ModuleSidecar.mergeGranular(
            List.of(sidecar("core", "core", "instrumentation", List.of("**/a/**"), "body-a")));

        assertTrue(merged.containsKey("instrumentation"));
        assertEquals(1, merged.size(), "the merge names only files some module actually wrote");
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    @Test
    void contributionsSurviveTheSidecarRoundTrip(@TempDir Path dir) throws IOException {
        ModuleSidecar saved = sidecar("core", "core", "instrumentation",
            List.of("**/benchmark/**"), "## Core\n\n### com.example.A\n- **Note**: x");
        saved.putModuleGranularContribution("nested",
            new GranularContribution(List.of("**/n/**"), "nested-body"));
        saved.save(dir);

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(dir);
        assertEquals(1, loaded.size());
        GranularContribution shared = loaded.get(0).getGranularContributions().get("instrumentation");
        assertNotNull(shared, "the shared contribution must survive save/load");
        assertEquals(List.of("**/benchmark/**"), shared.globs());
        assertEquals("## Core\n\n### com.example.A\n- **Note**: x", shared.body());
        assertEquals("nested-body",
            loaded.get(0).getModuleGranularContributions().get("nested").body(),
            "~modgran~ must not be swallowed by the ~mod~ prefix that precedes it alphabetically");
    }

    /**
     * A sidecar written by a processor that predates this feature carries no contributions at all.
     * It must still load — the compiling module then publishes its own rendering, which is the
     * behaviour before the merge existed, rather than failing the build.
     */
    @Test
    void aSidecarWithoutContributionsStillLoads(@TempDir Path dir) throws IOException {
        String body = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve(".vibetags-mod-legacy"),
            "# version=2\nmoduleId=legacy\nmodulePath=\nregionId=legacy\nclaude=" + body + "\n# end\n");

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(dir);

        assertEquals(1, loaded.size(), "a sidecar with no granular contributions is still valid");
        assertTrue(loaded.get(0).getGranularContributions().isEmpty());
        assertTrue(ModuleSidecar.mergeGranular(loaded).isEmpty(),
            "with nothing recorded there is nothing to merge, and the writer falls back");
    }

    /** A corrupt contribution is dropped, not guessed at, and does not take the sidecar with it. */
    @Test
    void aMalformedContributionIsDroppedButTheSidecarLoads(@TempDir Path dir) throws IOException {
        String garbage = Base64.getEncoder().encodeToString("no-newline".getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve(".vibetags-mod-broken"),
            "# version=2\nmoduleId=broken\nmodulePath=\nregionId=broken\n~gran~role=" + garbage + "\n# end\n");

        List<ModuleSidecar> loaded = ModuleSidecar.readAll(dir);

        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).getGranularContributions().isEmpty(),
            "an unparseable contribution is skipped rather than rendered into somebody's rule file");
    }
}
