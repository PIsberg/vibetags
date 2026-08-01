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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attribute checks whose interesting case is not "blank" but "present and wrong".
 *
 * <p>A blank required attribute is the easy half and is covered by the per-wave validation tests.
 * These are the branches a value has to be well-formed enough to reach: a date that looks like a
 * date and is not one, a {@code @AIThreadAffinity} that names its thread but not the way onto it.
 * Both were reachable and neither was reached.
 */
class CoreRulesEdgeCaseTest {

    @TempDir
    static Path tempDir;

    private static List<String> messages;

    @BeforeAll
    static void compileAndCollect() throws IOException {
        messages = new ArrayList<>();
        Path classOut = tempDir.resolve("classes");
        Files.createDirectories(classOut);

        List<JavaFileObject> sources = List.of(
            // Matches YYYY-MM-DD and still is not a date. The regex gate lets it through and
            // LocalDate.parse throws — the branch that turns that into a warning rather than a
            // stack trace out of an annotation processor.
            new StringSource("com/example/edge/ImpossibleDate.java",
                "package com.example.edge;\n"
                    + "import se.deversity.vibetags.annotations.AITemporary;\n"
                    + "@AITemporary(reason = \"waiting on upstream\", expiresOn = \"2026-02-31\")\n"
                    + "public class ImpossibleDate {}\n"),

            // A named thread with no route onto it: the caller is told "no" with no way to comply.
            new StringSource("com/example/edge/NamedButUnreachable.java",
                "package com.example.edge;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadAffinity;\n"
                    + "@AIThreadAffinity(value = AIThreadAffinity.Affinity.NAMED, thread = \"Swing EDT\")\n"
                    + "public class NamedButUnreachable {}\n"),

            // Well-formed and in the future: neither rule may fire.
            new StringSource("com/example/edge/CleanTemporary.java",
                "package com.example.edge;\n"
                    + "import se.deversity.vibetags.annotations.AITemporary;\n"
                    + "@AITemporary(reason = \"waiting on upstream\", expiresOn = \"2099-12-31\")\n"
                    + "public class CleanTemporary {}\n"),

            new StringSource("com/example/edge/CleanAffinity.java",
                "package com.example.edge;\n"
                    + "import se.deversity.vibetags.annotations.AIThreadAffinity;\n"
                    + "@AIThreadAffinity(value = AIThreadAffinity.Affinity.NAMED, thread = \"Swing EDT\",\n"
                    + "    marshalVia = \"SwingUtilities.invokeLater\")\n"
                    + "public class CleanAffinity {}\n"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics,
                List.of("-classpath", System.getProperty("java.class.path"), "-proc:only",
                        "-Avibetags.root=" + tempDir.toAbsolutePath()),
                null, sources);
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            task.call();
        }
        for (var d : diagnostics.getDiagnostics()) {
            messages.add(d.getMessage(Locale.ROOT));
        }
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void warns_whenAnExpiryDateIsWellFormedButImpossible() {
        assertTrue(messages.stream().anyMatch(m ->
                m.contains("@AITemporary") && m.contains("ImpossibleDate") && m.contains("unparseable")),
            "2026-02-31 passes the YYYY-MM-DD shape check and is still not a date; the parse failure "
                + "must become a warning rather than an exception out of the processor. Messages: " + messages);
    }

    @Test
    void warns_whenAnAffinityNamesAThreadButNoWayOntoIt() {
        assertTrue(messages.stream().anyMatch(m ->
                m.contains("@AIThreadAffinity") && m.contains("NamedButUnreachable") && m.contains("marshalVia")),
            "Naming the required thread without naming the way onto it leaves the caller stuck. "
                + "Messages: " + messages);
    }

    @Test
    void staysSilent_whenTheAttributesAreWellFormed() {
        assertFalse(messages.stream().anyMatch(m -> m.startsWith("VibeTags:") && m.contains("CleanTemporary")),
            "A future, valid date must not warn. Messages: " + messages);
        assertFalse(messages.stream().anyMatch(m -> m.startsWith("VibeTags:") && m.contains("CleanAffinity")),
            "A fully-specified affinity must not warn. Messages: " + messages);
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
