package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Generated output must be a function of the annotations, not of the order javac happened to hand
 * the sources over in.
 *
 * <p>{@link javax.annotation.processing.RoundEnvironment#getElementsAnnotatedWith} returns a
 * {@code Set} with no specified iteration order. javac fills it by walking the round's root
 * elements, which is the order the file manager enumerated them — and that is not the same between
 * Maven and Gradle, between an IDE and a command line, or between two machines whose directory
 * listings differ. The collector's {@code LinkedHashSet} faithfully preserves whatever order it was
 * given, which makes the generated files and the {@code BuildFingerprint} preserve it too.
 *
 * <p>The consequence, if it is not pinned: two developers compiling identical sources get different
 * {@code CLAUDE.md} content and different cache fingerprints, so every build after a colleague's
 * rewrites the guardrail files for no reason and the review diff is noise.
 *
 * <p>This compiles the same sources twice with the file list reversed and requires byte-identical
 * output.
 */
class OutputOrderDeterminismTest {

    @AfterEach
    void releaseProcessorLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void generatedOutputIsIdentical_whenTheSourceOrderIsReversed(@TempDir Path tmp) throws IOException {
        List<JavaFileObject> sources = new ArrayList<>(List.of(
            source("Zulu", "package com.example.order;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AILocked(reason = \"zulu is load-bearing\")\n"
                + "@AIContext(focus = \"zulu\", avoids = \"nothing\")\n"
                + "public class Zulu {}\n"),
            source("Alpha", "package com.example.order;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AILocked(reason = \"alpha is load-bearing\")\n"
                + "@AIContext(focus = \"alpha\", avoids = \"nothing\")\n"
                + "public class Alpha {}\n"),
            source("Mike", "package com.example.order;\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "import se.deversity.vibetags.annotations.AIContext;\n"
                + "@AILocked(reason = \"mike is load-bearing\")\n"
                + "@AIContext(focus = \"mike\", avoids = \"nothing\")\n"
                + "public class Mike {}\n")));

        String forward = generate(tmp.resolve("forward"), sources);

        List<JavaFileObject> reversed = new ArrayList<>(sources);
        Collections.reverse(reversed);
        String backward = generate(tmp.resolve("backward"), reversed);

        assertEquals(forward, backward,
            "CLAUDE.md must not depend on the order javac enumerated the sources in — two developers "
                + "compiling the same code would otherwise rewrite each other's generated files");
    }

    /** Compiles into a fresh project root with CLAUDE.md opted in, and returns what was written. */
    private static String generate(Path root, List<JavaFileObject> sources) throws IOException {
        Files.createDirectories(root);
        Path claudeMd = root.resolve("CLAUDE.md");
        Files.writeString(claudeMd, "", StandardCharsets.UTF_8);
        Path classOut = root.resolve("classes");
        Files.createDirectories(classOut);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics,
                List.of("-classpath", System.getProperty("java.class.path"),
                        "-Avibetags.root=" + root.toAbsolutePath(),
                        "-Avibetags.cache=false"),
                null, sources);
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            task.call();
        }
        return Files.readString(claudeMd, StandardCharsets.UTF_8);
    }

    private static JavaFileObject source(String simpleName, String code) {
        return new SimpleJavaFileObject(
                URI.create("string:///com/example/order/" + simpleName + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }
}
