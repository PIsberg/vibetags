package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reverse move of {@link AncestorModuleDuplicateRegionTest}: sources leave a subproject and
 * come back up to the root, while the subproject directory itself stays behind.
 *
 * <p>This is the case that keeps the superseded-ancestor rule honest. That rule retires a region
 * whose elements are all claimed by regions of modules nested inside it, and here the nested
 * region is the <em>stale</em> one: `app/` still exists (its resources, its git history, an
 * emptied `pom.xml`), so the ordinary module-directory staleness check cannot retire
 * `.vibetags-mod-app`, and the live `_root_` region claims exactly the elements the dead `app`
 * region still lists.
 *
 * <p>Retiring the live region there would be worse than the duplication it fixes: every later edit
 * to those annotations would render into a sidecar that is then dropped, and the generated files
 * would freeze on the last text the departed module happened to write.
 */
@Tag("e2e")
class ModuleFlattenedIntoRootTest {

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private static String locked(String reason) {
        return "package com.example.core;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"" + reason + "\")\n"
            + "public class IrNode {}\n";
    }

    /** Compiles the class from inside the `app` subproject, which has its own build file. */
    private void compileFromSubproject(String reason) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/pom.xml"),
            "<project><artifactId>app</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile("app/src/main/java/com/example/core/IrNode.java", locked(reason));
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    /** Compiles the same class from the root, as it is after the sources move up. */
    private void compileFromRoot(String reason) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>whole</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile("src/main/java/com/example/core/IrNode.java", locked(reason));
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    @Test
    void afterTheSourcesMoveUp_aLaterEditStillReachesTheGeneratedFile() throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileFromSubproject("Core IR node");
        assertTrue(Files.exists(root.resolve(".vibetags-mod-app")),
            "precondition: the subproject owned the element first");

        // The sources move up to the root. `app/` stays behind - emptied of Java, still a
        // directory - so the module-path staleness check cannot retire its sidecar.
        deleteRecursively(root.resolve("app/src"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        compileFromRoot("Core IR node");

        // Now edit the annotation. This is the assertion that matters: the edit is rendered by the
        // root, and must not be discarded in favour of the departed subproject's frozen copy.
        ProcessorTestHarness.awaitFilesystemTick(root);
        compileFromRoot("Core IR node, revised");

        String claude = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("Core IR node, revised"),
            "the live region's edit must reach the generated file:\n" + claude);
        assertFalse(claude.contains("Core IR node\n") && !claude.contains("revised"),
            "the departed subproject's frozen text must not be what survives:\n" + claude);
        assertEquals(1, countOf(claude, "com.example.core.IrNode"),
            "one element, one entry - not one per identity that ever claimed it:\n" + claude);
    }

    @Test
    void afterTheSourcesMoveUp_theGeneratedFileNamesTheElementOnce() throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileFromSubproject("Core IR node");
        deleteRecursively(root.resolve("app/src"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        compileFromRoot("Core IR node");

        String claude = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertEquals(1, countOf(claude, "com.example.core.IrNode"),
            "the element moved, it was not duplicated:\n" + claude);
    }

    /**
     * The same move, with a genuine sibling module compiling afterwards so the multi-region merge
     * runs. This is where retiring the live region costs real content rather than only tidiness:
     * the aggregate is assembled from sidecars, so if the root's is gone the file states the
     * departed subproject's frozen text and the edit is lost with no diagnostic.
     */
    @Test
    void afterTheSourcesMoveUp_aSiblingModulesBuildDoesNotRevertTheRootsEdit() throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileFromSubproject("Core IR node");
        deleteRecursively(root.resolve("app/src"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        compileFromRoot("Core IR node, revised");

        ProcessorTestHarness.awaitFilesystemTick(root);
        compileSibling();

        String claude = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("Core IR node, revised"),
            "the sibling's build must not revert the root to the departed module's copy:\n" + claude);
        assertTrue(claude.contains("com.example.lib.Helper"),
            "the sibling's own guardrails must be there too:\n" + claude);
        assertEquals(1, countOf(claude, "com.example.core.IrNode"),
            "one element, one entry:\n" + claude);
    }

    /** A genuine second module, which forces the merge across more than one region. */
    private void compileSibling() throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/pom.xml"),
            "<project><artifactId>lib</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile("lib/src/main/java/com/example/lib/Helper.java", """
            package com.example.lib;

            import se.deversity.vibetags.annotations.AILocked;

            @AILocked(reason = "Helper")
            public class Helper {}
            """);
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    /**
     * The prune runs on every read, so it must converge. Two rounds that each claim the same
     * element - a build misconfigured so two source roots see one class - would flip the surviving
     * region back and forth if freshness alone decided it round by round, and every build would
     * rewrite the generated files. Repeating the same pair of rounds must leave the file alone.
     */
    @Test
    void repeatingTheSameBuildPairLeavesTheFileByteIdentical() throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileFromSubproject("Core IR node");
        compileFromRoot("Core IR node");
        String afterFirstPair = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);

        ProcessorTestHarness.awaitFilesystemTick(root);
        compileFromSubproject("Core IR node");
        compileFromRoot("Core IR node");

        assertEquals(afterFirstPair,
            Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8),
            "the same build twice must not churn the generated file");
    }

    /**
     * Check mode reads the same sidecars through {@code peekAll}, which excludes a superseded
     * region without deleting it. If the two disagreed, a build that just wrote its files would
     * fail its own verification.
     */
    @Test
    void checkModeAgreesWithWhatGenerationJustWrote() throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileFromSubproject("Core IR node");
        deleteRecursively(root.resolve("app/src"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        compileFromRoot("Core IR node");

        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/core/IrNode.java", locked("Core IR node"));
        java.util.List<String> errors = harness.compileReturningDiagnostics("-Avibetags.check=true")
            .stream()
            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();

        assertTrue(errors.isEmpty(),
            "check mode must not report drift against files generation just wrote: " + errors);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort in a temp dir
                }
            });
        }
    }
}
