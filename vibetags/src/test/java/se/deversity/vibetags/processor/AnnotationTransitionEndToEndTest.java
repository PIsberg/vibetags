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
 * Transitions the existing lifecycle tests do not reach: the annotated element survives, but what
 * is true about it changes.
 *
 * <p>{@code RefactorAnnotatedElementTest} renames and deletes elements. {@code
 * GuardrailLifecycleEndToEndTest} edits an attribute and removes an annotation. Between those two
 * sit the transitions that keep the element and move it to a different <em>bucket</em>, a different
 * <em>module</em>, or a different <em>rule file</em> — and each one is a two-writer problem, where
 * the new location is written by a code path that has no reason to know about the old one.
 *
 * <ul>
 *   <li><b>Swapping the annotation type.</b> The element's FQN never changes, so nothing about it
 *       looks stale; only the bucket it renders into does. A cleanup keyed on the element rather
 *       than on the bucket leaves the old rule in place, and the file then states two contradictory
 *       things about the same class.</li>
 *   <li><b>Moving a class between modules.</b> The merged root is assembled from per-module
 *       sidecars, so a moved class is added by one module and has to be dropped by another — the
 *       exact shape of #365 and #383, and the one where the failure is a duplicate rather than a
 *       missing entry.</li>
 *   <li><b>Editing {@code .vibetags-roles}.</b> Renaming a role renames its rule file. The old file
 *       is now an orphan that no annotation points at, and the granular cleanup deletes by stem —
 *       so whether it goes depends on machinery that a role rename does not obviously touch.</li>
 * </ul>
 */
@Tag("e2e")
class AnnotationTransitionEndToEndTest {

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    // -----------------------------------------------------------------------
    // The element keeps its name and changes its bucket
    // -----------------------------------------------------------------------

