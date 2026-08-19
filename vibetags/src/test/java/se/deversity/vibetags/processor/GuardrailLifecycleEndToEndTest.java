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
 * What happens to generated guardrails over the life of a project: an annotation's value is
 * edited, an annotation is deleted, and a platform is opted out of.
 *
 * <p>Each of these is a *second* compile whose correctness depends on the first, which is why they
 * are easy to get wrong and hard to notice: the file is regenerated, it looks plausible, and the
 * stale half is only visible if you knew what used to be there. Every VibeTags multi-module defect
 * so far has failed exactly that way — well-formed output, green build, guardrails quietly wrong.
 *
 * <p>{@code RefactorAnnotatedElementTest} already covers renames and removal in a single module.
 * What was missing, and is here:
 *
 * <ul>
 *   <li><b>Editing an attribute.</b> Removal cleans up because the element leaves the model
 *       entirely; an edit keeps the same element and changes its text, which is the case a
 *       stale-content bug survives.</li>
 *   <li><b>Opting out.</b> "File presence is the opt-in, and deleting one deactivates that platform
 *       permanently" is the load-bearing invariant of the whole design, and nothing asserted it
 *       end-to-end. The nearest test deleted {@code .cursorrules} and asserted it came *back* —
 *       true there only because its harness re-creates every opt-in file before compiling.</li>
 *   <li><b>Both, through a reactor.</b> An edit or removal in one module has to reach the merged
 *       root files, including the whole-file JSON and TOML outputs that are assembled from every
 *       module's sidecar rather than from a marker region.</li>
 * </ul>
 */
@Tag("e2e")
class GuardrailLifecycleEndToEndTest {

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    // -----------------------------------------------------------------------
    // Editing an annotation's value
    // -----------------------------------------------------------------------

    @Test
    void editingAnAnnotationReason_replacesTheOldTextEverywhere(@TempDir Path dir) throws Exception {
        ProcessorTestHarness before = new ProcessorTestHarness(dir);
        before.addSource("com.example.Ledger", ledger("Balances are reconciled nightly"));
        before.compile();
        assertTrue(before.readFile("CLAUDE.md").contains("Balances are reconciled nightly"),
            "the original reason must be generated in the first place");

        ProcessorTestHarness.awaitFilesystemTick(dir);
        VibeTagsLogger.shutdown();

        ProcessorTestHarness after = new ProcessorTestHarness(dir);
        after.addSource("com.example.Ledger", ledger("Balances are reconciled hourly since INC-88"));
        after.compile();

        for (String file : new String[]{"CLAUDE.md", ".cursorrules", "llms.txt", ".mentatconfig.json"}) {
            String content = after.readFile(file);
            assertTrue(content.contains("Balances are reconciled hourly since INC-88"),
                file + " must carry the edited reason");
            assertFalse(content.contains("Balances are reconciled nightly"),
                file + " still carries the superseded reason — an edit left stale text behind, "
                    + "which is the failure mode a removal test cannot catch");
        }
    }

    // -----------------------------------------------------------------------
    // Opting a platform out
    // -----------------------------------------------------------------------

    /**
     * The invariant in one test: delete a generated file and that platform is done. Not "until the
     * next build", not "unless something else changes" — gone, until a human puts the file back.
     */
    @Test
    void deletingAGeneratedFile_optsThatPlatformOutPermanently(@TempDir Path dir) throws Exception {
        ProcessorTestHarness first = optedIn(dir, "CLAUDE.md", ".cursorrules");
        first.addSource("com.example.Ledger", ledger("Reconciliation is load-bearing"));
        first.compile();
        assertTrue(first.fileExists("CLAUDE.md") && first.fileExists(".cursorrules"),
            "both opted-in files must be generated first");

        Files.delete(dir.resolve(".cursorrules"));
        ProcessorTestHarness.awaitFilesystemTick(dir);
        VibeTagsLogger.shutdown();

        // Recompile with a *changed* annotation, so the round has real work to do and cannot be
        // short-circuited — the file must still stay deleted.
        ProcessorTestHarness second = optedIn(dir, "CLAUDE.md");
        second.addSource("com.example.Ledger", ledger("Reconciliation now runs hourly"));
        second.compile();

        assertFalse(Files.exists(dir.resolve(".cursorrules")),
            "a deleted output file is an opt-out: VibeTags must never re-create it. Recreating it "
                + "would resurrect a file the developer deliberately removed.");
        assertTrue(second.readFile("CLAUDE.md").contains("Reconciliation now runs hourly"),
            "opting one platform out must not stop the others from updating");
    }

