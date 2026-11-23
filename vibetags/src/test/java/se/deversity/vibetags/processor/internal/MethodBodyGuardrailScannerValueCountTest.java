package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link MethodBodyGuardrailScanner} counts and reports through the Tree API, on real javac
 * trees: how many guardrail annotation values the round's own sources do not determine, and how
 * many guardrail annotations it warns are invisible to processing.
 *
 * <p>The count decides whether the next build may take the early exit. A value counted that did
 * not need to be costs one skipped exit; a value not counted that should have been leaves the
 * generated files on a dependency constant's old text for good. Until this test the count was only
 * reached through whole builds, which see whether it is zero, not what it is. Each case compiles
 * the annotated class beside {@code p.Consts}, which is in the round; the constants of
 * {@code java.util.jar.JarFile} and {@code java.lang.Short} stand for a dependency's.
 */
class MethodBodyGuardrailScannerValueCountTest {

    private static final String CONSTS = """
        package p;
        public class Consts {
            public static final String PLAIN = "plain";
            public static final String DERIVED = PLAIN + "-derived";
            public static final int GOAL = 80;
            public static final long LONG_GOAL = 80L;
            public static final boolean FLAG = true;
            public static final int CAST = (int) 80L;
            public static class Inner {
                public static final String NESTED = "nested";
            }
        }
        """;

