package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.Messager;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    // @SupportedAnnotationTypes / @SupportedSourceVersion are not @Inherited, so the subclass
    // must re-declare them or javac never invokes the processor.
    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    static final class FaultyProcessor extends AIGuardrailProcessor {
        // process() calls the 3-arg overload (passing the present-annotation FQNs); override that
        // one so the fault fires inside the guarded region.
        @Override
        void validateAnnotations(Messager messager, RoundEnvironment roundEnv, java.util.Set<String> presentFqns) {
            throw new IllegalStateException(BOOM);
        }
    }

    @Test
    void runtimeFailure_isDowngradedToWarning_andBuildSucceeds(@TempDir Path tempDir) throws IOException {
        // An opt-in file so at least one service is active; the fault fires before generation anyway.
        Files.createFile(tempDir.resolve("CLAUDE.md"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JavaCompiler unavailable — run tests with a JDK, not a JRE");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
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
        }

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