    @Test
    void optingOutIsReversible_restoringTheFileReactivatesThePlatform(@TempDir Path dir) throws Exception {
        ProcessorTestHarness first = optedIn(dir, "CLAUDE.md", ".cursorrules");
        first.addSource("com.example.Ledger", ledger("Reconciliation is load-bearing"));
        first.compile();

        Files.delete(dir.resolve(".cursorrules"));
        ProcessorTestHarness.awaitFilesystemTick(dir);
        VibeTagsLogger.shutdown();

        // The developer changes their mind and creates the empty signal file again.
        Files.createFile(dir.resolve(".cursorrules"));
        ProcessorTestHarness.awaitFilesystemTick(dir);

        ProcessorTestHarness third = optedIn(dir, "CLAUDE.md", ".cursorrules");
        third.addSource("com.example.Ledger", ledger("Reconciliation is load-bearing"));
        third.compile();

        assertTrue(third.readFile(".cursorrules").contains("Ledger"),
            "re-creating the signal file must opt the platform back in — opting out is a decision, "
                + "not a one-way door");
    }

    /**
     * Opting the granular directory back out. While {@code .claude/rules/} exists the aggregate
     * collapses to a scoped-rules index and stops carrying the guardrails itself (invariant 6);
     * once the directory is gone that index points at files nobody generates any more, so the
     * aggregate has to go back to stating them inline. An index left behind is worse than a stale
     * rule file: it is the only thing CLAUDE.md says about the class, and it names a path that
     * does not exist.
     */
    @Test
    void optingTheGranularDirectoryBackOut_returnsTheAggregateToInlineGuardrails(@TempDir Path dir)
            throws Exception {
        ProcessorTestHarness first = optedIn(dir, "CLAUDE.md");
        Files.createDirectories(dir.resolve(".claude/rules"));
        first.addSource("com.example.Ledger", ledger("Reconciliation is load-bearing"));
        first.compile();
        assertTrue(first.readFile("CLAUDE.md").contains(".claude/rules/"),
            "precondition: the aggregate collapsed to a scoped-rules index");

        deleteRecursively(dir.resolve(".claude/rules"));
        ProcessorTestHarness.awaitFilesystemTick(dir);
        VibeTagsLogger.shutdown();

        ProcessorTestHarness second = optedIn(dir, "CLAUDE.md");
        second.addSource("com.example.Ledger", ledger("Reconciliation is load-bearing"));
        second.compile();

        String claude = second.readFile("CLAUDE.md");
        assertTrue(claude.contains("Reconciliation is load-bearing"),
            "the aggregate must carry the guardrail itself once nothing else does:\n" + claude);
        assertFalse(claude.contains(".claude/rules/"),
            "and must stop pointing at a directory that no longer exists:\n" + claude);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort inside a temp dir
                }
            });
        }
    }

    // -----------------------------------------------------------------------
    // Through a reactor, including the whole-file formats
    // -----------------------------------------------------------------------

    @Test
    void removingOneAnnotationInAModule_dropsItFromEveryMergedRootFile(@TempDir Path root) throws Exception {
        setUpReactor(root);
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            twoLocked("com.example.cli", "Cli", "CLI entry point", "Keeper", "Still annotated"));

        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("com.example.cli.Cli"),
            "both modules must be present before the removal");
        assertTrue(Files.readString(root.resolve(".mentatconfig.json")).contains("com.example.cli.Cli"),
            "the whole-file JSON must carry both modules before the removal");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        // Cli loses its annotation; its sibling Keeper stays annotated, so the module still has
        // contributions and re-saves its sidecar. module-core is NOT recompiled — the realistic
        // case, and the one where a stale sidecar would keep a removed guardrail alive.
        compileModule(root, "module-cli", "com.example.cli.Cli",
            "package com.example.cli;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "public class Cli {}\n"
                + "@AILocked(reason = \"Still annotated\")\n"
                + "class Keeper {}\n");

        String claude = Files.readString(root.resolve("CLAUDE.md"));
        assertFalse(claude.contains("com.example.cli.Cli"),
            "the de-annotated element must leave the merged CLAUDE.md");
        assertTrue(claude.contains("com.example.core.IrNode"),
            "the module that was not recompiled must keep its guardrails — its sidecar is the only "
                + "record of them");

        String mentat = Files.readString(root.resolve(".mentatconfig.json"));
        assertFalse(mentat.contains("com.example.cli.Cli"),
            "the whole-file JSON is assembled from sidecars, so a removal has to reach it too");
        assertTrue(mentat.contains("com.example.core.IrNode"),
            "and must still carry the module that did not recompile");
    }

    /**
     * Pins the documented limitation rather than pretending it is not there: emptying a module of
     * <em>every</em> annotation leaves its last contribution in the merged files.
     *
     * <p>That is deliberate, and the reason is in {@code docs/MULTI-MODULE.md} — two preservation
     * guards stop a compile that saw no annotations from destroying content, because a module
     * compiled without the processor seeing its sources would otherwise wipe everyone else's
     * guardrails. The cost is this: the last annotation to leave a module leaves its rule behind
     * until {@code .vibetags-mod-<id>} is deleted.
     *
     * <p>Written as a passing test of the current behaviour, not an ignored test of the desired
     * one, so that changing it is a deliberate act with a failing test to update — and so the
     * escape hatch is discoverable from the test suite rather than only from prose.
     */
    @Test
    void emptyingAModuleEntirely_leavesItsLastContributionUntilTheSidecarIsDeleted(@TempDir Path root)
            throws Exception {
        setUpReactor(root);
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        compileModule(root, "module-cli", "com.example.cli.Cli",
            "package com.example.cli;\npublic class Cli {}\n");

        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("com.example.cli.Cli"),
            "documented behaviour: a module with no annotations left does not re-save its sidecar, "
                + "so its previous contribution stays in the merged output");

        // The documented escape hatch has to actually work.
        Files.delete(root.resolve(".vibetags-mod-module-cli"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));

        assertFalse(Files.readString(root.resolve("CLAUDE.md")).contains("com.example.cli.Cli"),
            "deleting the module's sidecar must retire its contribution — the documented remedy");
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("com.example.core.IrNode"),
            "and must not disturb the remaining module");
    }

    @Test
    void editingAnAnnotationInOneModule_updatesEveryMergedRootFile(@TempDir Path root) throws Exception {
        setUpReactor(root);
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "Original CLI reason"));

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "Rewritten CLI reason"));

        for (String file : new String[]{"CLAUDE.md", ".mentatconfig.json", ".pr_agent.toml"}) {
            String content = Files.readString(root.resolve(file));
            assertTrue(content.contains("Rewritten CLI reason"), file + " must carry the edited reason");
            assertFalse(content.contains("Original CLI reason"),
                file + " still carries the superseded reason from the module's previous compile");
            assertTrue(content.contains("Core IR node"),
                file + " must keep the untouched module's guardrails");
        }
    }

    /** Opting out in a reactor: the merged file stays gone even as other modules keep compiling. */
    @Test
    void optingOutInAReactor_survivesEveryOtherModulesCompile(@TempDir Path root) throws Exception {
        setUpReactor(root);
        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"));
        compileModule(root, "module-cli", "com.example.cli.Cli",
            locked("com.example.cli", "Cli", "CLI entry point"));
        assertTrue(Files.exists(root.resolve(".mentatconfig.json")), "opted in to begin with");

        Files.delete(root.resolve(".mentatconfig.json"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        compileModule(root, "module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node, revised"));

        assertFalse(Files.exists(root.resolve(".mentatconfig.json")),
            "a platform opted out at the reactor root must stay out when any module recompiles");
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("Core IR node, revised"),
            "the platforms still opted in must keep updating");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ProcessorTestHarness optedIn(Path dir, String... optIns) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        for (String optIn : optIns) {
            h.touchOptIn(optIn);
        }
        return h;
    }

    private static String ledger(String reason) {
        return locked("com.example", "Ledger", reason);
    }

    /** One annotated public type plus an annotated package-private sibling in the same file. */
    private static String twoLocked(String pkg, String type, String reason,
                                    String sibling, String siblingReason) {
        return locked(pkg, type, reason)
            + "@AILocked(reason = \"" + siblingReason + "\")\n"
            + "class " + sibling + " {}\n";
    }

    private static String locked(String pkg, String type, String reason) {
        return "package " + pkg + ";\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"" + reason + "\")\n"
            + "public class " + type + " {}\n";
    }

    /** Opt-in files at the shared root, covering a marker file and both whole-file formats. */
    private static void setUpReactor(Path root) throws IOException {
        Files.createDirectories(root.resolve("module-core"));
        Files.createDirectories(root.resolve("module-cli"));
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve(".mentatconfig.json"));
        Files.createFile(root.resolve(".pr_agent.toml"));
    }

    /** One module's compile into the shared reactor root, as a reactor pass would do it. */
    private static void compileModule(Path root, String module, String fqn, String source)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
        VibeTagsLogger.shutdown();
    }
}