    static Stream<Arguments> values() {
        return Stream.of(
            Arguments.of("a literal", "@AILocked(reason = \"plain\")", 0),
            Arguments.of("literals combined", "@AILocked(reason = \"a\" + \"b\")", 0),
            Arguments.of("a round constant with a literal initializer", "@AILocked(reason = Consts.PLAIN)", 0),
            Arguments.of("a round constant whose initializer names another", "@AILocked(reason = Consts.DERIVED)", 1),
            Arguments.of("a dependency constant", "@AILocked(reason = JarFile.MANIFEST_NAME)", 1),
            Arguments.of("a literal plus a round constant", "@AILocked(reason = \"a\" + Consts.PLAIN)", 0),
            Arguments.of("a literal plus a dependency constant", "@AILocked(reason = \"a\" + JarFile.MANIFEST_NAME)", 1),
            Arguments.of("a parenthesized round constant", "@AILocked(reason = (Consts.PLAIN))", 0),
            Arguments.of("a negated round constant", "@AITestDriven(coverageGoal = -Consts.GOAL)", 0),
            Arguments.of("a cast round constant", "@AITestDriven(coverageGoal = (int) Consts.LONG_GOAL)", 0),
            Arguments.of("a round constant with a cast literal initializer", "@AITestDriven(coverageGoal = Consts.CAST)", 0),
            Arguments.of("a conditional over round constants", "@AITestDriven(coverageGoal = Consts.FLAG ? 80 : Consts.GOAL)", 0),
            Arguments.of("a conditional with a dependency branch", "@AITestDriven(coverageGoal = Consts.FLAG ? 80 : Short.BYTES)", 1),
            Arguments.of("an array of round values", "@AIAudit(checkFor = {\"a\", Consts.PLAIN})", 0),
            Arguments.of("an array with a dependency value", "@AIAudit(checkFor = {\"a\", JarFile.MANIFEST_NAME})", 1),
            Arguments.of("a single value for an array attribute", "@AIAudit(checkFor = JarFile.MANIFEST_NAME)", 1),
            Arguments.of("two dependency values in one annotation",
                "@AISunset(jira = JarFile.MANIFEST_NAME, reason = JarFile.MANIFEST_NAME)", 2),
            Arguments.of("a constant of a type nested in a round type", "@AILocked(reason = Consts.Inner.NESTED)", 0),
            Arguments.of("a constant of the annotated class, by simple name", "@AILocked(reason = OWN)", 0),
            Arguments.of("an enum constant", "@AIThreadSafe(strategy = AIThreadSafe.Strategy.IMMUTABLE)", 0),
            Arguments.of("a class literal", "@AISunset(jira = \"J-1\", replacement = String.class)", 0),
            Arguments.of("a fully qualified annotation", "@se.deversity.vibetags.annotations.AILocked(reason = JarFile.MANIFEST_NAME)", 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void values(String shape, String annotation, int expected) {
        Probe probe = compile("""
            package p;
            import java.util.jar.JarFile;
            import se.deversity.vibetags.annotations.*;
            public class Host {
                static final String OWN = "own";
                %s
                public void guarded() { }
            }
            """.formatted(annotation));

        assertEquals(expected, probe.outside, shape);
        assertEquals(0, probe.warnings, shape);
    }

    /**
     * A constant javac parses from the source path is a tree with a spelled-out initializer, the
     * same as a round constant's, but its file is not one this round was given: an edit to it
     * changes no byte the source digest covers, so it has to count.
     */
    @Test
    void aSourcePathConstantOutsideTheRoundCounts(@TempDir Path sourcePath) throws IOException {
        Path dep = sourcePath.resolve("q/Dep.java");
        Files.createDirectories(dep.getParent());
        Files.writeString(dep, "package q;\npublic class Dep { public static final String D = \"d\"; }\n");

        Probe probe = compile("""
            package p;
            import se.deversity.vibetags.annotations.*;
            public class Host {
                @AILocked(reason = q.Dep.D)
                public void guarded() { }
            }
            """, "-sourcepath", sourcePath.toString());

        assertEquals(1, probe.outside, "a source-path constant is not the round's to vouch for");
    }

    static Stream<Arguments> bodies() {
        String locked = "@AILocked(reason = JarFile.MANIFEST_NAME)";
        return Stream.of(
            Arguments.of("a member class", "static class Member { " + locked + " void m() { } }", 0, 1),
            Arguments.of("a local class in a method", "void host() { class Local { " + locked + " void m() { } } }", 1, 0),
            Arguments.of("an anonymous class member",
                "Runnable host() { return new Runnable() { " + locked + " public void run() { } }; }", 1, 0),
            Arguments.of("a local class in a static initializer",
                "static { class Local { " + locked + " void m() { } } }", 1, 0),
            Arguments.of("a local class in an instance initializer",
                "{ class Local { " + locked + " void m() { } } }", 1, 0),
            Arguments.of("a local class in a lambda in a field initializer",
                "Runnable task = () -> { class Local { " + locked + " void m() { } } };", 1, 0),
            Arguments.of("an anonymous class in a field initializer",
                "Runnable task = new Runnable() { " + locked + " public void run() { } };", 1, 0),
            Arguments.of("an annotation on a local variable",
                "void host() { @SuppressWarnings(\"x\") " + locked.replace("@AILocked", "@Deprecated @AIContext")
                    .replace("reason = JarFile.MANIFEST_NAME", "focus = JarFile.MANIFEST_NAME")
                    + " int x = 0; }", 0, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void bodies(String shape, String member, int warnings, int outside) {
        Probe probe = compile("""
            package p;
            import java.util.jar.JarFile;
            import se.deversity.vibetags.annotations.*;
            public class Host {
                %s
            }
            """.formatted(member));

        assertEquals(warnings, probe.warnings, shape + ": warnings");
        assertEquals(outside, probe.outside, shape + ": values from outside the round");
    }

    /** Runs the scanner the way the processor does, once per round, and totals what it found. */
    @SupportedAnnotationTypes("*")
    static final class Probe extends AbstractProcessor {
        int outside;
        int warnings;
        boolean ran;

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            if (round.processingOver()) {
                return false;
            }
            MethodBodyGuardrailScanner scanner = MethodBodyGuardrailScanner.forEnv(processingEnv);
            scanner.scanAndWarn(round, RoundSources.of(processingEnv, round));
            outside += scanner.valuesFromOutsideTheRound();
            warnings += scanner.warningsReported();
            ran = true;
            return false;
        }
    }

    static Probe compile(String host, String... extraOptions) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Probe probe = new Probe();
        List<String> options = new ArrayList<>(
            List.of("-proc:only", "-implicit:none", "-classpath", System.getProperty("java.class.path")));
        options.addAll(List.of(extraOptions));
        JavaCompiler.CompilationTask task = javac.getTask(null, null, diagnostics, options,
            null, List.of(source("p/Host.java", host), source("p/Consts.java", CONSTS)));
        task.setProcessors(List.of(probe));
        task.call();
        assertTrue(probe.ran, "the probe never ran: " + diagnostics.getDiagnostics());
        return probe;
    }

    private static JavaFileObject source(String path, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }
}
