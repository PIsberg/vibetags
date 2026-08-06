package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Tag;
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
 * Pins the user-facing "Generating files" NOTE to the real active-services set.
 *
 * <p>The NOTE used to report the aggregate content files it was about to write, so a build with
 * {@code CLAUDE.md} plus {@code .claude/rules/} opted in said "1 active services: claude" while
 * {@code vibetags.log} correctly recorded two. A consumer reading compiler output to answer
 * "is my granular directory actually active?" got a wrong answer from the only channel they were
 * looking at. Found dogfooding RC10 across five consumer repos (common-license-lib reported 2 of
 * 4, skill3 reported 4 with two granular directories silently active).
 */
@Tag("e2e")
class ActiveServicesNoteTest {

    @Test
    void generatingFilesNote_countsAndNamesGranularServices(@TempDir Path tmp) throws IOException {
        Files.createFile(tmp.resolve("CLAUDE.md"));
        Files.createDirectories(tmp.resolve(".claude").resolve("rules"));

        List<String> messages = compileAllMessages(tmp, List.of(
            new StringSource("com/example/core/Kernel.java",
                "package com.example.core;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "@AIArchitecture(belongsTo = \"core\")\n"
                    + "public class Kernel {}\n")));

        String note = messages.stream()
            .filter(m -> m.contains("Generating files (v"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No generating-files NOTE was emitted. Messages: " + messages));

        assertTrue(note.contains("2 active services"),
            "The NOTE must count every active service, granular included. NOTE: " + note);
        assertTrue(note.contains("claude_granular"),
            "The NOTE must name the granular service, not only the aggregate. NOTE: " + note);
    }

    /** Compiles sources in-process and returns all diagnostic messages (all kinds). */
    private static List<String> compileAllMessages(Path tmp, List<JavaFileObject> sources) throws IOException {
        List<String> messages = new ArrayList<>();
        Path classOut = tmp.resolve("classes");
        Files.createDirectories(classOut);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));
            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-proc:only",
                "-Avibetags.root=" + tmp.toAbsolutePath()
            );
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, sources);
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            task.call();
        }
        for (var d : diagnostics.getDiagnostics()) {
            messages.add(d.getMessage(Locale.ROOT));
        }
        return messages;
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
