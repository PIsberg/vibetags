package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for opt-in check mode ({@code -Avibetags.check=true}): the processor verifies that
 * every generated guardrail file is in sync with the annotations and fails the build on
 * drift — without writing anything to disk.
 *
 * <p>Check mode is strictly opt-in: without the option, behaviour is the normal generate
 * path (covered by the rest of the suite).
 */
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

    /** Writes a minimal valid {@code .vibetags-mod-<id>} sidecar with one service body. */
    private static void writeSiblingSidecar(Path root, String moduleId, String service, String body)
            throws IOException {
        String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String content = "# version=2\n"
            + "moduleId=" + moduleId + "\n"
            + "modulePath=\n"
            + service + "=" + encoded + "\n";
        Files.writeString(root.resolve(".vibetags-mod-" + moduleId), content, StandardCharsets.UTF_8);
    }
}
