package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides which of two overlapping module identities wins.
 *
 * <p>An annotated element belongs to exactly one module: its source file lives in exactly one
 * module directory. So when a region's elements are all claimed by regions of modules nested
 * inside it, that region is the same sources read twice under a less specific identity, and
 * {@link ModuleSidecar#readAll(Path)} retires it.
 *
 * <p>The case this came from: a Gradle repository with one included subproject below the VibeTags
 * root left a {@code .vibetags-mod-_root_} sidecar (empty {@code modulePath}) beside the
 * subproject's own. The ordinary stale check cannot retire it, because its module path is the root
 * directory and that always exists, so every build wrote both regions with identical content into
 * every generated file. {@code AncestorModuleDuplicateRegionTest} pins the same defect end to end;
 * these are the boundary cases of the rule itself.
 */
class SupersededAncestorRegionTest {

    private static void writeSidecar(Path root, String moduleId, String modulePath,
                                     String... elementIds) throws IOException {
        ModuleSidecar sidecar = new ModuleSidecar(moduleId, modulePath);
        sidecar.putBody("claude", "body of " + moduleId);
        sidecar.setElementIds(Set.of(elementIds));
        sidecar.save(root);
    }

    /** Stamps a sidecar's mtime so the freshness comparison is deterministic, not scheduler-dependent. */
    private static void writtenAt(Path root, String moduleId, long millis) throws IOException {
        Files.setLastModifiedTime(root.resolve(".vibetags-mod-" + moduleId),
            java.nio.file.attribute.FileTime.fromMillis(millis));
    }

    private static List<String> regionIds(List<ModuleSidecar> sidecars) {
        return sidecars.stream().map(ModuleSidecar::getRegionId).sorted().toList();
    }

    @Test
    void rootRegionCoveredByItsOnlySubprojectIsRetired(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("app"));
        writeSidecar(root, "_root_", "", "com.example.A", "com.example.B");
        writeSidecar(root, "app", "app", "com.example.A", "com.example.B");

        assertEquals(List.of("app"), regionIds(ModuleSidecar.readAll(root)));
        assertFalse(Files.exists(root.resolve(".vibetags-mod-_root_")),
            "the superseded sidecar must be deleted, not merely skipped");
        assertTrue(Files.exists(root.resolve(".vibetags-mod-app")));
    }

    @Test
    void rootRegionWithAnElementOfItsOwnSurvives(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("app"));
        writeSidecar(root, "_root_", "", "com.example.A", "com.example.RootOnly");
        writeSidecar(root, "app", "app", "com.example.A");

        assertEquals(List.of("_root_", "app"), regionIds(ModuleSidecar.readAll(root)));
        assertTrue(Files.exists(root.resolve(".vibetags-mod-_root_")));
    }

    @Test
    void siblingModulesNeverSupersedeEachOther(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        writeSidecar(root, "a", "a", "com.example.A");
        writeSidecar(root, "b", "b", "com.example.B");

        assertEquals(List.of("a", "b"), regionIds(ModuleSidecar.readAll(root)));
    }

    @Test
    void aNestedRegionIsNeverRetiredByItsAncestor(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("app"));
        // The ancestor claims everything the subproject does, and more. Coverage runs downward
        // only: the subproject is the specific identity and must survive.
        writeSidecar(root, "_root_", "", "com.example.A", "com.example.B");
        writeSidecar(root, "app", "app", "com.example.A");

        assertTrue(regionIds(ModuleSidecar.readAll(root)).contains("app"));
    }

    @Test
    void coverageIsTransitiveDownTheTree(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        writeSidecar(root, "_root_", "", "com.example.A");
        writeSidecar(root, "a", "a", "com.example.A");
        writeSidecar(root, "a_b", "a/b", "com.example.A");

        assertEquals(List.of("a_b"), regionIds(ModuleSidecar.readAll(root)),
            "only the most specific module that claims the element may keep a region");
    }

    @Test
    void bothSourceSetsOfTheWinningModuleCount(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("app"));
        // Maven and Gradle compile a module once per source set, so the module's elements are
        // split over two sidecars sharing one region. Neither alone covers the root region.
        writeSidecar(root, "_root_", "", "com.example.Main", "com.example.MainTest");
        ModuleSidecar main = new ModuleSidecar("app", "app", "app");
        main.putBody("claude", "main body");
        main.setElementIds(Set.of("com.example.Main"));
        main.save(root);
        ModuleSidecar test = new ModuleSidecar("app__test", "app", "app");
        test.putBody("claude", "test body");
        test.setElementIds(Set.of("com.example.MainTest"));
        test.save(root);

        assertEquals(List.of("app", "app"), regionIds(ModuleSidecar.readAll(root)),
            "the region's source sets together account for the root region");
        assertFalse(Files.exists(root.resolve(".vibetags-mod-_root_")));
    }

    @Test
    void aSidecarThatRecordsNoElementsIsLeftAlone(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("app"));
        // Written before element ids were recorded: nothing to compare, so nothing to conclude.
        ModuleSidecar legacy = new ModuleSidecar("_root_", "");
        legacy.putBody("claude", "legacy body");
        legacy.save(root);
        writeSidecar(root, "app", "app", "com.example.A");

        assertEquals(List.of("_root_", "app"), regionIds(ModuleSidecar.readAll(root)));
        assertTrue(Files.exists(root.resolve(".vibetags-mod-_root_")));
    }

    @Test
    void peekAllExcludesTheSupersededRegionWithoutDeletingIt(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("app"));
        writeSidecar(root, "_root_", "", "com.example.A");
        writeSidecar(root, "app", "app", "com.example.A");

        assertEquals(List.of("app"), regionIds(ModuleSidecar.peekAll(root)));
        assertTrue(Files.exists(root.resolve(".vibetags-mod-_root_")),
            "check mode promises to touch nothing VibeTags manages");
    }

    // ------------------------------------------------------- freshness decides, not depth

    @Test
    void aStaleSubprojectIsRetiredByTheFresherRoot(@TempDir Path root) throws IOException {
        // The sources moved up out of `app`, which survives the move as a directory, so the
        // module-path staleness check cannot retire its sidecar. The root is the live identity.
        Files.createDirectories(root.resolve("app"));
        writeSidecar(root, "app", "app", "com.example.A");
        writeSidecar(root, "_root_", "", "com.example.A");
        writtenAt(root, "app", 1_000_000L);
        writtenAt(root, "_root_", 2_000_000L);

        assertEquals(List.of("_root_"), regionIds(ModuleSidecar.readAll(root)),
            "the fresher region describes the tree as it is now");
        assertFalse(Files.exists(root.resolve(".vibetags-mod-app")));
    }

    @Test
    void aStaleRootIsRetiredByTheFresherSubproject(@TempDir Path root) throws IOException {
        // The reported direction: sources moved down into `app`, the root sidecar is the leftover.
        Files.createDirectories(root.resolve("app"));
        writeSidecar(root, "_root_", "", "com.example.A");
        writeSidecar(root, "app", "app", "com.example.A");
        writtenAt(root, "_root_", 1_000_000L);
        writtenAt(root, "app", 2_000_000L);

        assertEquals(List.of("app"), regionIds(ModuleSidecar.readAll(root)));
        assertFalse(Files.exists(root.resolve(".vibetags-mod-_root_")));
    }

    @Test
    void onEqualTimestampsTheMoreSpecificModuleWins(@TempDir Path root) throws IOException {
        // Two sidecars written inside one filesystem tick must still resolve deterministically.
        Files.createDirectories(root.resolve("app"));
        writeSidecar(root, "_root_", "", "com.example.A");
        writeSidecar(root, "app", "app", "com.example.A");
        writtenAt(root, "_root_", 1_500_000L);
        writtenAt(root, "app", 1_500_000L);

        assertEquals(List.of("app"), regionIds(ModuleSidecar.readAll(root)),
            "a tie retires the ancestor, never the reverse");
    }

    @Test
    void anOlderAncestorNeverRetiresAFresherSubproject(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("app"));
        writeSidecar(root, "_root_", "", "com.example.A", "com.example.B");
        writeSidecar(root, "app", "app", "com.example.A");
        writtenAt(root, "_root_", 1_000_000L);
        writtenAt(root, "app", 2_000_000L);

        assertTrue(regionIds(ModuleSidecar.readAll(root)).contains("app"),
            "the subproject is the fresher identity for the element it claims");
    }
    // ------------------------------------------- one directory claimed under two identities

    @Test
    void twoRootLikeRegionsForOneDirectoryResolveToOne(@TempDir Path root) throws IOException {
        // Reported from a Gradle repo whose settings.gradle carries rootProject.name='x' and
        // include 'x'. Both compilations resolved a root-like module path: computeModulePath
        // returns "" for the root project, and also for any compilation root it cannot
        // relativize under the VibeTags root (an out-of-tree root, a ".."-escaping relative,
        // or an IllegalArgumentException). Two regions then describe one directory, and
        // neither is nested under the other, so both were written out verbatim and every
        // generated file stated the same guardrails twice under two VIBETAGS-MODULE markers.
        writeSidecar(root, "_root_", "", "com.example.A", "com.example.B");
        writeSidecar(root, "webapp", "", "com.example.A", "com.example.B");
        writtenAt(root, "_root_", 1_000_000L);
        writtenAt(root, "webapp", 2_000_000L);

        assertEquals(List.of("webapp"), regionIds(ModuleSidecar.readAll(root)),
            "one directory cannot hold two regions; the fresher identity describes it");
        assertFalse(Files.exists(root.resolve(".vibetags-mod-_root_")));
    }

    @Test
    void theStalerOfTwoIdenticallyPathedRegionsIsRetiredEitherWay(@TempDir Path root) throws IOException {
        // The mirror direction, so the rule cannot be satisfied by always preferring "_root_".
        writeSidecar(root, "_root_", "", "com.example.A");
        writeSidecar(root, "webapp", "", "com.example.A");
        writtenAt(root, "_root_", 2_000_000L);
        writtenAt(root, "webapp", 1_000_000L);

        assertEquals(List.of("_root_"), regionIds(ModuleSidecar.readAll(root)));
        assertFalse(Files.exists(root.resolve(".vibetags-mod-webapp")));
    }

    @Test
    void twoRegionsOnOneNonRootPathAlsoResolveToOne(@TempDir Path root) throws IOException {
        // Same shape one level down: a module compiled once under its derived id and once under
        // an -Avibetags.module override leaves two regions on one directory.
        Files.createDirectories(root.resolve("app"));
        writeSidecar(root, "app", "app", "com.example.A");
        writeSidecar(root, "custom-name", "app", "com.example.A");
        writtenAt(root, "app", 1_000_000L);
        writtenAt(root, "custom-name", 2_000_000L);

        assertEquals(List.of("custom-name"), regionIds(ModuleSidecar.readAll(root)));
    }

    @Test
    void identicallyPathedRegionsWithDifferentElementsBothSurvive(@TempDir Path root) throws IOException {
        // The safety net that keeps the new rule from eating a real module. "" is also the
        // catch-all for a compilation root that cannot be relativized, so two genuinely
        // different modules can land on it. They claim different elements, so neither covers
        // the other and both keep their region: containment is what makes retirement safe.
        writeSidecar(root, "_root_", "", "com.example.A");
        writeSidecar(root, "other", "", "com.example.B");
        writtenAt(root, "_root_", 1_000_000L);
        writtenAt(root, "other", 2_000_000L);

        assertEquals(List.of("_root_", "other"), regionIds(ModuleSidecar.readAll(root)));
        assertTrue(Files.exists(root.resolve(".vibetags-mod-_root_")));
    }

    @Test
    void identicallyPathedRegionsOnEqualTimestampsStillResolveToOne(@TempDir Path root) throws IOException {
        // Two sidecars written inside one filesystem tick. A tie must not mean "keep both",
        // which is the duplication, and must land the same way on every build.
        writeSidecar(root, "_root_", "", "com.example.A");
        writeSidecar(root, "webapp", "", "com.example.A");
        writtenAt(root, "_root_", 1_500_000L);
        writtenAt(root, "webapp", 1_500_000L);

        assertEquals(List.of("webapp"), regionIds(ModuleSidecar.readAll(root)),
            "a tie goes to the named module, matching the ancestor rule's preference");
    }

    // ------------------------------------------------ out-of-tree ids must not move with the tree

    /**
     * A module outside the VibeTags root is filed under an id derived from where it sits relative
     * to that root, not from where the checkout happens to live.
     *
     * <p>The id reaches committed output: it is the sidecar filename and the name in every
     * {@code VIBETAGS-MODULE} marker. Deriving it from the absolute path meant the same repository
     * produced different generated files on two machines, so committed output stopped reproducing
     * and check mode reported drift on a tree where nothing was wrong. Reachable through ordinary
     * layouts: Gradle {@code includeFlat} or a {@code projectDir} override, and Maven
     * {@code <module>../sibling</module>}. See issue #436.
     */
    @Test
    void outOfTreeModuleIdIsTheSameFromAnyCheckoutLocation() {
        String here = ModuleSidecar.computeModuleId(
            Paths.get("/home/alice/work/repo/lib"), Paths.get("/home/alice/work/repo/app"));
        String there = ModuleSidecar.computeModuleId(
            Paths.get("/ci/runner/build/repo/lib"), Paths.get("/ci/runner/build/repo/app"));

        assertEquals(here, there,
            "the same layout under a different checkout root must produce the same module id, or "
                + "committed generated files stop reproducing across machines");
    }

    /** Two different out-of-tree modules must still be told apart, or they share a sidecar. */
    @Test
    void differentOutOfTreeModulesStillGetDifferentIds() {
        String lib = ModuleSidecar.computeModuleId(
            Paths.get("/home/alice/repo/lib"), Paths.get("/home/alice/repo/app"));
        String tools = ModuleSidecar.computeModuleId(
            Paths.get("/home/alice/repo/tools"), Paths.get("/home/alice/repo/app"));

        assertNotEquals(lib, tools, "two modules sharing an id would share a sidecar");
    }

    /** Same directory name, different place: the id must still separate them. */
    @Test
    void outOfTreeModulesWithTheSameDirectoryNameAreStillDistinct() {
        String near = ModuleSidecar.computeModuleId(
            Paths.get("/home/alice/repo/lib"), Paths.get("/home/alice/repo/app"));
        String far = ModuleSidecar.computeModuleId(
            Paths.get("/home/alice/other/lib"), Paths.get("/home/alice/repo/app"));

        assertNotEquals(near, far,
            "a bare directory name would collide here and the two modules would overwrite one "
                + "another, which is worse than an opaque id");
    }

    /** The id is a filename, so it must stay short whatever the path length. */
    @Test
    void outOfTreeModuleIdStaysShortEnoughToBeAFilename() {
        StringBuilder deep = new StringBuilder("/home/alice");
        for (int i = 0; i < 40; i++) {
            deep.append("/averyverylongintermediatedirectoryname").append(i);
        }
        String id = ModuleSidecar.computeModuleId(
            Paths.get(deep + "/lib"), Paths.get("/home/alice/repo/app"));

        assertTrue(id.length() <= 64,
            "the id becomes '.vibetags-mod-<id>'; an unbounded one fails with ENAMETOOLONG and no "
                + "sidecar is written at all. Got " + id.length() + " chars: " + id);
    }

    /**
     * Upgrading past issue #436 renames an out-of-tree module's sidecar, because its id is no
     * longer derived from the absolute path. The old one is not stale by the ordinary test — its
     * module path is empty, so the directory-existence check is skipped and never retires it — so
     * without something else it would sit beside the new one and every generated file would carry
     * the module twice.
     *
     * <p>The equal-path rule handles it: both regions report the same (empty) module path and the
     * same elements, so the fresher wins and the leftover is deleted on the first build after the
     * upgrade. This pins that the upgrade heals itself rather than needing a manual cleanup.
     */
    @Test
    void aSidecarLeftUnderTheOldOutOfTreeIdIsRetiredByTheNewOne(@TempDir Path root) throws IOException {
        writeSidecar(root, "3f2a9c11", "", "com.example.A", "com.example.B");   // pre-#436 id
        writeSidecar(root, "lib_560aee97", "", "com.example.A", "com.example.B"); // post-#436 id
        writtenAt(root, "3f2a9c11", 1_000_000L);
        writtenAt(root, "lib_560aee97", 2_000_000L);

        assertEquals(List.of("lib_560aee97"), regionIds(ModuleSidecar.readAll(root)),
            "the upgrade must not leave the module represented twice");
        assertFalse(Files.exists(root.resolve(".vibetags-mod-3f2a9c11")),
            "and the leftover sidecar must be deleted, not merely skipped");
    }

}
