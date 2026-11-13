package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The early exit on a compiler that exposes no Tree API (#1062): ECJ, a build tool's decorator the
 * reflective unwrap cannot see through.
 *
 * <p>On javac, {@code MethodBodyGuardrailScanner} reads each guardrail annotation's argument trees
 * and refuses to record a source digest when a value comes from outside the round, such as
 * {@code @AILocked(reason = Reasons.LEDGER)} with {@code Reasons} in a dependency. Without the Tree
 * API that scan did not run, the count stayed zero, and the digest was recorded: changing the
 * constant changed no source the digest reads, so the next build was skipped and the generated
 * files kept the old value. Measured with ECJ 3.46.100 on 2026-11-13, where {@code CLAUDE.md} still
 * said {@code ledger-v1} after the constant became {@code ledger-v2}.
 *
 * <p>These tests reproduce that under javac with the Tree API hidden behind an opaque
 * {@code ProcessingEnvironment}, the same seam {@code OneSourceLookupPerElementTest} uses.
 */
@Tag("e2e")
class SourceDigestWithoutTreeApiTest {

    private static final String EARLY_EXIT = "(source digest ";

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void aDependencyConstantIsNotVouchedForWithoutTheTreeApi() throws IOException {
        ProcessorTestHarness h = project("""
            package com.example;

            import se.deversity.vibetags.annotations.AILocked;

            @AILocked(reason = com.lib.Reasons.LEDGER)
            public class Ledger {
            }
            """);
        String[] classpath = {"-classpath", System.getProperty("java.class.path")
            + File.pathSeparator + compileReasons("ledger maths is audited")};
        compile(h, classpath);
        assertTrue(read("CLAUDE.md").contains("ledger maths is audited"), "precondition:\n" + read("CLAUDE.md"));
        compileReasons("ledger maths moved to the settlement service");

        String second = notes(compile(h, classpath));

        assertFalse(second.contains(EARLY_EXIT), "the constant changed behind the digest:\n" + second);
        assertTrue(read("CLAUDE.md").contains("ledger maths moved to the settlement service"), read("CLAUDE.md"));
    }

    /**
     * The guard above must not cost the exit where the source spells every value out, and it shows
     * the exit is reachable without the Tree API at all, which is what makes the case above real.
     */
    @Test
    void valuesSpelledOutInTheRoundStillTakeTheExitWithoutTheTreeApi() throws IOException {
        ProcessorTestHarness h = project("""
            package com.example;

            import se.deversity.vibetags.annotations.AIContext;
            import se.deversity.vibetags.annotations.AILocked;
            import se.deversity.vibetags.annotations.AITestDriven;

            @AILocked(reason = "ledger maths is \\"audited\\"")
            @AIContext(focus = Topics.SETTLEMENT)
            @AITestDriven(coverageGoal = 90, framework = AITestDriven.Framework.JUNIT_5)
            public class Ledger {
            }
            """);
        h.writeSourceFile("src/main/java/com/example/Topics.java", """
            package com.example;

            final class Topics {
                static final String SETTLEMENT = "settlement routing";
            }
            """);
        compile(h);
        assertTrue(read("CLAUDE.md").contains("ledger maths is"), "precondition:\n" + read("CLAUDE.md"));

        String second = notes(compile(h));

        assertTrue(second.contains(EARLY_EXIT), second);
    }

    // ---------------------------------------------------------------- helpers

    private List<Diagnostic<? extends JavaFileObject>> compile(ProcessorTestHarness h, String... options) {
        List<Diagnostic<? extends JavaFileObject>> diagnostics =
            h.compileWithReturningDiagnostics(new TreelessProcessor(), options);
        VibeTagsLogger.shutdown();
        return diagnostics;
    }

    private ProcessorTestHarness project(String ledger) throws IOException {
        Files.writeString(root.resolve("CLAUDE.md"), "", StandardCharsets.UTF_8);
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.writeSourceFile("src/main/java/com/example/Ledger.java", ledger);
        return h;
    }

    private Path compileReasons(String value) throws IOException {
        Path source = root.resolve("lib-src/com/lib/Reasons.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.lib;\n\npublic final class Reasons {\n"
            + "    public static final String LEDGER = \"" + value + "\";\n}\n", StandardCharsets.UTF_8);
        Path out = Files.createDirectories(root.resolve("lib-classes"));
        int exit = javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", out.toString(), source.toString());
        assertEquals(0, exit, "the dependency must compile");
        return out;
    }

    private String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
    }

    private static String notes(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream().map(d -> d.getKind() + ": " + d.getMessage(null))
            .collect(Collectors.joining("\n"));
    }

    /** The processor behind a decorator that hides its delegate, and so the Tree API, from reflection. */
    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    @SupportedOptions({"vibetags.root", "vibetags.project", "vibetags.log.path", "vibetags.log.level",
                       "vibetags.cache", "vibetags.check", "vibetags.module"})
    private static final class TreelessProcessor extends AIGuardrailProcessor {
        @Override
        public synchronized void init(ProcessingEnvironment processingEnv) {
            super.init(new OpaqueEnvironment(processingEnv));
        }
    }

    /** A pass-through whose delegate is typed {@code Object}, so no reflective unwrap finds it. */
    private static final class OpaqueEnvironment implements ProcessingEnvironment {
        private final Object hidden;

        OpaqueEnvironment(ProcessingEnvironment delegate) {
            this.hidden = delegate;
        }

        private ProcessingEnvironment env() {
            return (ProcessingEnvironment) hidden;
        }

        @Override public Map<String, String> getOptions() { return env().getOptions(); }
        @Override public Messager getMessager() { return env().getMessager(); }
        @Override public Filer getFiler() { return env().getFiler(); }
        @Override public Elements getElementUtils() { return env().getElementUtils(); }
        @Override public Types getTypeUtils() { return env().getTypeUtils(); }
        @Override public SourceVersion getSourceVersion() { return env().getSourceVersion(); }
        @Override public Locale getLocale() { return env().getLocale(); }
    }
}
