package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AILocked;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers AnnotationValidator gaps related to @AIArchitecture:
 * <ul>
 *   <li>blank belongsTo warning (L300–304)</li>
 *   <li>cannotReference with Trees API — match sub-branches (L320–331):
 *     exact match, prefix-dot match, prefix-star match, blank forbidden entry (skip)</li>
 *   <li>Trees API unavailable catch-Throwable path (L343–348) — triggered by the mock
 *     validator unit test that passes processingEnv=null</li>
 * </ul>
 *
 * These tests compile real Java sources so the Trees API is live and the import-scan
 * logic executes. processingEnv=null tests cover the graceful-fallback catch path.
 */
class AnnotationValidatorArchitectureTest {

    // -----------------------------------------------------------------------
    // blank belongsTo → warning
    // -----------------------------------------------------------------------

    @Test
    void architecture_blankBelongsTo_emitsWarning(@TempDir Path tmp) throws IOException {
        List<String> warnings = compile(tmp, List.of(
            new StringSource("com/example/arch/BlankLayer.java",
                "package com.example.arch;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "@AIArchitecture(belongsTo = \"\")\n"
                    + "public class BlankLayer {}\n")));

        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("@AIArchitecture") && (w.contains("blank") || w.contains("belongsTo"))),
            "Expected warning about blank 'belongsTo'. Warnings: " + warnings);
    }

    @Test
    void architecture_nonBlankBelongsTo_noBlankBelongsToWarning(@TempDir Path tmp) throws IOException {
        List<String> warnings = compile(tmp, List.of(
            new StringSource("com/example/arch/ServiceLayer.java",
                "package com.example.arch;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "@AIArchitecture(belongsTo = \"service\")\n"
                    + "public class ServiceLayer {}\n")));

        assertTrue(warnings.stream().noneMatch(w ->
                w.contains("@AIArchitecture") && w.contains("blank")),
            "No blank-belongsTo warning expected for non-blank value. Warnings: " + warnings);
    }

    // -----------------------------------------------------------------------
    // cannotReference — exact import match triggers ERROR
    // -----------------------------------------------------------------------

    @Test
    void architecture_cannotReference_exactImportMatch_emitsError(@TempDir Path tmp) throws IOException {
        List<String> allMessages = compileAllMessages(tmp, List.of(
            new StringSource("com/example/arch/ExactImportClass.java",
                "package com.example.arch;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "import com.example.forbidden.ForbiddenClass;\n"
                    + "@AIArchitecture(belongsTo = \"service\", cannotReference = {\"com.example.forbidden.ForbiddenClass\"})\n"
                    + "public class ExactImportClass {}\n"),
            new StringSource("com/example/forbidden/ForbiddenClass.java",
                "package com.example.forbidden;\n"
                    + "public class ForbiddenClass {}\n")));

        assertTrue(allMessages.stream().anyMatch(w ->
                w.contains("prohibited") || w.contains("illegal import") || w.contains("forbidden")),
            "Expected an error for exact import match. Messages: " + allMessages);
    }

    // -----------------------------------------------------------------------
    // cannotReference — prefix-dot match (import starts with forbiddenPkg + ".")
    // -----------------------------------------------------------------------

    @Test
    void architecture_cannotReference_prefixDotMatch_emitsError(@TempDir Path tmp) throws IOException {
        List<String> allMessages = compileAllMessages(tmp, List.of(
            new StringSource("com/example/arch/PrefixDotClass.java",
                "package com.example.arch;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "import com.example.forbidden.sub.ForbiddenSub;\n"
                    + "@AIArchitecture(belongsTo = \"service\", cannotReference = {\"com.example.forbidden\"})\n"
                    + "public class PrefixDotClass {}\n"),
            new StringSource("com/example/forbidden/sub/ForbiddenSub.java",
                "package com.example.forbidden.sub;\n"
                    + "public class ForbiddenSub {}\n")));

        assertTrue(allMessages.stream().anyMatch(w ->
                w.contains("prohibited") || w.contains("illegal import") || w.contains("forbidden")),
            "Expected error for prefix-dot import match. Messages: " + allMessages);
    }

    // -----------------------------------------------------------------------
    // cannotReference — blank forbidden entry is skipped (continue branch at L320)
    // -----------------------------------------------------------------------

    @Test
    void architecture_cannotReference_blankForbiddenEntry_noError(@TempDir Path tmp) throws IOException {
        // Blank entry in cannotReference → the "continue" branch at L320 fires; no error
        List<String> allMessages = compileAllMessages(tmp, List.of(
            new StringSource("com/example/arch/BlankForbiddenEntry.java",
                "package com.example.arch;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "import java.util.List;\n"
                    + "@AIArchitecture(belongsTo = \"service\", cannotReference = {\"\", \"  \"})\n"
                    + "public class BlankForbiddenEntry { List<?> f; }\n")));

        assertTrue(allMessages.stream().noneMatch(w ->
                w.contains("prohibited") || w.contains("illegal import")),
            "Blank forbidden entries must not trigger an import error. Messages: " + allMessages);
    }

    // -----------------------------------------------------------------------
    // cannotReference — wildcard import match (import com.example.forbidden.*)
    // -----------------------------------------------------------------------

    @Test
    void architecture_cannotReference_wildcardImportMatch_emitsError(@TempDir Path tmp) throws IOException {
        // import com.example.forbidden.* → importStr = "com.example.forbidden.*"
        // forbiddenPkg = "com.example.forbidden" → importStr.startsWith(forbiddenPkg + "*") → true
        List<String> allMessages = compileAllMessages(tmp, List.of(
            new StringSource("com/example/arch/WildcardImportClass.java",
                "package com.example.arch;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "import com.example.forbidden.*;\n"
                    + "@AIArchitecture(belongsTo = \"service\", cannotReference = {\"com.example.forbidden\"})\n"
                    + "public class WildcardImportClass {}\n"),
            new StringSource("com/example/forbidden/SomeType.java",
                "package com.example.forbidden;\n"
                    + "public class SomeType {}\n")));

        assertTrue(allMessages.stream().anyMatch(w ->
                w.contains("prohibited") || w.contains("illegal import") || w.contains("forbidden")),
            "Wildcard import of forbidden package must trigger an error. Messages: " + allMessages);
    }

    // -----------------------------------------------------------------------
    // cannotReference — import that does NOT match any forbidden pkg (false branch at L331)
    // -----------------------------------------------------------------------

    @Test
    void architecture_cannotReference_nonMatchingImport_noError(@TempDir Path tmp) throws IOException {
        List<String> allMessages = compileAllMessages(tmp, List.of(
            new StringSource("com/example/arch/SafeImportClass.java",
                "package com.example.arch;\n"
                    + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                    + "import java.util.List;\n"
                    + "@AIArchitecture(belongsTo = \"service\", cannotReference = {\"com.example.forbidden\"})\n"
                    + "public class SafeImportClass { List<?> f; }\n")));

        assertTrue(allMessages.stream().noneMatch(w ->
                w.contains("prohibited") || w.contains("illegal import")),
            "Safe imports must not trigger an architectural import error. Messages: " + allMessages);
    }

    // -----------------------------------------------------------------------
    // Helper utilities
    // -----------------------------------------------------------------------

    /** Compiles sources and returns only non-empty diagnostic messages. */
    @SuppressWarnings("unused")
    private static List<String> compile(Path tmp, List<JavaFileObject> sources) throws IOException {
        return compileAllMessages(tmp, sources).stream()
            .filter(m -> !m.isEmpty())
            .toList();
    }

    /** Compiles sources and returns all diagnostic messages (all kinds). */
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
