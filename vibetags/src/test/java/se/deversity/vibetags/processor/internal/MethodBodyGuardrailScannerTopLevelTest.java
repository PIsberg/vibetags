package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.util.ElementScanner14;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MethodBodyGuardrailScanner#topLevel}: from any element, the outermost type around it, whose
 * source file is where the early exit looks for an annotation value's spelling (#1062).
 *
 * <p>Landing one level too low reads the wrong file, or none: the value is then judged to come from
 * outside the round and the early exit is turned off for nothing, or judged present when it is not.
 * Landing too high, on the package, names no file at all. The boundary is which kinds of enclosing
 * element count as "still inside a type", so it is pinned twice: on real javac elements of every
 * nesting shape a source can declare, and on every {@link ElementKind} constant this JDK has, so a
 * kind added by a later JDK fails here until someone decides which side it is on.
 */
class MethodBodyGuardrailScannerTopLevelTest {

    // ------------------------------------------------------------------ real javac elements

    private static final String SOURCE = """
        package p;
        public class Outer {
            int field;
            Outer(int ctorParam) {}
            void method(String param) {}
            <T> void generic(T typed) {}
            static class Nested {
                String nestedField;
                void nestedMethod(long nestedParam) {}
                class Inner {
                    void innerMethod(int deepParam) {}
                    class Deepest { int deepestField; }
                }
            }
            enum Color { RED, GREEN; void enumMethod() {} }
            record Point(int x, int y) { Point { } }
            @interface Marker { String value() default ""; }
            interface Api { void call(int callParam); default void def() {} }
        }
        class Sibling { void siblingMethod() {} }
        """;

    /** Every element javac models for SOURCE, keyed by a readable path. */
    private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();

