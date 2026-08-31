package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.processor.internal.validation.AttributeRule;
import se.deversity.vibetags.processor.internal.validation.ModernJavaRules;
import se.deversity.vibetags.processor.internal.validation.ValidationContext;
import se.deversity.vibetags.processor.internal.validation.ValidationRule;

import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every {@link ModernJavaRules} detector must fire on the construct it exists for, and stay quiet
 * on the correct counterpart.
 *
 * <p>The mutation report showed the package in the same state {@code CoreRules} was in before
 * {@code CoreRulesEveryRuleFiresTest}: 23 of the class's 29 mutants survived, every one of its
 * eight {@code ctx.warn}/{@code ctx.note} calls could be deleted without a failure, and
 * {@code ModernJavaRules.all()} could return an empty list — unregistering the whole rule set —
 * with the suite still green. The existing tests only covered the degraded-environment edges
 * (an element that is not a method, an enclosing chain that runs out).
 *
 * <p>Both directions matter. A detector that stops firing silently loses its guardrail; one that
 * fires on correct code is how a team ends up muting the whole processor.
 */
class ModernJavaRulesEveryRuleFiresTest {

    @Test
    @DisplayName("all() registers five attribute rules and three unnamed-package rules")
    void allRegistersEveryDetector() {
        List<ValidationRule> rules = ModernJavaRules.all();
        assertEquals(8, rules.size(), "a detector was dropped from ModernJavaRules.all(): " + rules);

        Set<Class<? extends Annotation>> attribute = Set.of(AIImmutable.class, AIExtensible.class,
            AIPure.class, AIPublicAPI.class, AIThreadSafe.class);
        Set<Class<? extends Annotation>> unnamed = Set.of(AILocked.class, AIContract.class,
            AIPublicAPI.class);
        for (Class<? extends Annotation> type : attribute) {
            attributeRuleFor(type);
        }
        for (Class<? extends Annotation> type : unnamed) {
            unnamedPackageRuleFor(type);
        }
    }

    // ------------------------------------------------------------ @AIImmutable: shallow arrays

    @Test
    @DisplayName("@AIImmutable on a class with an instance array field warns and names the field")
    void immutableArrayFieldWarnsOnAClass() {
        Element arrayField = field("buffer", TypeKind.ARRAY, Set.of(Modifier.PRIVATE, Modifier.FINAL));
        // A method whose type also reads as an array must not be mistaken for a field.
        Element arrayReturningMethod = mock(Element.class);
        when(arrayReturningMethod.getKind()).thenReturn(ElementKind.METHOD);
        Element type = type(ElementKind.CLASS, arrayField, arrayReturningMethod);

        Recorder recorder = checkAttribute(AIImmutable.class, type);

        assertEquals(1, recorder.messages.size(), "one array field, one warning: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("'buffer' is an array"),
            "the warning must name the field to copy defensively: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("freezes the reference"),
            "the warning must say why final is not enough: " + recorder.messages);
    }

    @Test
    @DisplayName("@AIImmutable on a record with an array component also names the generated accessor")
    void immutableArrayFieldOnARecordNamesTheAccessor() {
        Element component = field("bytes", TypeKind.ARRAY, Set.of(Modifier.PRIVATE, Modifier.FINAL));
        Element record = type(ElementKind.RECORD, component);

        Recorder recorder = checkAttribute(AIImmutable.class, record);

        assertEquals(1, recorder.messages.size(), "one component, one warning: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("generated accessor"),
            "on a record the warning must point at the accessor handing the array out: "
                + recorder.messages);
    }

    @Test
    @DisplayName("@AIImmutable stays quiet on static array fields and non-array fields")
    void immutableStaysQuietWithoutAnInstanceArrayField() {
        Element staticArray = field("CACHE", TypeKind.ARRAY, Set.of(Modifier.STATIC, Modifier.FINAL));
        Element scalar = field("count", TypeKind.INT, Set.of(Modifier.PRIVATE, Modifier.FINAL));
        Element type = type(ElementKind.CLASS, staticArray, scalar);

        Recorder recorder = checkAttribute(AIImmutable.class, type);

        assertEquals(List.of(), recorder.messages,
            "a static array is shared state, not instance state, and an int cannot leak: "
                + recorder.messages);
    }

