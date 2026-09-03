package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for opt-in check mode ({@code -Avibetags.check=true}): the processor verifies that
 * every generated guardrail file is in sync with the annotations and fails the build on
 * drift — without writing anything to disk.
 *
 * <p>Check mode is strictly opt-in: without the option, behaviour is the normal generate
 * path (covered by the rest of the suite).
 */
@Tag("e2e")
class CheckModeTest {

    private static final String CHECK_OPTION = "-Avibetags.check=true";
    private static final String CHECK_FAILED = "VibeTags: check failed";

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    // -----------------------------------------------------------------------
    // End-to-end: pass / fail verdicts
    // -----------------------------------------------------------------------

    @Test
    void checkPasses_whenFilesAreInSync(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A", lockedSource("original reason"));
        h1.compile();

        Path cursorRules = tmp.resolve(".cursorrules");
        Path claudeMd = tmp.resolve("CLAUDE.md");
        long cursorMtime = Files.getLastModifiedTime(cursorRules).toMillis();
        long claudeMtime = Files.getLastModifiedTime(claudeMd).toMillis();

        ProcessorTestHarness.awaitFilesystemTick(tmp);

        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A", lockedSource("original reason"));
        List<Diagnostic<? extends JavaFileObject>> diags = h2.compileReturningDiagnostics(CHECK_OPTION);

        assertTrue(errors(diags).isEmpty(),
            "check must not raise errors when files are in sync, but got: " + errors(diags));
        assertEquals(cursorMtime, Files.getLastModifiedTime(cursorRules).toMillis(),
            ".cursorrules must not be rewritten by check mode");
        assertEquals(claudeMtime, Files.getLastModifiedTime(claudeMd).toMillis(),
            "CLAUDE.md must not be rewritten by check mode");
    }

    @Test
    void checkFails_whenAnnotationChanged_andLeavesFilesUntouched(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A", lockedSource("original reason"));
        h1.compile();

        Path cursorRules = tmp.resolve(".cursorrules");
        long mtimeBefore = Files.getLastModifiedTime(cursorRules).toMillis();

        ProcessorTestHarness.awaitFilesystemTick(tmp);

        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A", lockedSource("completely different reason"));
        List<Diagnostic<? extends JavaFileObject>> diags = h2.compileReturningDiagnostics(CHECK_OPTION);

        assertFalse(errors(diags).isEmpty(),
            "check must fail when annotations changed but files were not regenerated");
        assertTrue(errors(diags).stream().anyMatch(m -> m.contains(CHECK_FAILED)),
            "error must carry the check-failed message, got: " + errors(diags));

        String cursorContent = h2.readFile(".cursorrules");
        assertTrue(cursorContent.contains("original reason"),
            "check mode must not modify generated files — .cursorrules should still hold the old content");
        assertFalse(cursorContent.contains("completely different reason"),
            "check mode must not write the new content");
        assertEquals(mtimeBefore, Files.getLastModifiedTime(cursorRules).toMillis(),
            ".cursorrules mtime must be unchanged after a failed check");
    }

    @Test
    void checkFails_whenGeneratedFileManuallyEdited(@TempDir Path tmp) throws Exception {
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A", lockedSource("original reason"));
        h1.compile();

        // Simulate a developer (or merge conflict) corrupting the generated block.
        Path cursorRules = tmp.resolve(".cursorrules");
        String corrupted = Files.readString(cursorRules, StandardCharsets.UTF_8)
            .replace("original reason", "tampered reason");
        Files.writeString(cursorRules, corrupted, StandardCharsets.UTF_8);

        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A", lockedSource("original reason"));
        List<Diagnostic<? extends JavaFileObject>> diags = h2.compileReturningDiagnostics(CHECK_OPTION);

        assertTrue(errors(diags).stream().anyMatch(m -> m.contains(CHECK_FAILED)),
            "check must fail when a generated file was hand-edited out of sync");
        assertTrue(h2.readFile(".cursorrules").contains("tampered reason"),
            "check mode must not repair the file — that is the normal compile's job");
    }

