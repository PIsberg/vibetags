package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModuleSidecar#staleGranularStems} returns the departed modules' stems in an order that
 * does not depend on the filesystem.
 *
 * <p>The order is observable, which is the reason this exists. The returned set is consumed in
 * iteration order by {@code GranularRulesWriter.removeStems}, which collects what it deleted into
 * a {@code LinkedHashSet} in that same order, and the processor logs that as
 * {@code granular.departed.removed stems=}. The method used to walk a raw {@code Files.list}, so
 * the same reactor could print the same stems in a different order on a different filesystem, or
 * on the same one after a rename (#777).
 *
 * <p>What is pinned is the order <em>between</em> modules, which is what the listing decides. The
 * order within one module's stems is that sidecar's own serialisation and is left alone.
 *
 * <p>Real {@code .vibetags-mod-*} files, written through the sidecar's own {@code save}, because
 * the file format is cross-module law and a test that mocked it would pin nothing.
 */
@DisplayName("Departed granular stems come back in a filesystem-independent order")
class ModuleSidecarStaleStemOrderTest {

    private static final Set<String> ALPHA_STEMS = Set.of("com.example.Anvil", "com.example.Acorn");
    private static final Set<String> ZULU_STEMS = Set.of("com.example.Zebra", "com.example.Zircon");

    /**
     * Two departed modules, created in the order opposite to their sidecar filenames. Whichever
     * order the directory listing hands back, the stems come out grouped by sidecar filename.
     */
    @Test
    void stemsAreOrderedBySidecarFilenameNotByCreationOrder(@TempDir Path root) throws IOException {
        // Written first, sorts last. If creation order leaked through, zulu's stems would lead.
        departedModuleAt(root, "zulu", ZULU_STEMS);
        departedModuleAt(root, "alpha", ALPHA_STEMS);

        List<String> stems = List.copyOf(ModuleSidecar.staleGranularStems(root));

        assertEquals(4, stems.size(), "both departed modules contribute their stems: " + stems);
        // Compared as sets: the order within one module is that sidecar's own serialisation, and
        // only the order between modules is what the directory listing decides.
        assertEquals(ALPHA_STEMS, Set.copyOf(stems.subList(0, 2)),
            "alpha's sidecar sorts before zulu's, so its stems come first whatever order the "
                + "directory listing returned. Got: " + stems);
        assertEquals(ZULU_STEMS, Set.copyOf(stems.subList(2, 4)),
            "and zulu's follow, all of them: " + stems);
    }

    /**
     * The filter that decides what counts as a sidecar is shared with {@code anyStale} and
     * {@code readAll} rather than re-spelled here, so the three must agree about a half-written
     * {@code .tmp} file: none of them may read it.
     */
    @Test
    void aTmpSidecarIsNotReadAsADepartedModule(@TempDir Path root) throws IOException {
        departedModuleAt(root, "alpha", ALPHA_STEMS);
        departedModuleAt(root, "zulu", ZULU_STEMS);
        // zulu's save caught mid-rename: it names a departed module and real stems, and is ignored.
        Files.move(root.resolve(".vibetags-mod-zulu"), root.resolve(".vibetags-mod-zulu.tmp"));

        Set<String> stems = ModuleSidecar.staleGranularStems(root);

        assertEquals(ALPHA_STEMS, stems,
            "a .tmp file is a save in progress, not a sidecar, and staleGranularStems must agree "
                + "with anyStale and readAll about that: " + stems);
    }

    /** A root that is not a directory names no stems, and does not throw. */
    @Test
    void aRootThatIsNotADirectoryNamesNoStems(@TempDir Path parent) {
        Path absent = parent.resolve("no-such-dir");
        assertTrue(ModuleSidecar.staleGranularStems(absent).isEmpty(),
            "listPaths answers an absent root with an empty list rather than an exception");
        assertFalse(ModuleSidecar.anyStale(absent),
            "and anyStale, which shares that listing, answers the same way");
    }

    /**
     * Saves a sidecar naming a module directory that does not exist, which is what makes it
     * departed, with {@code stems} recorded as its granular rule files.
     */
    private static void departedModuleAt(Path root, String moduleId, Set<String> stems)
            throws IOException {
        ModuleSidecar sidecar = new ModuleSidecar(moduleId, moduleId);
        sidecar.setGranularStems(new LinkedHashSet<>(stems));
        sidecar.save(root);
        assertTrue(Files.exists(root.resolve(".vibetags-mod-" + moduleId)),
            "precondition: the sidecar was written where the reader looks for it");
        assertFalse(Files.isDirectory(root.resolve(moduleId)),
            "precondition: the module directory must be absent, or the sidecar is not stale");
    }
}