    // ------------------------------------------------------------ @AIExtensible: nothing can extend

    @Test
    @DisplayName("@AIExtensible warns on a record, an enum, a final class and a sealed class")
    void extensibleWarnsOnEveryUnextendableShape() {
        assertExtensibleWarning(type(ElementKind.RECORD), "a record");
        assertExtensibleWarning(type(ElementKind.ENUM), "an enum");

        Element finalClass = type(ElementKind.CLASS);
        when(finalClass.getModifiers()).thenReturn(Set.of(Modifier.FINAL));
        assertExtensibleWarning(finalClass, "declared final");

        Element sealedClass = type(ElementKind.CLASS);
        when(sealedClass.getModifiers()).thenReturn(Set.of(Modifier.SEALED));
        assertExtensibleWarning(sealedClass, "sealed");
    }

    @Test
    @DisplayName("@AIExtensible stays quiet on an open class")
    void extensibleStaysQuietOnAnOpenClass() {
        Recorder recorder = checkAttribute(AIExtensible.class, type(ElementKind.CLASS));

        assertEquals(List.of(), recorder.messages,
            "an open class is exactly what the annotation asks for: " + recorder.messages);
    }

    private void assertExtensibleWarning(Element type, String expectedPhrase) {
        Recorder recorder = checkAttribute(AIExtensible.class, type);
        assertEquals(1, recorder.messages.size(),
            "expected one warning mentioning '" + expectedPhrase + "': " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains(expectedPhrase),
            "the warning must say why nothing can extend the type: " + recorder.messages);
    }

    // ------------------------------------------------------------ @AIPure: void methods

    @Test
    @DisplayName("@AIPure on a void method warns; on a value-returning method it stays quiet")
    void pureFiresOnVoidOnly() {
        Recorder onVoid = checkAttribute(AIPure.class, method(TypeKind.VOID));
        assertEquals(1, onVoid.messages.size(), "expected one warning: " + onVoid.messages);
        assertTrue(onVoid.messages.get(0).contains("returns void"),
            "the warning must name the contradiction: " + onVoid.messages);

        Recorder onValue = checkAttribute(AIPure.class, method(TypeKind.INT));
        assertEquals(List.of(), onValue.messages,
            "a value-returning method is what purity looks like: " + onValue.messages);
    }

    // ------------------------------------------------------------ @AIPublicAPI: reachability

    @Test
    @DisplayName("@AIPublicAPI on a non-public element warns about the element itself")
    void publicApiWarnsOnTheElementItself() {
        PackageElement pkg = unnamablePackage(false);
        Element element = type(ElementKind.CLASS);
        when(element.getEnclosingElement()).thenReturn(pkg);

        Recorder recorder = checkAttribute(AIPublicAPI.class, element);

        assertEquals(1, recorder.messages.size(), "expected one warning: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("it is not public"),
            "the barrier is the element itself and the warning must say so: " + recorder.messages);
    }

    @Test
    @DisplayName("@AIPublicAPI on a public member of a non-public type names the enclosing barrier")
    void publicApiNamesTheEnclosingBarrier() {
        Element hidden = type(ElementKind.CLASS);
        when(hidden.toString()).thenReturn("com.example.Hidden");

        Element member = type(ElementKind.METHOD);
        when(member.getModifiers()).thenReturn(Set.of(Modifier.PUBLIC));
        when(member.getEnclosingElement()).thenReturn(hidden);

        Recorder recorder = checkAttribute(AIPublicAPI.class, member);

        assertEquals(1, recorder.messages.size(), "expected one warning: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("the enclosing com.example.Hidden is not public"),
            "the warning must name the declaration the developer has to open up: "
                + recorder.messages);
    }

    @Test
    @DisplayName("@AIPublicAPI stays quiet when the whole enclosing chain is public")
    void publicApiStaysQuietOnAPublicChain() {
        PackageElement pkg = unnamablePackage(false);
        Element outer = type(ElementKind.CLASS);
        when(outer.getModifiers()).thenReturn(Set.of(Modifier.PUBLIC));
        when(outer.getEnclosingElement()).thenReturn(pkg);

        Element element = type(ElementKind.CLASS);
        when(element.getModifiers()).thenReturn(Set.of(Modifier.PUBLIC));
        when(element.getEnclosingElement()).thenReturn(outer);

        Recorder recorder = checkAttribute(AIPublicAPI.class, element);

        assertEquals(List.of(), recorder.messages,
            "public all the way up to the package means the promise can be kept: "
                + recorder.messages);
    }

