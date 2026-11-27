package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retirement of a source-set sidecar written under a misread name (#1068): which pairs retire the
 * non-main sidecar, and which leave both. Real {@code .vibetags-mod-*} files, read back through
 * {@link ModuleSidecar#readAll(Path)}.
 */
class ModuleSidecarMisreadSourceSetTest {

    private static final long OLDER = 1_700_000_000_000L;
    private static final long NEWER = OLDER + 60_000L;

    @Test
    void aFlatSrcMainSidecarRetiresAnOlderSourceSetSidecarOfItsModule(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        save(root, "core__com", List.of(), OLDER);
        save(root, "core", List.of("core/src"), NEWER);

        assertEquals(List.of("core"), ids(ModuleSidecar.readAll(root)));
        assertEquals(false, Files.exists(root.resolve(".vibetags-mod-core__com")), "and deletes its file");
    }

    @Test
    void aSourceSetSidecarWrittenAfterTheFlatOneIsKept(@TempDir Path root) throws IOException {
        // An older processor wrote it after this one: it may be the module's current content.
        Files.createDirectories(root.resolve("core"));
        save(root, "core", List.of("core/src"), OLDER);
        save(root, "core__com", List.of(), NEWER);

        assertEquals(List.of("core", "core__com"), ids(ModuleSidecar.readAll(root)));
    }

    @Test
    void aConventionalRootRetiresNoSourceSet(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        save(root, "core__test", List.of("core/src/test/java"), OLDER);
        save(root, "core", List.of("core/src/main/java"), NEWER);

        assertEquals(List.of("core", "core__test"), ids(ModuleSidecar.readAll(root)));
    }

    @Test
    void anotherModulesFlatRootRetiresNothingHere(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core"));
        Files.createDirectories(root.resolve("app"));
        save(root, "core__com", List.of(), OLDER);
        save(root, "app", List.of("app/src"), NEWER);

        assertEquals(List.of("app", "core__com"), ids(ModuleSidecar.readAll(root)));
    }

    /**
     * The sibling that retires a source set is another sidecar. A non-main sidecar that records a
     * flat {@code src} root of its own satisfies every other clause of the rule against itself
     * (same region, written no earlier than itself), and would delete its own file on every build.
     */
    @Test
    void aSourceSetSidecarIsNotRetiredByItsOwnFlatRoot(@TempDir Path root) throws IOException {
        // The round compiled from the conventional tree and from a second directory ending in src:
        // "it/src" is a root whose first src segment is its last, which is what the rule looks for.
        Files.createDirectories(root.resolve("core/src/it/java"));
        Files.createDirectories(root.resolve("core/it/src"));
        save(root, "core__it", List.of("core/src/it/java", "core/it/src"), NEWER);

        assertEquals(List.of("core__it"), ids(ModuleSidecar.readAll(root)));
    }

    /**
     * #1159. A main round that compiles the conventional tree and a second directory ending in
     * {@code src} records a flat-looking root, and the module's real test sidecar, whose sources are
     * in {@code src/test/java}, was retired and deleted on every compile-only build.
     */
    @Test
    void aSecondMainRootEndingInSrcDoesNotRetireTheRealTestSidecar(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core/src/main/java"));
        Files.createDirectories(root.resolve("core/src/test/java"));
        Files.createDirectories(root.resolve("core/gen/src"));
        save(root, "core__test", List.of("core/src/test/java"), OLDER);
        save(root, "core", List.of("core/src/main/java", "core/gen/src"), NEWER);

        assertEquals(List.of("core", "core__test"), ids(ModuleSidecar.readAll(root)));
        assertEquals(true, Files.exists(root.resolve(".vibetags-mod-core__test")), "and keeps its file");
    }

    /** A misread sidecar written after ~roots existed records the flat root itself, and is still retired. */
    @Test
    void aMisreadSidecarRecordingTheFlatRootIsStillRetired(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core/src"));
        save(root, "core__com", List.of("core/src"), OLDER);
        save(root, "core", List.of("core/src"), NEWER);

        assertEquals(List.of("core"), ids(ModuleSidecar.readAll(root)));
    }

    /**
     * Packages under a flat {@code src/test/} were filed as {@code __test} before #1068. That sidecar
     * is named after a real source set but records no {@code src/test/...} root, so it is still the
     * misread one.
     */
    @Test
    void aMisreadTestSidecarFromAFlatSrcIsStillRetired(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core/src/test"));
        save(root, "core__test", List.of("core/src"), OLDER);
        save(root, "core", List.of("core/src"), NEWER);

        assertEquals(List.of("core"), ids(ModuleSidecar.readAll(root)));
    }

    /**
     * #1214. A real source set compiled only from a non-conventional root ({@code it/src}, from a
     * Gradle {@code srcDir} or a build-helper execution) records no {@code src/it/...} root, so the
     * #1159 exemption did not cover it, and a main sidecar recording a flat-looking root retired it.
     * A misread sidecar compiled the same sources as its sibling, so every root it records is one
     * the sibling records too; this one records a root the sibling does not.
     */
    @Test
    void aSourceSetOnANonConventionalRootOfItsOwnIsKept(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core/src"));
        Files.createDirectories(root.resolve("core/it/src"));
        save(root, "core__it", List.of("core/it/src"), OLDER);
        save(root, "core", List.of("core/src"), NEWER);

        assertEquals(List.of("core", "core__it"), ids(ModuleSidecar.readAll(root)));
        assertEquals(true, Files.exists(root.resolve(".vibetags-mod-core__it")), "and keeps its file");
    }

    @Test
    void aSourceSetOnANonConventionalRootIsKeptBesideAGeneratedFlatRoot(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core/src/main/java"));
        Files.createDirectories(root.resolve("core/gen/src"));
        Files.createDirectories(root.resolve("core/it/src"));
        save(root, "core__it", List.of("core/it/src"), OLDER);
        save(root, "core", List.of("core/src/main/java", "core/gen/src"), NEWER);

        assertEquals(List.of("core", "core__it"), ids(ModuleSidecar.readAll(root)));
    }

    /** The sibling may have gained a root since; a misread sidecar's roots are still among its own. */
    @Test
    void aMisreadSidecarIsRetiredBySiblingThatGainedARoot(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("core/src"));
        Files.createDirectories(root.resolve("core/gen/src"));
        save(root, "core__com", List.of("core/src"), OLDER);
        save(root, "core", List.of("core/src", "core/gen/src"), NEWER);

        assertEquals(List.of("core"), ids(ModuleSidecar.readAll(root)));
    }

    private static void save(Path root, String moduleId, List<String> sourceRoots, long mtime) throws IOException {
        String region = moduleId.contains(ModuleSidecar.SOURCE_SET_SEPARATOR)
            ? moduleId.substring(0, moduleId.indexOf(ModuleSidecar.SOURCE_SET_SEPARATOR)) : moduleId;
        ModuleSidecar sidecar = new ModuleSidecar(moduleId, region, region);
        sidecar.putBody("claude", "guardrails of " + moduleId);
        sidecar.setSourceRoots(sourceRoots);
        sidecar.save(root);
        Files.setLastModifiedTime(root.resolve(ModuleSidecar.SIDECAR_PREFIX + moduleId), FileTime.fromMillis(mtime));
    }

    private static List<String> ids(List<ModuleSidecar> sidecars) {
        return sidecars.stream().map(ModuleSidecar::getModuleId).sorted().toList();
    }
}