    @Test
    void swappingTheAnnotationType_retiresTheOldBucket(@TempDir Path dir) throws Exception {
        ProcessorTestHarness first = new ProcessorTestHarness(dir, false);
        first.touchOptIn("CLAUDE.md");
        first.touchOptIn(".cursorrules");
        first.addSource("com.example.Ledger",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AIContext(focus = \"double-entry bookkeeping\", avoids = \"floating point\")\n"
                + "public class Ledger {}\n");
        first.compile();
        assertTrue(first.readFile("CLAUDE.md").contains("double-entry bookkeeping"),
            "precondition: the first annotation must be generated");

        ProcessorTestHarness.awaitFilesystemTick(dir);
        VibeTagsLogger.shutdown();

        // Same class, same FQN, different annotation: the guidance becomes a lock.
        ProcessorTestHarness second = new ProcessorTestHarness(dir, false);
        second.touchOptIn("CLAUDE.md");
        second.touchOptIn(".cursorrules");
        second.addSource("com.example.Ledger",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"Frozen pending the ledger rewrite\")\n"
                + "public class Ledger {}\n");
        second.compile();

        for (String file : new String[]{"CLAUDE.md", ".cursorrules"}) {
            String content = second.readFile(file);
            assertTrue(content.contains("Frozen pending the ledger rewrite"),
                file + " must carry the new annotation's rule");
            assertFalse(content.contains("double-entry bookkeeping"),
                file + " still carries the superseded annotation. The element never changed name, "
                    + "so a cleanup keyed on the element sees nothing to remove — and the file now "
                    + "tells an agent both to work on the class and not to touch it:\n" + content);
        }
    }

    // -----------------------------------------------------------------------
    // The element keeps its name and changes module
    // -----------------------------------------------------------------------

    /**
     * A class moves from one module to another in a reactor. Both modules recompile, as they would
     * in the build that follows the move. The merged root has to end up with the guardrail once:
     * the losing module's sidecar drops it, the gaining module's adds it.
     *
     * <p>The losing module keeps a second annotated class on purpose. A module emptied of
     * <em>every</em> annotation is the documented no-op case pinned by
     * {@code GuardrailLifecycleEndToEndTest} — testing the move through it would assert the known
     * limitation instead of the merge.
     */
    @Test
    void movingAnAnnotatedClassBetweenModules_leavesNoDuplicate(@TempDir Path root) throws Exception {
        setUpReactor(root);

        compileModule(root, "module-a",
            source("module-a", "com.example.a.Engine", locked("com.example.a", "Engine", "Engine internals")),
            source("module-a", "com.example.a.Keeper", locked("com.example.a", "Keeper", "Stays put")));
        compileModule(root, "module-b",
            source("module-b", "com.example.b.Cli", locked("com.example.b", "Cli", "CLI entry point")));

        String before = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertEquals(1, count(before, "com.example.a.Engine"),
            "precondition: the class is in module-a exactly once:\n" + before);

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        // The refactor: Engine moves to module-b, package and all. Both modules rebuild.
        Files.delete(root.resolve("module-a/src/main/java/com/example/a/Engine.java"));
        compileModule(root, "module-a",
            source("module-a", "com.example.a.Keeper", locked("com.example.a", "Keeper", "Stays put")));
        compileModule(root, "module-b",
            source("module-b", "com.example.b.Cli", locked("com.example.b", "Cli", "CLI entry point")),
            source("module-b", "com.example.b.Engine", locked("com.example.b", "Engine", "Engine internals")));

        String after = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertEquals(1, count(after, "Engine internals"),
            "the moved guardrail must appear once. Two copies means the losing module's sidecar "
                + "still claims a class it no longer compiles:\n" + after);
        assertTrue(after.contains("com.example.b.Engine"), "the gaining module's FQN must be the live one");
        assertFalse(after.contains("com.example.a.Engine"),
            "the old FQN must be gone from the merged root:\n" + after);
        assertTrue(after.contains("Stays put") && after.contains("CLI entry point"),
            "neither module's remaining guardrails may be disturbed by the move");
    }

    // -----------------------------------------------------------------------
    // The element keeps its name and changes rule file
    // -----------------------------------------------------------------------

    @Test
    void renamingARoleInTheConfig_retiresTheOldRoleFile(@TempDir Path dir) throws Exception {
        ProcessorTestHarness first = granular(dir, "api-endpoints = **/*Controller.java\n");
        first.addSource("com.example.web.OrderController", controller());
        first.compile();
        assertTrue(first.fileExists(".cursor/rules/api-endpoints.mdc"),
            "precondition: the role file is generated under its first name");

        ProcessorTestHarness.awaitFilesystemTick(dir);
        VibeTagsLogger.shutdown();

        // The team renames the role. Same glob, same class, different file name.
        ProcessorTestHarness second = granular(dir, "controllers = **/*Controller.java\n");
        second.addSource("com.example.web.OrderController", controller());
        second.compile();

        assertTrue(second.fileExists(".cursor/rules/controllers.mdc"),
            "the renamed role must produce its rule file");
        assertTrue(second.readFile(".cursor/rules/controllers.mdc").contains("com.example.web.OrderController"),
            "and must carry the class that routes to it");
        assertFalse(second.fileExists(".cursor/rules/api-endpoints.mdc"),
            "the old role file is an orphan no annotation points at any more. Left behind, the "
                + "agent loads two rule files for the same class and the stale one never expires.");
    }

    @Test
    void deletingTheRolesConfig_fallsBackToPerClassFilesAndRetiresTheRoleFile(@TempDir Path dir)
            throws Exception {
        ProcessorTestHarness first = granular(dir, "api-endpoints = **/*Controller.java\n");
        first.addSource("com.example.web.OrderController", controller());
        first.compile();
        assertTrue(first.fileExists(".cursor/rules/api-endpoints.mdc"), "precondition: role grouping is on");

        Files.delete(dir.resolve(".vibetags-roles"));
        ProcessorTestHarness.awaitFilesystemTick(dir);
        VibeTagsLogger.shutdown();

        ProcessorTestHarness second = new ProcessorTestHarness(dir, false);
        second.touchOptIn(".cursor/rules/.vibetags");
        second.addSource("com.example.web.OrderController", controller());
        second.compile();

        assertTrue(second.fileExists(".cursor/rules/com-example-web-OrderController.mdc"),
            "with no roles config the class falls back to its per-class rule file");
        assertFalse(second.fileExists(".cursor/rules/api-endpoints.mdc"),
            "turning role grouping off must retire the role files it produced, or the class is "
                + "described twice — once per class and once by a role that no longer exists");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ProcessorTestHarness granular(Path dir, String rolesContent) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn(".cursor/rules/.vibetags");
        Files.writeString(dir.resolve(".vibetags-roles"), rolesContent, StandardCharsets.UTF_8);
        return h;
    }

    private static String controller() {
        return "package com.example.web;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"auth surface\")\n"
            + "public class OrderController {}\n";
    }

    private static String locked(String pkg, String type, String reason) {
        return "package " + pkg + ";\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"" + reason + "\")\n"
            + "public class " + type + " {}\n";
    }

    /** A module-relative source path paired with its content, for {@link #compileModule}. */
    private record Src(String relativePath, String content) { }

    private static Src source(String module, String fqn, String content) {
        return new Src(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", content);
    }

    private static void setUpReactor(Path root) throws IOException {
        Files.createDirectories(root.resolve("module-a"));
        Files.createDirectories(root.resolve("module-b"));
        Files.createFile(root.resolve("CLAUDE.md"));
    }

    /** One module's compile into the shared reactor root, as a reactor pass would do it. */
    private static void compileModule(Path root, String module, Src... sources) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        for (Src src : sources) {
            harness.writeSourceFile(src.relativePath(), src.content());
        }
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
