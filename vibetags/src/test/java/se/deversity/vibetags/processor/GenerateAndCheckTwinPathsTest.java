package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.SupportedAnnotationTypes;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The paths {@code generateFiles()} and {@code checkFiles()} both take, and the generation paths no
 * other test reached.
 *
 * <p>Check mode promises the verdict generation would reach, which it keeps by computing the same
 * things generation computes: the round's targets, the content, and this module's sidecar. These
 * cases pin the parts of that twin that had no test (a module's own opt-in file, a round that found
 * no annotation), so the two can share code without a branch of either going unseen, and the two
 * generation failures that had none (a sidecar that cannot be saved, a write that throws).
 */
@Tag("e2e")
class GenerateAndCheckTwinPathsTest {

    private static final String CHECK = "-Avibetags.check=true";

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    // ------------------------------------------------------------------ check mode

    /** A module's own opt-in file is generated from the module's own content, so check mode has to verify it too. */
    @Test
    void checkModeVerifiesAModulesOwnFile() throws IOException {
        touch(root.resolve("CLAUDE.md"));
        touch(root.resolve("module-core/CLAUDE.md"));
        compileModule("module-core", "com.example.core.IrNode", locked("com.example.core", "IrNode", "Core IR node"));
        assertTrue(Files.readString(root.resolve("module-core/CLAUDE.md")).contains("Core IR node"),
            "precondition: generation wrote the module's own file");

        List<String> inSync = errors(compileModule("module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"), CHECK));
        assertTrue(inSync.isEmpty(), "freshly generated files must pass the check: " + inSync);

