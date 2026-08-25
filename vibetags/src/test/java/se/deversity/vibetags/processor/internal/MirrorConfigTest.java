package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code .vibetags-mirror} reader, exercised directly rather than only through
 * {@code MirrorEndToEndTest}.
 *
 * <p>Everything here is a decision made from a hand-edited file that no build step validates: a
 * source path that resolves to the wrong directory, a {@code glob =} line that is not recognised as
 * one, or a target that mirrors into itself all produce rule files that are wrong but present, and
 * a present-but-wrong rule file is exactly the failure mode VibeTags exists to avoid. Parsing is
 * pinned here at the level a typo can reach it.
 */
class MirrorConfigTest {

    private static MirrorConfig write(Path dir, String... lines) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MirrorConfig.FILE_NAME),
            String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return MirrorConfig.load(dir);
    }

    @Test
    void absentFileIsNotAMirrorTarget(@TempDir Path dir) {
        assertNull(MirrorConfig.load(dir), "no file, not a target; presence is the only opt-in");
    }

    @Test
    void aDirectoryNamedLikeTheConfigIsTreatedAsAbsent(@TempDir Path dir) throws IOException {
        // Files.isRegularFile is false for a directory, so load() must degrade to "not a target"
        // rather than failing the compile of a module that never asked for mirroring.
        Files.createDirectories(dir.resolve(MirrorConfig.FILE_NAME));
        assertNull(MirrorConfig.load(dir));
    }

    @Test
    void sourcePathsResolveRelativeToTheConfigsOwnDirectory(@TempDir Path root) throws IOException {
        Path target = root.resolve("payments-tests");
        Path core = root.resolve("payments-core");
        Files.createDirectories(core);
        MirrorConfig cfg = write(target, "../payments-core");

        assertNotNull(cfg);
        assertTrue(cfg.accepts(core), "the named sibling must be accepted");
        assertFalse(cfg.accepts(root.resolve("payments-api")), "an unnamed module must not be");
    }

    @Test
    void noSourceLinesMeansMirrorFromEveryModule(@TempDir Path root) throws IOException {
        MirrorConfig cfg = write(root.resolve("tests"), "# only a comment", "");
        assertNotNull(cfg);
        assertTrue(cfg.accepts(root.resolve("anything")),
            "an empty source list is the documented mirror-everything form");
    }

    @Test
    void aTargetNeverMirrorsIntoItself(@TempDir Path root) throws IOException {
        Path target = root.resolve("tests");
        MirrorConfig cfg = write(target, ".");
        assertNotNull(cfg);
        assertFalse(cfg.accepts(target),
            "self-mirroring would duplicate every rule file under the mirror prefix");
    }

    @Test
    void acceptsNullModuleRoot(@TempDir Path root) throws IOException {
        MirrorConfig cfg = write(root.resolve("tests"));
        assertNotNull(cfg);
        assertFalse(cfg.accepts(null), "an unresolved module root is not a mirror source");
    }

    @Test
    void repeatedSourcePathsAreDeduplicated(@TempDir Path root) throws IOException {
        Path target = root.resolve("tests");
        MirrorConfig cfg = write(target, "../core", "../core", "../a/../core");
        assertNotNull(cfg);
        assertTrue(cfg.accepts(root.resolve("core")));
    }

    @Test
    void globDirectiveIsRecognisedInEveryDocumentedSpelling(@TempDir Path root) throws IOException {
        assertEquals(List.of("**/a/**/*.java"),
            write(root.resolve("t1"), "glob = **/a/**/*.java").globs());
        assertEquals(List.of("**/b/**/*.java"),
            write(root.resolve("t2"), "globs = **/b/**/*.java").globs());
        assertEquals(List.of("**/c/**/*.java"),
            write(root.resolve("t3"), "glob: **/c/**/*.java").globs());
        assertEquals(List.of("**/d/**/*.java"),
            write(root.resolve("t4"), "GLOB = **/d/**/*.java").globs(),
            "the key is matched case-insensitively");
    }

    @Test
    void repeatedGlobsAreDeduplicatedAndTheDefaultIsNotAppended(@TempDir Path root) throws IOException {
        MirrorConfig cfg = write(root.resolve("tests"),
            "glob = **/x/**/*.java", "glob = **/x/**/*.java", "glob = **/y/**/*.java");
        assertNotNull(cfg);
        assertEquals(List.of("**/x/**/*.java", "**/y/**/*.java"), cfg.globs());
    }

    @Test
    void anEmptyGlobValueFallsBackToTheDefault(@TempDir Path root) throws IOException {
        MirrorConfig cfg = write(root.resolve("payments-tests"), "glob =");
        assertNotNull(cfg);
        assertEquals(List.of("**/payments-tests/**/*.java"), cfg.globs(),
            "an empty value must not leave the mirrored rules with no frontmatter glob at all");
    }

    @Test
    void aKeyThatIsNotGlobIsReadAsASourcePath(@TempDir Path root) throws IOException {
        MirrorConfig cfg = write(root.resolve("tests"), "note = something");
        assertNotNull(cfg);
        assertEquals(List.of("**/tests/**/*.java"), cfg.globs(),
            "an unrecognised key contributes no glob");
        assertFalse(cfg.accepts(root.resolve("core")),
            "but it does become a source path, so unrelated modules stop being accepted");
    }

    @Test
    void defaultGlobUsesTheTargetDirectoryName(@TempDir Path root) throws IOException {
        MirrorConfig cfg = write(root.resolve("integration-tests"));
        assertNotNull(cfg);
        assertEquals(List.of("**/integration-tests/**/*.java"), cfg.globs());
    }

    @Test
    void configFilePointsAtTheFileThatWasRead(@TempDir Path root) throws IOException {
        Path target = root.resolve("tests");
        MirrorConfig cfg = write(target);
        assertNotNull(cfg);
        assertEquals(target.toAbsolutePath().normalize().resolve(MirrorConfig.FILE_NAME),
            cfg.configFile(),
            "the fingerprint watches this path; a wrong one means an edit never invalidates");
        assertEquals(target.toAbsolutePath().normalize(), cfg.targetDir());
    }

    @Test
    void contentHashTracksTheRawFileContent(@TempDir Path root) throws IOException {
        String a = write(root.resolve("t1"), "../core").contentHash();
        String b = write(root.resolve("t2"), "../core").contentHash();
        String c = write(root.resolve("t3"), "../other").contentHash();
        assertEquals(a, b, "identical content hashes identically wherever it lives");
        assertNotEquals(a, c, "an edited config must invalidate the fingerprint");
    }

    @Test
    void discoverReturnsNothingForANullOrNonDirectoryRoot(@TempDir Path root) throws IOException {
        assertEquals(List.of(), MirrorConfig.discover(null));
        Path file = root.resolve("a-file");
        Files.createFile(file);
        assertEquals(List.of(), MirrorConfig.discover(file));
    }

    @Test
    void discoverFindsTargetsAtBothSupportedDepths(@TempDir Path root) throws IOException {
        write(root.resolve("tests"));                   // depth 1: root/module
        write(root.resolve("group").resolve("tests"));  // depth 2: root/group/module

        List<Path> found = MirrorConfig.discover(root).stream().map(MirrorConfig::targetDir).toList();
        assertEquals(2, found.size(), "both root/module and root/group/module must be reached");
        assertTrue(found.contains(root.resolve("tests").toAbsolutePath().normalize()));
        assertTrue(found.contains(root.resolve("group").resolve("tests").toAbsolutePath().normalize()));
    }

    @Test
    void discoverIgnoresTheRootItselfAndAnythingDeeperThanTwoLevels(@TempDir Path root) throws IOException {
        write(root);                                             // the root is not a target
        write(root.resolve("a").resolve("b").resolve("c"));       // depth 3 is out of range

        assertEquals(List.of(), MirrorConfig.discover(root),
            "the walk is bounded; an unbounded one would stat a whole workspace on every build");
    }

    @Test
    void discoverDoesNotDescendIntoBuildOutputOrSourceTrees(@TempDir Path root) throws IOException {
        write(root.resolve("target").resolve("classes"));
        write(root.resolve("src").resolve("main"));
        write(root.resolve("node_modules").resolve("pkg"));

        assertEquals(List.of(), MirrorConfig.discover(root),
            "a stray config under target/ or src/ is not a module and must not be picked up");
    }
}
