package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A validation diagnostic survives the trip into {@code .vibetags-cache} and back (#856).
 *
 * <p>The text has to come back byte for byte, and the anchor has to name the same element in the
 * next build, or a replayed warning lands on another line or none: an early-exited rebuild would
 * then warn differently from the cold build, which is the thing the replay exists to prevent.
 */
class ReplayableDiagnosticTest {

    @Test
    void theEncodedFormRoundTripsAnyText() {
        String awkward = "VibeTags: line one\nline two\ttabbed \u00E9\u3000 # not a comment = not a key";
        for (ReplayableDiagnostic d : List.of(
                new ReplayableDiagnostic(Diagnostic.Kind.WARNING, awkward, "type\ncom.example.Ledger", null),
                new ReplayableDiagnostic(Diagnostic.Kind.MANDATORY_WARNING, "m", null, null),
                new ReplayableDiagnostic(Diagnostic.Kind.NOTE, "", "type\nA", "se.deversity.vibetags.annotations.AIContext"))) {
            String encoded = d.encode();
            assertTrue(encoded.indexOf('\n') < 0 && encoded.indexOf('\r') < 0,
                "one cache line per diagnostic: " + encoded);
            assertEquals(d, ReplayableDiagnostic.decode(encoded));
        }
    }

    @Test
    void aLineThatIsNotAnEncodedDiagnosticDecodesToNothing() {
        for (String bad : List.of("", "WARNING", "WARNING a b", "SHOUT - - bWVzc2FnZQ==", "WARNING - - !!!",
                "WARNING !!! - bWVzc2FnZQ==", "WARNING - - bWVzc2FnZQ== extra")) {
            assertNull(ReplayableDiagnostic.decode(bad), "decoded [" + bad + "]");
        }
    }

    /** Every root and member of the fixture, anchored and resolved back inside a live round. */
    @SupportedAnnotationTypes("*")
    private static final class RoundTrip extends AbstractProcessor {
        final List<String> checked = new ArrayList<>();
        final List<String> unanchored = new ArrayList<>();

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            Elements elements = processingEnv.getElementUtils();
            for (Element root : roundEnv.getRootElements()) {
                visit(elements, root);
            }
            return false;
        }

        private void visit(Elements elements, Element element) {
            String anchor = ReplayableDiagnostic.anchorOf(element);
            if (anchor == null) {
                unanchored.add(element.getKind() + " " + element);
            } else {
                assertSame(element, ReplayableDiagnostic.resolve(elements, anchor),
                    "the anchor " + anchor.replace('\n', '|') + " must resolve to the element it named");
                checked.add(element.getKind() + " " + element);
            }
            for (Element enclosed : element.getEnclosedElements()) {
                visit(elements, enclosed);
            }
            if (element instanceof ExecutableElement executable) {
                executable.getParameters().forEach(p -> visit(elements, p));
            }
        }
    }

    @Test
    void everyElementValidationCanWarnOnResolvesBackToItself() {
        String source = """
            package com.example;

            public class Ledger {
                public Ledger() {}
                public Ledger(int seed) {}
                private int total;
                private final int[] values = new int[0];
                public void post(int amount) {}
                public void post(long amount) {}
                public void post(java.util.List<String> lines, String... rest) {}
                public <T extends Comparable<T>> T max(T a, T b) { return a; }
                public static final class Snapshot { private int[] kept; void keep(int[] k) {} }
                public record Frame(int[] data, String label) {}
                public enum Mode { OPEN, CLOSED; void flip() {} }
                public @interface Marker { String value() default ""; }
                interface Api { default void call() {} }
            }
            """;
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///com/example/Ledger.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        RoundTrip roundTrip = new RoundTrip();
        JavaCompiler.CompilationTask task = compiler.getTask(null, null, null, List.of("-proc:only"), null, List.of(unit));
        task.setProcessors(List.of(roundTrip));
        assertTrue(task.call(), "the fixture must compile");

        assertTrue(roundTrip.checked.size() > 30, "most of the fixture must have been checked: " + roundTrip.checked);
        for (String kind : List.of("CONSTRUCTOR", "METHOD", "FIELD", "PARAMETER", "RECORD_COMPONENT",
                "ENUM_CONSTANT", "CLASS", "RECORD", "ENUM", "ANNOTATION_TYPE", "INTERFACE")) {
            assertTrue(roundTrip.checked.stream().anyMatch(c -> c.startsWith(kind + " ")),
                kind + " must be among the anchored kinds: " + roundTrip.checked);
        }
        assertTrue(roundTrip.unanchored.stream().allMatch(u -> u.startsWith(ElementKind.TYPE_PARAMETER.name())),
            "only a type parameter may go unanchored, and then it replays without a position: "
                + roundTrip.unanchored);
    }

    @Test
    void anAnchorThatNoLongerResolvesIsNotAnElement() {
        List<Element> found = new ArrayList<>();
        @SupportedAnnotationTypes("*")
        final class Probe extends AbstractProcessor {
            @Override
            public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.latestSupported();
            }

            @Override
            public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                if (!roundEnv.processingOver()) {
                    Elements elements = processingEnv.getElementUtils();
                    for (String anchor : List.of("type\ncom.example.Gone", "member\njava.lang.String\nMETHOD\nnoSuch()",
                            "param\njava.lang.String\nMETHOD\nlength()\n3", "nonsense", "type")) {
                        Element e = ReplayableDiagnostic.resolve(elements, anchor);
                        if (e != null) {
                            found.add(e);
                        }
                    }
                    assertNotNull(ReplayableDiagnostic.resolve(elements, "type\njava.lang.String"),
                        "a type that exists still resolves");
                }
                return false;
            }
        }
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "class A {}";
            }
        };
        JavaCompiler.CompilationTask task = ToolProvider.getSystemJavaCompiler()
            .getTask(null, null, null, List.of("-proc:only"), null, List.of(unit));
        task.setProcessors(List.of(new Probe()));
        assertTrue(task.call());

        assertEquals(List.of(), found);
    }
}
