package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the final round skips all guardrail generation when the compilation has already
 * raised errors ({@link RoundEnvironment#errorRaised()}).
 *
 * <p>Why this matters: an error raised during an earlier round (a peer annotation processor
 * reporting {@code Diagnostic.Kind.ERROR}, or a generated source failing to compile) aborts any
 * remaining source-generation rounds, so the collected annotation set can be incomplete. Writing
 * output from that state on a build that is failing anyway would overwrite committed guardrail
 * files, overwrite this module's sidecar with a shrunken contribution, delete granular rule files
 * as "orphans", and record a fingerprint for a build that never succeeded. A failed compile must
 * leave every VibeTags artifact exactly as it found it.
 */
class ErrorRaisedRoundGuardTest {

    /**
     * A peer processor that fails the build in the first round, the way a real annotation
     * processor reports an invalid annotation. VibeTags itself must survive this untouched.
     */
    @SupportedAnnotationTypes("*")
    static final class ErrorRaisingPeerProcessor extends AbstractProcessor {
        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (!roundEnv.processingOver()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "peer-processor-simulated-failure");
            }
            return false;
        }
    }

    @AfterEach
    void releaseLogFile() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void errorRaised_finalRound_leavesFilesSidecarsAndCacheUntouched(@TempDir Path tempDir)
            throws IOException {
        // Opted-in, committed guardrail file with existing content — exactly what a failed build
        // must not rewrite.
        String priorContent = "# hand-authored heading\n";
        Files.writeString(tempDir.resolve("CLAUDE.md"), priorContent, StandardCharsets.UTF_8);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ok = compileWithFailingPeer(tempDir, diagnostics);

        assertFalse(ok, "The peer processor's ERROR must fail the compilation");

        assertEquals(priorContent, Files.readString(tempDir.resolve("CLAUDE.md"), StandardCharsets.UTF_8),
            "CLAUDE.md must not be rewritten by a compilation that raised errors");
        assertFalse(Files.exists(tempDir.resolve(".vibetags-cache")),
            "No fingerprint/cache state may be recorded for a failed compilation");
        try (var stream = Files.list(tempDir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith(".vibetags-mod-")),
                "No module sidecar may be written for a failed compilation");
        }

        assertTrue(
            diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .anyMatch(d -> d.getMessage(null).contains("left untouched")),
            "Expected a NOTE explaining that guardrail files were left untouched");
    }

    private static boolean compileWithFailingPeer(Path tempDir,
                                                  DiagnosticCollector<JavaFileObject> diagnostics)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "JavaCompiler unavailable — run tests with a JDK, not a JRE");

        try (StandardJavaFileManager fm = ProcessorTestHarness.sharedFileManager()) {
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
            task.setProcessors(List.of(new AIGuardrailProcessor(), new ErrorRaisingPeerProcessor()));
            return task.call();
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
