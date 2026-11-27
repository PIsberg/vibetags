package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.Messager;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the top-level exception guard in {@link AIGuardrailProcessor#process}: an unexpected
 * runtime failure during guardrail generation must NEVER fail the consumer's compilation. It is
 * downgraded to a {@code javac} WARNING and the processor still returns normally so peer
 * annotation processors continue to run.
 *
 * <p>The fault is injected by subclassing the processor and overriding the package-private
 * {@code validateAnnotations(...)} — which runs inside the guarded region of {@code process()} —
 * to throw a {@link RuntimeException}. Without the guard, javac would report the processor as
 * having "thrown an exception" and abort the build with an ERROR.
 */
class ProcessorFailureGuardTest {

    private static final String BOOM = "vibetags-fault-injection-boom";

    /** A processor that always blows up inside the guarded region of process(). */
    // @SupportedAnnotationTypes is not @Inherited, so the subclass must re-declare it or javac
    // never invokes the processor. getSupportedSourceVersion() is a plain method override on
    // AIGuardrailProcessor (not annotation-driven), so it applies here without restating it.
    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    static final class FaultyProcessor extends AIGuardrailProcessor {
        // process() calls the 4-arg overload (present-annotation FQNs plus the collector's
        // round index); override that one so the fault fires inside the guarded region. The 3-arg
        // overload delegates here, so this covers both routes.
        @Override
        void validateAnnotations(Messager messager, RoundEnvironment roundEnv, java.util.Set<String> presentFqns,
                                 java.util.Map<Class<? extends java.lang.annotation.Annotation>,
                                     java.util.Set<? extends javax.lang.model.element.Element>> roundIndex) {
            throw new IllegalStateException(BOOM);
        }
    }

    /** A processor whose init() fails part way, after the log is open (#1147). */
    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    static final class FaultyInitProcessor extends AIGuardrailProcessor {
        @Override
        se.deversity.vibetags.processor.internal.ModuleSidecar.OnRecord sidecarsOnRecord() {
            throw new IllegalStateException(BOOM);
        }
    }

    /**
     * #1147. init() reads options, the root, the role and mirror configs, the write cache and the
     * sidecars on record, and had no guard: one RuntimeException there failed the consumer's
     * compilation as a processor crash, the outcome process()'s guard exists to rule out. A corrupt
     * cache row did it once (#1146).
     */
    @Test
    void aFailureInInitIsAWarningAndTheProcessorStandsDown(@TempDir Path tempDir) throws IOException {
        Path claude = tempDir.resolve("CLAUDE.md");
        Files.writeString(claude, "Hand-written project notes.\n");
        byte[] before = Files.readAllBytes(claude);
        ProcessorTestHarness harness = new ProcessorTestHarness(tempDir, false);
        harness.writeSourceFile("src/main/java/com/example/Locked.java", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "core logic")
            public class Locked {}
            """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            harness.compileWithReturningDiagnostics(new FaultyInitProcessor());

        assertFalse(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            () -> "a failure in init() must not fail the consumer's build: " + diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.WARNING
                && d.getMessage(null).contains("guardrail generation is off for this compilation")
                && d.getMessage(null).contains(BOOM)),
            () -> "the failure is named in one warning: " + diagnostics);
        assertArrayEquals(before, Files.readAllBytes(claude),
            "a processor that did not start must not write: CLAUDE.md keeps its bytes");
        assertFalse(diagnostics.stream().anyMatch(d -> d.getMessage(null).contains("Generating files")),
            () -> "a half-initialised processor must not run generation: " + diagnostics);
        assertNull(VibeTagsLogger.currentFor(tempDir), "the log init() opened is released");
    }

    /** Check mode fails closed here as it does in process(): a check that could not start has not passed. */
    @Test
    void inCheckModeAFailureInInitFailsTheBuild(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(tempDir, false);
        harness.writeSourceFile("src/main/java/com/example/Locked.java", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "core logic")
            public class Locked {}
            """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            harness.compileWithReturningDiagnostics(new FaultyInitProcessor(), "-Avibetags.check=true");

        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                && d.getMessage(null).contains("check mode could not start")),
            () -> "a check that could not start must fail the build: " + diagnostics);
        assertFalse(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                && d.getMessage(null).contains("threw an uncaught exception")),
            () -> "the failure is VibeTags' own ERROR, not javac's processor crash: " + diagnostics);
    }

    @Test
    void runtimeFailure_isDowngradedToWarning_andBuildSucceeds(@TempDir Path tempDir) throws IOException {
        // An opt-in file so at least one service is active; the fault fires before generation anyway.
        Files.createFile(tempDir.resolve("CLAUDE.md"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JavaCompiler unavailable — run tests with a JDK, not a JRE");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok;
        StandardJavaFileManager fm = ProcessorTestHarness.sharedFileManager();
        Path classOut = tempDir.resolve("classes");
        Files.createDirectories(classOut);
        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));

        JavaFileObject source = new StringSource(
            "com/example/Locked.java",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"core logic\")\n"
                + "public class Locked {}\n");

        List<String> options = List.of(
            "-classpath", System.getProperty("java.class.path"),
            "-proc:only",
            "-Avibetags.root=" + tempDir.toAbsolutePath());

        JavaCompiler.CompilationTask task = compiler.getTask(
            null, fm, diagnostics, options, null, List.of(source));
        task.setProcessors(List.of(new FaultyProcessor()));
        ok = task.call();

        // 1. The build must NOT fail because of the processor blowing up.
        assertTrue(ok, "Compilation should succeed: a guardrail failure must never break the build");
        assertFalse(
            diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            "No ERROR diagnostics expected — the failure must be swallowed, not surfaced as an error");

        // 2. The failure must be surfaced as a WARNING that points at guardrail generation.
        assertTrue(
            diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .anyMatch(d -> d.getMessage(null).contains("guardrail generation failed")),
            "Expected a WARNING noting that guardrail generation failed and was skipped");
    }

    /**
     * Check mode is the one place the guard fails closed: a build that asked VibeTags to verify its
     * guardrail files must not pass because the verification itself blew up.
     */
    @Test
    void inCheckModeARuntimeFailureFailsTheBuildWithAnError(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(tempDir, false);
        harness.writeSourceFile("src/main/java/com/example/Locked.java", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "core logic")
            public class Locked {}
            """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            harness.compileWithReturningDiagnostics(new FaultyProcessor(), "-Avibetags.check=true");

        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                && d.getMessage(null).contains("check mode could not verify guardrail files")),
            () -> "a check that could not run must fail the build, not pass it: " + diagnostics);
        assertFalse(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.WARNING
                && d.getMessage(null).contains("guardrail generation failed")),
            () -> "check mode must not downgrade the failure to the advisory warning: " + diagnostics);
    }

    /**
     * #1143. A failure in a collection round was reported as "generation failed and was skipped",
     * and then the final round generated anyway, from a model the failed round may not have
     * finished. The files must be left as this build found them, the way an errorRaised() build
     * leaves them, so the warning is true.
     */
    @Test
    void aFailedCollectionRoundLeavesEveryFileAsTheBuildFoundIt(@TempDir Path tempDir) throws IOException {
        Path claude = tempDir.resolve("CLAUDE.md");
        Files.writeString(claude, "Hand-written project notes.\n");
        byte[] before = Files.readAllBytes(claude);
        ProcessorTestHarness harness = new ProcessorTestHarness(tempDir, false);
        harness.writeSourceFile("src/main/java/com/example/Locked.java", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "core logic")
            public class Locked {}
            """);

        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            harness.compileWithReturningDiagnostics(new FaultyProcessor());

        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.WARNING
                && d.getMessage(null).contains("guardrail generation failed and was skipped")),
            () -> "precondition: the collection round's fault was downgraded: " + diagnostics);
        assertArrayEquals(before, Files.readAllBytes(claude),
            "the warning said generation was skipped, so CLAUDE.md must keep its bytes");
        try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
            assertEquals(List.of(), files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(".vibetags-mod-")).toList(),
                "no sidecar may record a model the failed round did not finish");
        }
        assertFalse(diagnostics.stream().anyMatch(d -> d.getMessage(null).contains("updated - CLAUDE.md")),
            () -> "nothing may be reported as written: " + diagnostics);
        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.NOTE
                && d.getMessage(null).contains("guardrail files left untouched")),
            () -> "the final round says why it wrote nothing: " + diagnostics);
        assertNull(VibeTagsLogger.currentFor(tempDir), "the final round releases the log");
    }

    @Test
    @ResourceLock(ParallelReactorGenerationTest.SEAM_LOCK)
    void aFailureDuringGenerationStillReleasesTheLog(@TempDir Path tempDir) throws IOException {
        // #1075. A RuntimeException in the final round skips every shutdown on the normal exit
        // paths of generation, so the guard must release the log itself: otherwise the appender
        // stays attached and vibetags.log stays open (locked on Windows) for the rest of a daemon.
        Files.createFile(tempDir.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(tempDir, false);
        harness.writeSourceFile("src/main/java/com/example/Locked.java", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "core logic")
            public class Locked {}
            """);
        String thread = Thread.currentThread().getName();
        AIGuardrailProcessor.setAfterSidecarRead(() -> {
            if (thread.equals(Thread.currentThread().getName())) {
                throw new IllegalStateException(BOOM);
            }
        });
        List<Diagnostic<? extends JavaFileObject>> diagnostics;
        try {
            diagnostics = harness.compileWithReturningDiagnostics(new AIGuardrailProcessor());
        } finally {
            AIGuardrailProcessor.setAfterSidecarRead(null);
        }

        assertTrue(diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.WARNING
                && d.getMessage(null).contains("guardrail generation failed")),
            () -> "precondition: the fault fired inside generation and was downgraded: " + diagnostics);
        try {
            assertNull(VibeTagsLogger.currentFor(tempDir),
                "the failed round left its logger configured, with its appender and file handle open");
        } finally {
            VibeTagsLogger.shutdown(tempDir);
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String content;

        StringSource(String path, String content) {
            super(URI.create("string:///" + path), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
