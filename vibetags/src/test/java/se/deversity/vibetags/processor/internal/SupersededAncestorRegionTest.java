package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
