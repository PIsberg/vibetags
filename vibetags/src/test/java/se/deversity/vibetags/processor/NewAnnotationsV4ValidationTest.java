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
 * Verifies that AnnotationValidator emits the expected warnings for the new V4 annotations.
 * Compiles in-memory sources and checks the diagnostics collected by the compiler.
 */
class NewAnnotationsV4ValidationTest {

    @TempDir
    static Path tempDir;

    private static List<String> warnings;

    @BeforeAll
    static void compileAndCollect() throws IOException {
        warnings = new ArrayList<>();

        Path classOut = tempDir.resolve("classes");
        Files.createDirectories(classOut);

        List<JavaFileObject> sources = List.of(
            // @AILegacyBridge + @AIDraft
            new StringSource("com/example/v4/LegacyBridgeDraftConflicted.java",
                "package com.example.v4;\n"
                    + "import se.deversity.vibetags.annotations.AILegacyBridge;\n"
                    + "import se.deversity.vibetags.annotations.AIDraft;\n"
                    + "@AILegacyBridge\n"
                    + "@AIDraft(instructions = \"modernize it!\")\n"
                    + "public class LegacyBridgeDraftConflicted {}\n"),

            // @AIPublicAPI + @AILocked
            new StringSource("com/example/v4/PublicApiLockedRedundant.java",
                "package com.example.v4;\n"
                    + "import se.deversity.vibetags.annotations.AIPublicAPI;\n"
                    + "import se.deversity.vibetags.annotations.AILocked;\n"
                    + "@AIPublicAPI\n"
                    + "@AILocked\n"
                    + "public class PublicApiLockedRedundant {}\n"),

            // @AIParallelTests + @AILocked
            new StringSource("com/example/v4/ParallelTestsLockedRedundant.java",
                "package com.example.v4;\n"
                    + "import se.deversity.vibetags.annotations.AIParallelTests;\n"
                    + "import se.deversity.vibetags.annotations.AILocked;\n"
                    + "@AIParallelTests\n"
                    + "@AILocked\n"
                    + "public class ParallelTestsLockedRedundant {}\n"),

            // @AISchemaSafe + @AIIgnore
            new StringSource("com/example/v4/SchemaSafeIgnoreRedundant.java",
                "package com.example.v4;\n"
                    + "import se.deversity.vibetags.annotations.AISchemaSafe;\n"
                    + "import se.deversity.vibetags.annotations.AIIgnore;\n"
                    + "@AISchemaSafe\n"
                    + "@AIIgnore\n"
                    + "public class SchemaSafeIgnoreRedundant {}\n"),

            // @AIStrictClasspath + @AILocked
            new StringSource("com/example/v4/StrictClasspathLockedRedundant.java",
                "package com.example.v4;\n"
                    + "import se.deversity.vibetags.annotations.AIStrictClasspath;\n"
                    + "import se.deversity.vibetags.annotations.AILocked;\n"
                    + "@AIStrictClasspath\n"
                    + "@AILocked\n"
                    + "public class StrictClasspathLockedRedundant {}\n"),

            // @AIArchitecture with blank belongsTo
            new StringSource("com/example/v4/BlankArchitecture.java",
                "package com.example.v4;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "@AIArchitecture(belongsTo = \"   \")\n"
                    + "public class BlankArchitecture {}\n"),

            // @AIArchitecture with forbidden import
            new StringSource("com/example/v4/ForbiddenArchitectureImport.java",
                "package com.example.v4;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "import java.util.ArrayList;\n"
                    + "@AIArchitecture(belongsTo = \"domain\", cannotReference = {\"java.util\"})\n"
                    + "public class ForbiddenArchitectureImport {}\n")
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
    void warns_aiLegacyBridgeAndAiDraft_combination() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AILegacyBridge") && w.contains("@AIDraft") && w.contains("contradictory")),
            "Expected warning about @AILegacyBridge + @AIDraft. Warnings: " + warnings);
    }

    @Test
    void warns_aiPublicApiAndAiLocked_combination() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIPublicAPI") && w.contains("@AILocked") && w.contains("redundant")),
            "Expected warning about @AIPublicAPI + @AILocked. Warnings: " + warnings);
    }

    @Test
    void warns_aiParallelTestsAndAiLocked_combination() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIParallelTests") && w.contains("@AILocked") && w.contains("redundant")),
            "Expected warning about @AIParallelTests + @AILocked. Warnings: " + warnings);
    }

    @Test
    void warns_aiSchemaSafeAndAiIgnore_combination() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AISchemaSafe") && w.contains("@AIIgnore") && w.contains("redundant")),
            "Expected warning about @AISchemaSafe + @AIIgnore. Warnings: " + warnings);
    }

    @Test
    void warns_aiStrictClasspathAndAiLocked_combination() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIStrictClasspath") && w.contains("@AILocked") && w.contains("redundant")),
            "Expected warning about @AIStrictClasspath + @AILocked. Warnings: " + warnings);
    }

    @Test
    void warns_aiArchitecture_withBlankBelongsTo() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIArchitecture") && w.contains("belongsTo") && w.contains("blank")),
            "Expected warning about blank belongsTo on @AIArchitecture. Warnings: " + warnings);
    }

    @Test
    void errors_aiArchitecture_withForbiddenImport() {
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIArchitecture") && w.contains("strictly prohibited from referencing 'java.util'") && w.contains("illegal import of 'java.util.ArrayList'")),
            "Expected compilation error about forbidden import. Diagnostics/Warnings: " + warnings);
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