    @Test
    void checkOnFreshProject_failsAndWritesNothing(@TempDir Path tmp) throws Exception {
        // Opt-in files exist (empty) but no compile has populated them yet: a normal compile
        // WOULD write them all, so check must fail — and must not create anything itself.
        ProcessorTestHarness h = new ProcessorTestHarness(tmp);
        h.addSource("com.example.A", lockedSource("some reason"));
        List<Diagnostic<? extends JavaFileObject>> diags = h.compileReturningDiagnostics(CHECK_OPTION);

        assertTrue(errors(diags).stream().anyMatch(m -> m.contains(CHECK_FAILED)),
            "check on a never-generated project must fail");
        assertEquals("", h.readFile("CLAUDE.md"), "CLAUDE.md must stay empty in check mode");
        assertEquals("", h.readFile(".cursorrules"), ".cursorrules must stay empty in check mode");
        assertFalse(Files.exists(tmp.resolve(".vibetags-cache")),
            "check mode must not create the write cache");
        try (var stream = Files.list(tmp)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith(".vibetags-mod-")),
                "check mode must not write module sidecars");
        }
    }

    @Test
    void checkPasses_inMultiModuleMerge(@TempDir Path tmp) throws Exception {
        // First compile establishes this module's sidecar and output.
        ProcessorTestHarness h1 = new ProcessorTestHarness(tmp);
        h1.addSource("com.example.A", lockedSource("module-a reason"));
        h1.compile();

        // Inject a sibling module's sidecar, then recompile so the merged (sub-marker) output
        // lands on disk.
        writeSiblingSidecar(tmp, "zzz-sibling", "claude",
            "## Sibling guardrails\n- locked: com.sibling.Foo");
        ProcessorTestHarness h2 = new ProcessorTestHarness(tmp);
        h2.addSource("com.example.A", lockedSource("module-a reason"));
        h2.compile();
        assertTrue(h2.readFile("CLAUDE.md").contains("VIBETAGS-MODULE: zzz-sibling"),
            "precondition: merged multi-module output must be on disk");

        // Check mode must reproduce the same merge in memory and find no drift.
        ProcessorTestHarness h3 = new ProcessorTestHarness(tmp);
        h3.addSource("com.example.A", lockedSource("module-a reason"));
        List<Diagnostic<? extends JavaFileObject>> diags = h3.compileReturningDiagnostics(CHECK_OPTION);

        assertTrue(errors(diags).isEmpty(),
            "multi-module check must pass when merged files are in sync, but got: " + errors(diags));
    }

    // -----------------------------------------------------------------------
    // End-to-end: check mode must reproduce generation's cleanup, no more and no less
    // -----------------------------------------------------------------------

    /**
     * A reactor module round may not sweep the shared root's granular directory: on a cold clone
     * the sidecars are gitignored, so every sibling's committed rule file is unclaimed and a sweep
     * deletes them (issue #383). Generation learned that rule; check mode kept the unconditional
     * sweep, so the very same cold-clone round reported every sibling's rule file as drift and
     * failed a build in which nothing was wrong. A check verdict is only worth anything if it
     * reproduces generation exactly.
     */
    @Test
    void checkMode_onAColdCloneModuleRound_agreesWithGeneration(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".claude/rules"));
        compileModule(root, "module-a", "com.example.a.AlphaService", lockedIn("com.example.a", "AlphaService"));
        compileModule(root, "module-b", "com.example.b.BetaService", lockedIn("com.example.b", "BetaService"));
        Path bRule = root.resolve(".claude/rules/com-example-b-BetaService.md");
        assertTrue(Files.exists(bRule), "precondition: both modules' rule files exist");

        // Cold clone: no sidecars, no cache — the state a CI runner sees.
        deleteVibeTagsState(root);
        compileModule(root, "module-a", "com.example.a.AlphaService", lockedIn("com.example.a", "AlphaService"));
        assertTrue(Files.exists(bRule),
            "precondition: generation on a cold clone leaves the sibling's rule file alone (#383)");

        deleteVibeTagsState(root);
        List<String> errors = errors(checkModule(root, "module-a", "com.example.a.AlphaService",
            lockedIn("com.example.a", "AlphaService")));

        assertTrue(errors.isEmpty(),
            "check mode must agree with generation on a cold clone, but reported: " + errors);
        assertTrue(Files.exists(bRule), "and must not have touched the sibling's file either");
    }

    /**
     * The bound on the rule above, kept green through it. A sidecar whose module directory is gone
     * names the rule files that module wrote, and generation removes those on the next build of any
     * survivor. Check mode must report that removal as drift, or a departed module's stale rule
     * files pass check forever.
     */
    @Test
    void checkMode_reportsADepartedModulesRuleFileAsDrift(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".claude/rules"));
        compileModule(root, "module-a", "com.example.a.AlphaService", lockedIn("com.example.a", "AlphaService"));
        compileModule(root, "module-b", "com.example.b.BetaService", lockedIn("com.example.b", "BetaService"));
        Path bRule = root.resolve(".claude/rules/com-example-b-BetaService.md");
        assertTrue(Files.exists(bRule), "precondition: both modules' rule files exist");

        deleteRecursively(root.resolve("module-b"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        List<String> errors = errors(checkModule(root, "module-a", "com.example.a.AlphaService",
            lockedIn("com.example.a", "AlphaService")));

        assertTrue(errors.stream().anyMatch(m -> m.contains(CHECK_FAILED)
                && m.contains(".claude/rules/com-example-b-BetaService.md")),
            "generation would remove the departed module's rule file, so check mode must name it: " + errors);
        assertTrue(Files.exists(bRule), "reported, not removed: check mode writes nothing");
    }

    /**
     * Check mode touches nothing VibeTags manages, and the sidecars are the record of the build:
     * {@code readAll} prunes a sidecar whose module directory is gone, and check mode used to read
     * through it. One check-mode run after a module left the reactor deleted that module's sidecar,
     * and with it the only record of the rule files it wrote — so the next real build had nothing
     * to act on and the departed module's rule files stayed in the repository for good.
     */
    @Test
    void checkMode_doesNotPruneADepartedModulesSidecar(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".claude/rules"));
        compileModule(root, "module-a", "com.example.a.AlphaService", lockedIn("com.example.a", "AlphaService"));
        compileModule(root, "module-b", "com.example.b.BetaService", lockedIn("com.example.b", "BetaService"));
        Path bRule = root.resolve(".claude/rules/com-example-b-BetaService.md");
        Path bSidecar = root.resolve(".vibetags-mod-module-b");
        assertTrue(Files.exists(bRule) && Files.exists(bSidecar), "precondition: module-b's file and sidecar exist");

        deleteRecursively(root.resolve("module-b"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        checkModule(root, "module-a", "com.example.a.AlphaService", lockedIn("com.example.a", "AlphaService"));

        assertTrue(Files.exists(bSidecar),
            "check mode must not delete the departed module's sidecar: it writes nothing, and the "
                + "sidecar is the only record of which rule files that module wrote");

        compileModule(root, "module-a", "com.example.a.AlphaService", lockedIn("com.example.a", "AlphaService"));
        assertFalse(Files.exists(bRule),
            "the next real build must still be able to remove the departed module's rule file");
        assertFalse(Files.exists(bSidecar), "and it is the real build that prunes the sidecar");
    }

    // -----------------------------------------------------------------------
    // Unit: dry-run GuardrailFileWriter
    // -----------------------------------------------------------------------

    @Test
    void dryRunWriter_recordsWouldWrite_withoutTouchingDisk(@TempDir Path tmp) {
        GuardrailFileWriter writer = new GuardrailFileWriter("# header\n", null, null, null, true);
        Path target = tmp.resolve("subdir").resolve("CLAUDE.md");

        boolean wouldWrite = writer.writeFileIfChanged(target.toString(), "# new content", true);

        assertTrue(wouldWrite, "dry-run writer must report that it would write");
        assertFalse(Files.exists(target), "dry-run writer must not create the file");
        assertFalse(Files.exists(tmp.resolve("subdir")), "dry-run writer must not create parent directories");
        assertEquals(List.of(target.toString()), writer.dryRunChanges());
    }

    @Test
    void dryRunWriter_reportsNoChange_whenContentMatches(@TempDir Path tmp) throws IOException {
        // Write for real first, then verify the dry-run writer agrees nothing would change.
        GuardrailFileWriter realWriter = new GuardrailFileWriter("# header\n", null, null);
        Path target = tmp.resolve("CLAUDE.md");
        assertTrue(realWriter.writeFileIfChanged(target.toString(), "# same content", true));

        GuardrailFileWriter dryWriter = new GuardrailFileWriter("# header\n", null, null, null, true);
        boolean wouldWrite = dryWriter.writeFileIfChanged(target.toString(), "# same content", true);

        assertFalse(wouldWrite, "identical content must not register as drift");
        assertTrue(dryWriter.dryRunChanges().isEmpty());
    }

    @Test
    void defaultWriter_hasNoDryRunChanges(@TempDir Path tmp) {
        GuardrailFileWriter writer = new GuardrailFileWriter("# header\n", null, null);
        Path target = tmp.resolve("CLAUDE.md");
        assertTrue(writer.writeFileIfChanged(target.toString(), "# content", true));
        assertTrue(Files.exists(target), "non-dry-run writer must actually write");
        assertTrue(writer.dryRunChanges().isEmpty(), "dryRunChanges must stay empty outside dry-run mode");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String lockedSource(String reason) {
        return "package com.example;\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"" + reason + "\")\n"
            + "public class A {}\n";
    }

    private static List<String> errors(List<Diagnostic<? extends JavaFileObject>> diags) {
        return diags.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .collect(Collectors.toList());
    }

    private static String lockedIn(String pkg, String type) {
        return "package " + pkg + ";\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"" + type + " is load-bearing\")\n"
            + "public class " + type + " {}\n";
    }

    /** One module's compile into the shared reactor root, as a reactor pass would do it. */
    private static void compileModule(Path root, String module, String fqn, String source) throws IOException {
        moduleHarness(root, module, fqn, source).compile();
        VibeTagsLogger.shutdown();
    }

    /** The same round in check mode, returning its diagnostics. */
    private static List<Diagnostic<? extends JavaFileObject>> checkModule(
            Path root, String module, String fqn, String source) throws IOException {
        List<Diagnostic<? extends JavaFileObject>> diags =
            moduleHarness(root, module, fqn, source).compileReturningDiagnostics(CHECK_OPTION);
        VibeTagsLogger.shutdown();
        return diags;
    }

    private static ProcessorTestHarness moduleHarness(Path root, String module, String fqn, String source)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        return harness;
    }

    /** What a fresh clone lacks: the gitignored sidecars, cache and log. */
    private static void deleteVibeTagsState(Path root) throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            for (Path p : files.filter(f -> f.getFileName().toString().startsWith(".vibetags-")
                    || f.getFileName().toString().equals("vibetags.log")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Writes a minimal valid {@code .vibetags-mod-<id>} sidecar with one service body. */
    private static void writeSiblingSidecar(Path root, String moduleId, String service, String body)
            throws IOException {
        String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String content = "# version=2\n"
            + "moduleId=" + moduleId + "\n"
            + "modulePath=\n"
            + service + "=" + encoded + "\n"
            + "# end\n";
        Files.writeString(root.resolve(".vibetags-mod-" + moduleId), content, StandardCharsets.UTF_8);
    }
}
