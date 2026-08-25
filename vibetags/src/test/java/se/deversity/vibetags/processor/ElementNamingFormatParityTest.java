package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.ElementNaming;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ElementNaming} must render a member exactly as javac's own {@code toString()} does.
 *
 * <p>Both halves of that sentence are load-bearing.
 *
 * <p><b>Why it may not be toString().</b> {@code Element.toString()} is specified as "a string
 * representation of this element" and the format belongs to the implementation. ECJ renders the
 * same method as {@code public int getKeyRotationHours() } where javac renders
 * {@code getKeyRotationHours()} — modifiers, return type and a trailing space. That string is the
 * element's <em>identity</em>: {@code .vibetags-locks} records it and {@code action/locked-files}
 * matches a pull request's diff against it, and {@code granularQName} turns it into a rule
 * filename. An identity that changes with the compiler is a lock that stops matching and a rule
 * file that gets rewritten under a new name.
 *
 * <p><b>Why it must equal javac's.</b> Every committed fixture in this repository, and every
 * generated file in every consumer, was produced by javac. Any format but javac's would rename
 * elements for everybody. So the derivation targets javac's rendering, and this test compiles a
 * fixture with a real javac and asserts the two agree member by member. Under javac it is a
 * regression guard; under a compiler whose {@code toString()} differs, the derivation is what
 * makes the output agree with javac anyway, which is the whole point.
 *
 * <p>The fixture is deliberately awkward: primitives, a qualified generic, a nested type, an
 * array, a varargs tail, a wildcard, a type variable, an overload set, a constructor and a field.
 * Those are the shapes where a hand-rolled signature builder goes wrong.
 *
 * <p>If javac ever changes its rendering this test goes red, which is the correct outcome: the
 * alternative is every consumer's generated files moving with no commit to explain it.
 */
class ElementNamingFormatParityTest {

    private static final String FIXTURE = """
        package fixture;

        import java.util.List;
        import java.util.Map;

        public class Shapes {
            public int plainField;
            public java.util.Map<String, Object> genericField;

            public Shapes() {}
            public Shapes(int a, String b) {}

            public void noArgs() {}
            public void primitives(int a, double b, boolean c, long d, char e) {}
            public void qualifiedGeneric(Map<String, Object> m) {}
            public void nestedGeneric(Map<String, List<Integer>> m) {}
            public void arrays(String[] a, int[][] b) {}
            public void varargs(String first, int... rest) {}
            public void wildcards(List<? extends Number> a, List<? super Integer> b) {}
            public <T> void typeVariable(T value, List<T> values) {}
            public <T extends Number, U> void boundedTypeVariables(T a, U b) {}
            public void nestedType(Inner inner, Inner.Deeper deeper) {}
            public void overload(String a) {}
            public void overload(String a, int b) {}
            public void voidBoxed(Void v, Integer i) {}

            public static class Inner {
                public String innerField;
                public void innerMethod(Shapes owner) {}
                public static class Deeper {
                    public void deepMethod(Inner sibling) {}
                }
            }

            public enum Flavour {
                ONE, TWO;
                public void onFlavour(Flavour other) {}
            }
        }
        """;

    /** Every member javac saw, as {@code enclosingFqn + "." + member.toString()}. */
    private final List<String> javacRendering = new ArrayList<>();
    /** The same members, as {@link ElementNaming#elementPath} derives them. */
    private final List<String> derivedRendering = new ArrayList<>();

    @Test
    @DisplayName("every member renders exactly as javac's own toString() does")
    void derivedSignaturesMatchJavac() throws IOException {
        compileFixture();

        assertTrue(javacRendering.size() >= 20,
            "the fixture stopped covering the awkward shapes — only " + javacRendering.size()
                + " members were visited, so a passing run proves very little");

        assertEquals(javacRendering, derivedRendering,
            "ElementNaming no longer renders members the way javac does. Every committed fixture "
                + "and every consumer's generated files were produced by javac, so a divergence "
                + "here renames elements in .vibetags-locks (which action/locked-files matches a "
                + "PR diff against) and renames granular rule files.");
    }

    /** A fixture whose parameters carry a JSR-308 type-use annotation. */
    private static final String ANNOTATED_FIXTURE = """
        package fixture;

        import org.jspecify.annotations.Nullable;

        public class Annotated {
            public void takesNullable(@Nullable String a, int b) {}
            public void returnsAndTakes(@Nullable Object o) {}
        }
        """;

    @Test
    @DisplayName("a type-use annotation is left out of the element's identity, on purpose")
    void typeUseAnnotationsAreNotPartOfTheIdentity() throws IOException {
        javacRendering.clear();
        derivedRendering.clear();
        compile(ANNOTATED_FIXTURE, "Annotated");

        // javac renders the parameter as java.lang.@org.jspecify.annotations.Nullable String.
        String annotatedByJavac = javacRendering.stream()
            .filter(s -> s.contains("takesNullable"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the fixture no longer produces an annotated parameter, so this checks nothing: "
                    + javacRendering));
        assertTrue(annotatedByJavac.contains("@org.jspecify.annotations.Nullable"),
            "javac stopped putting type-use annotations in its rendering, which is the whole "
                + "premise of this test: " + annotatedByJavac);

        String derived = derivedRendering.get(javacRendering.indexOf(annotatedByJavac));
        assertEquals("fixture.Annotated.takesNullable(java.lang.String,int)", derived,
            "ElementNaming must render the signature without the annotation. This string is the "
                + "element's identity: action/locked-files matches a pull request's diff against "
                + "it, and granularQName turns it into a rule filename. Including the annotation "
                + "would rename a committed rule file, and break a lock match, every time a "
                + "@Nullable was added or removed without the signature changing.");
    }

    private void compileFixture() throws IOException {
        compile(FIXTURE, "Shapes");
    }

    private void compile(String sourceText, String simpleName) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null,
            "no system java compiler — this test compares against javac and cannot run without it");

        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            JavaFileObject source = new SimpleJavaFileObject(
                URI.create("string:///fixture/" + simpleName + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return sourceText;
                }
            };

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, files, null,
                // -proc:only: the fixture never needs to become bytecode, and skipping the rest of
                // the pipeline keeps the test to the model it is actually asserting about. The
                // classpath is this JVM's, so the annotated fixture can resolve jspecify.
                List.of("-proc:only", "-cp", System.getProperty("java.class.path")),
                null, List.of(source));
            task.setProcessors(List.of(new Collector()));
            assertTrue(task.call(), "the fixture failed to compile");
        }
    }

    /** Walks every member of every type in the fixture and records both renderings. */
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    private final class Collector extends AbstractProcessor {
        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            for (Element root : round.getRootElements()) {
                visit(root);
            }
            return false;
        }

        private void visit(Element type) {
            for (Element member : type.getEnclosedElements()) {
                ElementKind kind = member.getKind();
                if (kind == ElementKind.FIELD
                    || kind == ElementKind.METHOD
                    || kind == ElementKind.CONSTRUCTOR) {
                    Element enclosing = member.getEnclosingElement();
                    javacRendering.add(enclosing + "." + member);
                    derivedRendering.add(ElementNaming.elementPath(member));
                } else if (kind.isClass() || kind.isInterface()) {
                    visit(member);
                }
            }
        }
    }
}
