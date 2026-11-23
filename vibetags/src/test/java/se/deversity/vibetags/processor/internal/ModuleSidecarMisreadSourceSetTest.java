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
