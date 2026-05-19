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
 * Verifies that AnnotationValidator emits the expected warnings for the v0.9.0 annotations.
 * Compiles in-memory sources and checks the diagnostics collected by the compiler.
 */
class NewAnnotationsV3ValidationTest {

    @TempDir
    static Path tempDir;

    private static List<String> warnings;

    @BeforeAll
    static void compileAndCollect() throws IOException {
        warnings = new ArrayList<>();

        Path classOut = tempDir.resolve("classes");
        Files.createDirectories(classOut);

        List<JavaFileObject> sources = List.of(
            // Non-final field on @AIImmutable class
            new StringSource("com/example/v3/MutableImmutable.java",
                "package com.example.v3;\n"
                    + "import se.deversity.vibetags.annotations.AIImmutable;\n"
                    + "@AIImmutable\n"
                    + "public class MutableImmutable {\n"
                    + "    public int counter;\n"
                    + "    public final int constant = 0;\n"
                    + "    public static int globalCounter;\n"
                    + "}\n"),

            // @AIDeprecated + @AILocked
            new StringSource("com/example/v3/Conflicted.java",
                "package com.example.v3;\n"
                    + "import se.deversity.vibetags.annotations.AIDeprecated;\n"
                    + "import se.deversity.vibetags.annotations.AILocked;\n"
                    + "@AIDeprecated(replacedBy = \"x.New\")\n"
                    + "@AILocked\n"
                    + "public class Conflicted {}\n"),

            // @AIThreadSafe(IMMUTABLE) + @AIImmutable
            new StringSource("com/example/v3/Redundant.java",
                "package com.example.v3;\n"
                    + "import se.deversity.vibetags.annotations.AIImmutable;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadSafe;\n"
                    + "@AIImmutable\n"
                    + "@AIThreadSafe(strategy = AIThreadSafe.Strategy.IMMUTABLE)\n"
                    + "public final class Redundant {\n"
                    + "    private final int x = 1;\n"
                    + "    public int getX() { return x; }\n"
                    + "}\n"),

            // @AIObservability with no metrics/traces/logs
            new StringSource("com/example/v3/EmptyObservability.java",
                "package com.example.v3;\n"
                    + "import se.deversity.vibetags.annotations.AIObservability;\n"
                    + "@AIObservability(note = \"says nothing\")\n"
                    + "public class EmptyObservability {}\n"),

            // @AIRegulation with blank standard
            new StringSource("com/example/v3/BlankRegulation.java",
                "package com.example.v3;\n"
                    + "import se.deversity.vibetags.annotations.AIRegulation;\n"
                    + "@AIRegulation(standard = \"   \")\n"
                    + "public class BlankRegulation {}\n"),

            // @AIObservability with only traces (no metrics, no logs) — must NOT warn.
            // Covers the traces.length==0 false branch in AnnotationValidator's AND chain.
            new StringSource("com/example/v3/TracesOnlyObservability.java",
                "package com.example.v3;\n"
                    + "import se.deversity.vibetags.annotations.AIObservability;\n"
                    + "public class TracesOnlyObservability {\n"
                    + "    @AIObservability(traces = {\"span.operation\"})\n"
                    + "    public void trace() {}\n"
                    + "}\n"),

            // @AIObservability with only logs (no metrics, no traces) — must NOT warn.
            // Covers the logs.length==0 false branch in AnnotationValidator's AND chain.
            new StringSource("com/example/v3/LogsOnlyObservability.java",
                "package com.example.v3;\n"
                    + "import se.deversity.vibetags.annotations.AIObservability;\n"
                    + "public class LogsOnlyObservability {\n"
                    + "    @AIObservability(logs = {\"app.query.execute\"})\n"
                    + "    public void logOp() {}\n"
                    + "}\n")
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
    void warns_aiImmutable_withNonFinalInstanceField() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIImmutable") && w.contains("counter") && w.contains("not final")),
            "Expected warning about non-final field 'counter' on @AIImmutable class. Warnings: " + warnings);
    }

    @Test
    void doesNotWarn_aiImmutable_withStaticOrFinalField() {
        assertTrue(warnings.stream().noneMatch(w ->
                w.contains("@AIImmutable") && (w.contains("constant") || w.contains("globalCounter"))),
            "No warning expected for static or final fields on @AIImmutable class. Warnings: " + warnings);
    }

    @Test
    void warns_aiDeprecatedAndAiLocked_combination() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIDeprecated") && w.contains("@AILocked")),
            "Expected warning about @AIDeprecated + @AILocked combination. Warnings: " + warnings);
    }

    @Test
    void warns_aiThreadSafeImmutableAndAiImmutable_combination() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIThreadSafe") && w.contains("@AIImmutable")),
            "Expected warning about @AIThreadSafe(IMMUTABLE) + @AIImmutable combination. Warnings: " + warnings);
    }

    @Test
    void warns_aiObservability_withNoSignals() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIObservability") && w.contains("ignored")),
            "Expected warning about empty @AIObservability annotation. Warnings: " + warnings);
    }

    @Test
    void doesNotWarn_aiObservability_whenTracesProvided() {
        assertTrue(warnings.stream().noneMatch(w ->
                w.contains("TracesOnlyObservability") && w.contains("ignored")),
            "No warning expected when @AIObservability has traces. Warnings: " + warnings);
    }

    @Test
    void doesNotWarn_aiObservability_whenLogsProvided() {
        assertTrue(warnings.stream().noneMatch(w ->
                w.contains("LogsOnlyObservability") && w.contains("ignored")),
            "No warning expected when @AIObservability has logs. Warnings: " + warnings);
    }

    @Test
    void warns_aiRegulation_withBlankStandard() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIRegulation") && w.contains("standard")),
            "Expected warning about blank standard on @AIRegulation. Warnings: " + warnings);
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