    // ------------------------------------------------------------ @AIThreadSafe: ThreadLocal strategy

    @Test
    @DisplayName("@AIThreadSafe(THREAD_LOCAL) with a ThreadLocal field notes the first such field")
    void threadLocalStrategyNotesTheFirstThreadLocalField() {
        Element first = field("firstCache", "java.lang.ThreadLocal<java.lang.String>");
        Element second = field("secondCache", "java.lang.ThreadLocal<java.lang.Integer>");
        Element type = type(ElementKind.CLASS, first, second);

        Recorder recorder = checkThreadSafe(type, AIThreadSafe.Strategy.THREAD_LOCAL);

        assertEquals(1, recorder.messages.size(),
            "one note per type, not one per field: " + recorder.messages);
        assertTrue(recorder.messages.get(0).startsWith("NOTE|"),
            "advisory, not a warning — the strategy is still correct: " + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("'firstCache'"),
            "the note must name the first ThreadLocal field in declaration order: "
                + recorder.messages);
        assertTrue(recorder.messages.get(0).contains("ScopedValue"),
            "the note exists to point at the replacement: " + recorder.messages);
    }

    @Test
    @DisplayName("@AIThreadSafe stays quiet for other strategies and for non-ThreadLocal fields")
    void threadLocalStrategyStaysQuietOtherwise() {
        Element cache = field("cache", "java.lang.ThreadLocal<java.lang.String>");
        Recorder otherStrategy = checkThreadSafe(type(ElementKind.CLASS, cache),
            AIThreadSafe.Strategy.IMMUTABLE);
        assertEquals(List.of(), otherStrategy.messages,
            "the note is about the THREAD_LOCAL strategy, not the field: " + otherStrategy.messages);

        Element plainField = field("name", "java.lang.String");
        Element threadLocalMethod = mock(Element.class);
        when(threadLocalMethod.getKind()).thenReturn(ElementKind.METHOD);
        Recorder noSuchField = checkThreadSafe(type(ElementKind.CLASS, plainField, threadLocalMethod),
            AIThreadSafe.Strategy.THREAD_LOCAL);
        assertEquals(List.of(), noSuchField.messages,
            "no ThreadLocal field means the declared strategy holds nothing to note: "
                + noSuchField.messages);
    }

    // ------------------------------------------------------------ unnamed-package identity