        Files.writeString(root.resolve("module-core/CLAUDE.md"),
            "<!-- VIBETAGS-START -->\nstale\n<!-- VIBETAGS-END -->\n", StandardCharsets.UTF_8);
        List<String> drifted = errors(compileModule("module-core", "com.example.core.IrNode",
            locked("com.example.core", "IrNode", "Core IR node"), CHECK));
        assertTrue(drifted.stream().anyMatch(e -> e.contains("module-core/CLAUDE.md")),
            "a stale module-scoped file must be named as drift: " + drifted);
        assertEquals("<!-- VIBETAGS-START -->\nstale\n<!-- VIBETAGS-END -->\n",
            Files.readString(root.resolve("module-core/CLAUDE.md")), "check mode must not write");
    }

    /**
     * A module shown all of its sources with no annotation left retires its contribution (#781).
     * Generation saves the emptied sidecar over the old one; check mode reads the set as it would be
     * after that save, so it reports the file generation would rewrite.
     */
    @Test
    void checkModeReportsTheWithdrawalWhenAModulesLastAnnotationIsRemoved() throws Exception {
        touch(root.resolve("CLAUDE.md"));
        compileModule("module-core", "com.example.core.IrNode", locked("com.example.core", "IrNode", "Core IR node"));
        compileModule("module-cli", "com.example.cli.Cli", locked("com.example.cli", "Cli", "CLI entry point"));
        String before = Files.readString(root.resolve("CLAUDE.md"));
        assertTrue(before.contains("com.example.cli.Cli"), "precondition: the module contributes");
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        List<String> drifted = errors(compileModule("module-cli", "com.example.cli.Cli",
            "package com.example.cli;\npublic class Cli {}\n", CHECK));

        assertTrue(drifted.stream().anyMatch(e -> e.contains("CLAUDE.md")),
            "generation would withdraw the removed lock from CLAUDE.md, so check mode must say so: " + drifted);
        assertEquals(before, Files.readString(root.resolve("CLAUDE.md")), "check mode must not write");
    }

    /**
     * A module that has never had an annotation, compiled in a reactor where another module has:
     * the round finds nothing and retires nothing, so check mode compares against the sidecars as
     * they are, and the merged root file it would write is the one already on disk.
     */
    @Test
    void checkModePassesForAModuleWithNoAnnotationsInAnAnnotatedReactor() throws IOException {
        touch(root.resolve("CLAUDE.md"));
        compileModule("module-core", "com.example.core.IrNode", locked("com.example.core", "IrNode", "Core IR node"));
        String before = Files.readString(root.resolve("CLAUDE.md"));

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileModule("module-plain",
            "com.example.plain.Plain", "package com.example.plain;\npublic class Plain {}\n", CHECK);

        assertTrue(errors(diagnostics).isEmpty(),
            "a module with nothing to contribute leaves the merged file as it is: " + errors(diagnostics));
        assertTrue(diagnostics.stream().anyMatch(d -> d.getMessage(null).contains("check passed")),
            "the verdict must still be given: " + diagnostics);
        assertEquals(before, Files.readString(root.resolve("CLAUDE.md")), "check mode must not write");
        try (Stream<Path> files = Files.list(root)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".vibetags-mod-module-plain")),
                "check mode must not save a sidecar for the module either");
        }
    }

    // ------------------------------------------------------------------ generation failures

    /**
     * A sidecar that cannot be saved costs this round its multi-module aggregation, and nothing else:
     * the root files are still written from this round's content, and the build is told why.
     */
    @Test
    void aSidecarThatCannotBeSavedIsReportedAndTheRootFilesAreStillWritten() throws Exception {
        touch(root.resolve("CLAUDE.md"));
        ProcessorTestHarness first = new ProcessorTestHarness(root, false);
        first.writeSourceFile("src/main/java/com/example/A.java", locked("com.example", "A", "first reason"));
        first.compile();
        Path sidecar;
        try (Stream<Path> files = Files.list(root)) {
            sidecar = files.filter(p -> p.getFileName().toString().startsWith(".vibetags-mod-")
                    && !p.getFileName().toString().endsWith(".tmp"))
                .findFirst().orElseThrow(() -> new AssertionError("precondition: the first round saved a sidecar"));
        }
        // A non-empty directory where the sidecar goes: every rename onto it fails.
        Files.delete(sidecar);
        Files.createDirectories(sidecar.resolve("blocker"));
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        ProcessorTestHarness second = new ProcessorTestHarness(root, false);
        second.writeSourceFile("src/main/java/com/example/A.java", locked("com.example", "A", "second reason"));
        List<Diagnostic<? extends JavaFileObject>> diagnostics = second.compileReturningDiagnostics();

        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.NOTE
                && d.getMessage(null).contains("Could not save module sidecar")),
            "the lost aggregation must be reported: " + diagnostics);
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("second reason"),
            "the root file must still be written from this round's content");
    }

    /** A processor whose every file write throws, standing in for a bug inside the parallel write phase. */
    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    static final class ThrowingWriteProcessor extends AIGuardrailProcessor {
        @Override
        public boolean writeFileIfChanged(String path, String content, boolean hasNewRules) {
            throw new IllegalStateException("write-boom");
        }
    }

    /**
     * A failure inside the parallel write is carried out of the pool and reaches process()'s guard,
     * which downgrades it to a warning: the build is not failed, and the cause is named.
     */
    @Test
    void aWriteThatThrowsInTheParallelPhaseReachesTheGuardAsAWarning() throws IOException {
        touch(root.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/A.java", locked("com.example", "A", "reason"));

        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            harness.compileWithReturningDiagnostics(new ThrowingWriteProcessor());

        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.WARNING
                && d.getMessage(null).contains("guardrail generation failed")
                && d.getMessage(null).contains("parallel file write failed")),
            "the write failure must reach the guard with its cause: " + diagnostics);
        assertFalse(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            "and must not fail the build: " + diagnostics);
    }

    // ------------------------------------------------------------------ helpers

    /** Compiles one module (a real pom.xml and a file-backed source) into the shared root. */
    private List<Diagnostic<? extends JavaFileObject>> compileModule(String module, String fqn, String source,
                                                                    String... options) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        return harness.compileReturningDiagnostics(options);
    }

    private static String locked(String pkg, String type, String reason) {
        return "package " + pkg + ";\n"
            + "import se.deversity.vibetags.annotations.AILocked;\n"
            + "@AILocked(reason = \"" + reason + "\")\n"
            + "public class " + type + " {}\n";
    }

    private static List<String> errors(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .toList();
    }

    private static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
    }
}