    @SupportedAnnotationTypes("*")
    private static final class Collector extends AbstractProcessor {
        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            for (Element root : round.getRootElements()) {
                ELEMENTS.putIfAbsent("package p", processingEnv.getElementUtils().getPackageOf(root));
                new ElementScanner14<Void, String>() {
                    @Override
                    public Void scan(Element e, String parent) {
                        String key = parent.isEmpty() ? e.getSimpleName().toString() : parent + "/" + e.getSimpleName();
                                        ELEMENTS.putIfAbsent(key, e);
                        if (e instanceof ExecutableElement executable) {
                            for (TypeParameterElement tp : executable.getTypeParameters()) {
                                ELEMENTS.putIfAbsent(key + "/<" + tp.getSimpleName() + ">", tp);
                            }
                        }
                        return super.scan(e, key);
                    }
                }.scan(root, "");
            }
            return false;
        }
    }

    @BeforeAll
    static void compile() {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///p/Outer.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return SOURCE;
            }
        };
        JavaCompiler.CompilationTask task = javac.getTask(null, null, null,
            List.of("-proc:only", "-implicit:none"), null, List.of(source));
        task.setProcessors(List.of(new Collector()));
        assertTrue(task.call(), "the fixture source must compile");
    }

    private static Element element(String path) {
        Element e = ELEMENTS.get(path);
        assertNotNull(e, path + " not modelled; known: " + ELEMENTS.keySet());
        return e;
    }

    /**
     * Every member of Outer javac models, at every depth. Initializer blocks are absent on purpose:
     * javac does not expose them as members during annotation processing, so their kinds are covered
     * by the every-ElementKind table below instead.
     */
    static Stream<Arguments> nestedInOuter() {
        return Stream.of(
            "Outer", "Outer/field", "Outer/<init>", "Outer/<init>/ctorParam", "Outer/method", "Outer/method/param",
            "Outer/generic", "Outer/generic/<T>", "Outer/generic/typed",
            "Outer/Nested", "Outer/Nested/nestedField", "Outer/Nested/nestedMethod",
            "Outer/Nested/nestedMethod/nestedParam", "Outer/Nested/Inner", "Outer/Nested/Inner/innerMethod",
            "Outer/Nested/Inner/innerMethod/deepParam", "Outer/Nested/Inner/Deepest",
            "Outer/Nested/Inner/Deepest/deepestField", "Outer/Color", "Outer/Color/RED", "Outer/Color/GREEN",
            "Outer/Color/enumMethod", "Outer/Point", "Outer/Point/x", "Outer/Point/y", "Outer/Marker",
            "Outer/Marker/value", "Outer/Api", "Outer/Api/call", "Outer/Api/call/callParam", "Outer/Api/def"
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nestedInOuter")
    void everythingDeclaredInsideOuterLandsOnOuter(String path) {
        assertSame(element("Outer"), MethodBodyGuardrailScanner.topLevel(element(path)), path);
    }

    @Test
    void aSecondTopLevelTypeInTheSameFileIsItsOwnTopLevel() {
        assertSame(element("Sibling"), MethodBodyGuardrailScanner.topLevel(element("Sibling")));
        assertSame(element("Sibling"), MethodBodyGuardrailScanner.topLevel(element("Sibling/siblingMethod")));
    }

    @Test
    void thePackageIsNotClimbedPast() {
        Element pkg = element("package p");
        assertTrue(pkg instanceof PackageElement, pkg.getClass().getName());
        assertSame(pkg, MethodBodyGuardrailScanner.topLevel(pkg), "a package's enclosing module is not a type");
    }

    @Test
    void theFixtureModelsEveryShapeTheTestNames() {
        nestedInOuter().forEach(a -> element((String) a.get()[0]));
        assertEquals(ElementKind.RECORD_COMPONENT, element("Outer/Point/x").getKind());
        assertEquals(ElementKind.ENUM_CONSTANT, element("Outer/Color/RED").getKind());
        assertEquals(ElementKind.TYPE_PARAMETER, element("Outer/generic/<T>").getKind());
        assertEquals(ElementKind.ANNOTATION_TYPE, element("Outer/Marker").getKind());
    }

    // ------------------------------------------------------------------ every ElementKind

    /**
     * The enclosing kinds the walk climbs through: types, members and executables. Written out
     * rather than derived from {@code ElementKind.isClass()} and friends, so a JDK that widens one of
     * those predicates shows up here as a decision to make, not as silently different behaviour.
     */
    private static final Set<ElementKind> CLIMBS = EnumSet.of(
        ElementKind.CLASS, ElementKind.ENUM, ElementKind.RECORD,
        ElementKind.INTERFACE, ElementKind.ANNOTATION_TYPE,
        ElementKind.METHOD, ElementKind.CONSTRUCTOR, ElementKind.STATIC_INIT, ElementKind.INSTANCE_INIT,
        ElementKind.FIELD, ElementKind.ENUM_CONSTANT);

    /** A bare Element with just a kind and an enclosing element, enough for the walk. */
    private static Element fake(ElementKind kind, Element enclosing) {
        return (Element) Proxy.newProxyInstance(Element.class.getClassLoader(), new Class<?>[]{Element.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getKind" -> kind;
                case "getEnclosingElement" -> enclosing;
                case "toString" -> kind + "@" + System.identityHashCode(proxy);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    @ParameterizedTest(name = "enclosed by {0}")
    @EnumSource(ElementKind.class)
    void anElementClimbsOutOfExactlyTheTypeMemberAndExecutableKinds(ElementKind enclosingKind) {
        Element pkg = fake(ElementKind.PACKAGE, null);
        Element enclosing = fake(enclosingKind, pkg);
        Element element = fake(ElementKind.METHOD, enclosing);

        Element expected = CLIMBS.contains(enclosingKind) ? enclosing : element;
        assertSame(expected, MethodBodyGuardrailScanner.topLevel(element),
            "an element enclosed by " + enclosingKind + (CLIMBS.contains(enclosingKind) ? " climbs to it" : " stays put"));
    }

    @Test
    void anElementWithNothingAroundItIsItsOwnTopLevel() {
        Element alone = fake(ElementKind.CLASS, null);
        assertSame(alone, MethodBodyGuardrailScanner.topLevel(alone));
    }

    @Test
    void theWalkClimbsAnyNumberOfLevelsAndStopsAtThePackage() {
        Element pkg = fake(ElementKind.PACKAGE, null);
        Element outermost = fake(ElementKind.CLASS, pkg);
        Element current = outermost;
        ElementKind[] shapes = {ElementKind.CLASS, ElementKind.METHOD, ElementKind.CLASS, ElementKind.FIELD,
            ElementKind.ENUM, ElementKind.ENUM_CONSTANT, ElementKind.INTERFACE, ElementKind.CONSTRUCTOR,
            ElementKind.RECORD, ElementKind.STATIC_INIT, ElementKind.ANNOTATION_TYPE, ElementKind.INSTANCE_INIT};
        for (ElementKind kind : shapes) {
            current = fake(kind, current);
        }
        assertSame(outermost, MethodBodyGuardrailScanner.topLevel(fake(ElementKind.PARAMETER, current)));
    }

    @Test
    void aStopKindHalfwayUpEndsTheWalkThere() {
        Element pkg = fake(ElementKind.PACKAGE, null);
        Element outer = fake(ElementKind.CLASS, pkg);
        Element module = fake(ElementKind.MODULE, outer);
        Element inner = fake(ElementKind.CLASS, module);
        Element member = fake(ElementKind.FIELD, inner);
        assertSame(inner, MethodBodyGuardrailScanner.topLevel(member),
            "an enclosing kind outside the climb set ends the walk even with a type above it");
    }

    @Test
    void theClimbSetIsTheTypeMemberAndExecutableKindsOfThisJdk() {
        for (ElementKind kind : ElementKind.values()) {
            boolean predicate = kind.isClass() || kind.isInterface() || kind.isExecutable() || kind.isField();
            assertEquals(predicate, CLIMBS.contains(kind),
                kind + ": this JDK classifies it differently from the climb set; decide which side it belongs on");
        }
    }
}