    @Test
    @DisplayName("the unnamed-package rules warn in the unnamed package and stay quiet in a named one")
    void unnamedPackageRulesFireBothWays() {
        for (Class<? extends Annotation> type : List.of(AILocked.class, AIContract.class,
                AIPublicAPI.class)) {
            ValidationRule rule = unnamedPackageRuleFor(type);

            PackageElement unnamedPkg = unnamablePackage(true);
            Element unnamed = type(ElementKind.CLASS);
            when(unnamed.getEnclosingElement()).thenReturn(unnamedPkg);
            Recorder fired = new Recorder();
            rule.check(fired.ctx, unnamed);
            assertEquals(1, fired.messages.size(),
                "@" + type.getSimpleName() + " in the unnamed package collides on its simple name "
                    + "and must be reported: " + fired.messages);
            assertTrue(fired.messages.get(0).contains("@" + type.getSimpleName())
                    && fired.messages.get(0).contains("unnamed package"),
                "the warning must name both the annotation and the hazard: " + fired.messages);

            PackageElement namedPkg = unnamablePackage(false);
            Element named = type(ElementKind.CLASS);
            when(named.getEnclosingElement()).thenReturn(namedPkg);
            Recorder quiet = new Recorder();
            rule.check(quiet.ctx, named);
            assertEquals(List.of(), quiet.messages,
                "@" + type.getSimpleName() + " in a named package has a stable identity: "
                    + quiet.messages);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Collects what a rule reported, as {@code KIND|message}. */
    private static final class Recorder {
        private final List<String> messages = new ArrayList<>();
        private final ValidationContext ctx;

        Recorder() {
            Messager messager = mock(Messager.class);
            doAnswer(invocation -> {
                messages.add(invocation.getArgument(0) + "|" + invocation.getArgument(1));
                return null;
            }).when(messager).printMessage(any(Diagnostic.Kind.class), anyString(), any(Element.class));
            ctx = new ValidationContext(messager, mock(RoundEnvironment.class), null, null);
        }
    }

    /** Runs the attribute rule for {@code type} against {@code element} carrying a bare mock of it. */
    private static Recorder checkAttribute(Class<? extends Annotation> type, Element element) {
        stubAnnotation(element, type, mock(type));
        Recorder recorder = new Recorder();
        attributeRuleFor(type).check(recorder.ctx, element);
        return recorder;
    }

    private static Recorder checkThreadSafe(Element element, AIThreadSafe.Strategy strategy) {
        AIThreadSafe annotation = mock(AIThreadSafe.class);
        when(annotation.strategy()).thenReturn(strategy);
        stubAnnotation(element, AIThreadSafe.class, annotation);
        Recorder recorder = new Recorder();
        attributeRuleFor(AIThreadSafe.class).check(recorder.ctx, element);
        return recorder;
    }

    private static void stubAnnotation(Element element, Class<? extends Annotation> type,
            Annotation annotation) {
        doAnswer(invocation -> invocation.getArgument(0) == type ? annotation : null)
            .when(element).getAnnotation(any());
    }

    private static ValidationRule attributeRuleFor(Class<? extends Annotation> type) {
        return ruleFor(type, true);
    }

    private static ValidationRule unnamedPackageRuleFor(Class<? extends Annotation> type) {
        return ruleFor(type, false);
    }

    private static ValidationRule ruleFor(Class<? extends Annotation> type, boolean attribute) {
        return ModernJavaRules.all().stream()
            .filter(r -> r.scans() == type && (r instanceof AttributeRule) == attribute)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + (attribute ? "attribute" : "unnamed-package")
                + " rule registered for @" + type.getSimpleName()));
    }

    /** A type-shaped element of {@code kind} named {@code com.example.Target}, enclosing the rest. */
    private static Element type(ElementKind kind, Element... enclosed) {
        Element type = mock(Element.class);
        when(type.toString()).thenReturn("com.example.Target");
        when(type.getKind()).thenReturn(kind);
        when(type.getModifiers()).thenReturn(Set.of());
        doAnswer(invocation -> List.of(enclosed)).when(type).getEnclosedElements();
        return type;
    }

    /** A method-shaped executable element whose return type has {@code returnKind}. */
    private static Element method(TypeKind returnKind) {
        ExecutableElement method = mock(ExecutableElement.class);
        when(method.toString()).thenReturn("com.example.Target.compute()");
        TypeMirror returns = mock(TypeMirror.class);
        when(returns.getKind()).thenReturn(returnKind);
        when(method.getReturnType()).thenReturn(returns);
        return method;
    }

    /** An instance or static field whose declared type has {@code kind}. */
    private static Element field(String name, TypeKind kind, Set<Modifier> modifiers) {
        Element field = mock(Element.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        when(field.getModifiers()).thenReturn(modifiers);
        TypeMirror mirror = mock(TypeMirror.class);
        when(mirror.getKind()).thenReturn(kind);
        when(field.asType()).thenReturn(mirror);
        Name simpleName = mock(Name.class);
        when(simpleName.toString()).thenReturn(name);
        when(field.getSimpleName()).thenReturn(simpleName);
        return field;
    }

    /** A private instance field whose declared type prints as {@code typeName}. */
    private static Element field(String name, String typeName) {
        Element field = field(name, TypeKind.DECLARED, Set.of(Modifier.PRIVATE));
        TypeMirror mirror = mock(TypeMirror.class);
        when(mirror.toString()).thenReturn(typeName);
        when(field.asType()).thenReturn(mirror);
        return field;
    }

    /** A package element, unnamed or named, that also ends a modifier walk (no PUBLIC modifier). */
    private static PackageElement unnamablePackage(boolean unnamed) {
        PackageElement pkg = mock(PackageElement.class);
        when(pkg.getKind()).thenReturn(ElementKind.PACKAGE);
        when(pkg.isUnnamed()).thenReturn(unnamed);
        return pkg;
    }
}
