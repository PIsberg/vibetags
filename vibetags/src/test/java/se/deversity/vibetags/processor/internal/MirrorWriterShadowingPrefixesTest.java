package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which sibling modules {@link MirrorWriter#shadowingPrefixes} protects from a module's cleanup.
 *
 * <p>{@code MirrorEndToEndTest} covers the one sibling the bug report named (#383's
 * {@code core}/{@code core-api}). The walk that finds siblings has limits of its own, and each one
 * fails in a direction nothing else notices: a module it does not reach has its mirrored files
 * deleted by every build of the shorter-named module, and a directory it wrongly counts as a
 * module only protects files nobody wrote. These cases pin the depth bound, the "own build file"
 * test, the skipped build-output directories, and the order of the result.
 */
class MirrorWriterShadowingPrefixesTest {

    private static void module(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
    }

    private static List<String> prefixes(Path root, String regionId) {
        return new ArrayList<>(MirrorWriter.shadowingPrefixes(root, regionId));
    }

    private static String mirrored(String moduleId) {
        return MirrorWriter.MIRROR_PREFIX + moduleId;
    }

    @Test
    void protectsEverySiblingWhoseIdExtendsTheRegionWithinThreeLevels(@TempDir Path root) throws IOException {
        module(root);
        module(root.resolve("core"));                                    // the region itself
        module(root.resolve("core-api"));                                // depth 1
        module(root.resolve("core-api").resolve("nested"));              // depth 2: core-api_nested
        module(root.resolve("core-x").resolve("a").resolve("b"));        // depth 3: core-x_a_b
        module(root.resolve("other-api"));                               // does not extend core

        assertEquals(List.of(mirrored("core-api"), mirrored("core-api_nested"), mirrored("core-x_a_b")),
            prefixes(root, "core"),
            "every module whose id starts with core- is a sibling to protect, in walk order");
    }

    @Test
    void doesNotLookPastThreeLevels(@TempDir Path root) throws IOException {
        module(root);
        module(root.resolve("core-y").resolve("a").resolve("b").resolve("c")); // depth 4

        assertEquals(List.of(), prefixes(root, "core"),
            "the search is bounded, one level past mirror discovery");
    }

    @Test
    void aDirectoryWithoutItsOwnBuildFileIsNotAModule(@TempDir Path root) throws IOException {
        module(root);
        Files.createDirectories(root.resolve("core-z").resolve("docs"));

        assertEquals(List.of(), prefixes(root, "core"),
            "core-z resolves to the root's build file, so it is part of the root module, not a sibling");
    }

    @Test
    void doesNotDescendIntoBuildOutputOrSourceTrees(@TempDir Path root) throws IOException {
        module(root);
        module(root.resolve("core-w").resolve("target"));
        module(root.resolve("core-v").resolve("src"));

        assertEquals(List.of(), prefixes(root, "core"),
            "a pom.xml under target/ or src/ is build output or a fixture, not a sibling module");
    }
}
