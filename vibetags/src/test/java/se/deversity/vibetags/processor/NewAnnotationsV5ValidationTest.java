package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that AnnotationValidator emits the expected warnings for v1.0.0 annotations.
 * Compiles in-memory sources and checks the diagnostics collected by the compiler.
 */
class NewAnnotationsV5ValidationTest {

    @TempDir
    static Path tempDir;

    private static List<String> warnings;

    @BeforeAll
    static void compileAndCollect() throws IOException {
        warnings = new ArrayList<>();

        Path classOut = tempDir.resolve("classes");
        Files.createDirectories(classOut);

        List<JavaFileObject> sources = List.of(
            // @AIIdempotent + @AIDraft — contradictory
            new StringSource("com/example/v5/IdempotentDraftConflicted.java",
                "package com.example.v5;\n"
                    + "import se.deversity.vibetags.annotations.AIIdempotent;\n"
                    + "import se.deversity.vibetags.annotations.AIDraft;\n"
                    + "@AIIdempotent(reason = \"Safe to retry\")\n"
                    + "@AIDraft(instructions = \"Still being written\")\n"
                    + "public class IdempotentDraftConflicted {}\n")
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));
            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-proc:only",
                "-Avibetags.root=" + tempDir.toAbsolutePath()
            );
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, sources);
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            task.call();
        }

        for (var d : diagnostics.getDiagnostics()) {
            warnings.add(d.getMessage(Locale.ROOT));
        }
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void warns_aiIdempotentAndAiDraft_combination() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIIdempotent") && w.contains("@AIDraft") && w.contains("contradictory")),
            "Expected warning about @AIIdempotent + @AIDraft. Warnings: " + warnings);
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String name, String code) {
            super(URI.create("string:///" + name), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
