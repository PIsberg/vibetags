package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The structural signature that {@code @AIContract} and {@code @AIPublicAPI} enforcement compares
 * against its stored baseline.
 *
 * <p>Two failures matter here and they pull in opposite directions. A signature that is too coarse
 * lets a genuinely breaking change through — a return type narrowed, a checked exception added —
 * and the baseline reports nothing, which is enforcement that does not enforce. A signature that is
 * too fine changes on things a caller cannot observe — declaration order, a private helper renamed
 * — and every ordinary refactor becomes a false alarm, which is enforcement people switch off.
 *
 * <p>{@code SignatureCaptureTest} checks the value that reaches {@code TaggedElement} through a
 * real compilation. This asks what the signature is made of, one element kind at a time, including
 * the kinds a compilation fixture cannot easily produce and the malformed element that must not
 * take a build down with it.
 */
class ElementSignatureTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /**
     * A {@link Name} that behaves like its text through {@link CharSequence} as well as
     * {@code toString()}. {@code StringBuilder.append} resolves to the {@code CharSequence}
     * overload for a {@code Name}, so a mock that only stubs {@code toString()} appends nothing
     * at all and the signature silently loses the element's name.
     */
    private static Name name(String text) {
        Name n = mock(Name.class);
        when(n.toString()).thenReturn(text);
        when(n.length()).thenReturn(text.length());
        when(n.charAt(anyInt())).thenAnswer(call -> text.charAt(call.getArgument(0)));
        when(n.subSequence(anyInt(), anyInt()))
            .thenAnswer(call -> text.subSequence(call.getArgument(0), call.getArgument(1)));
        return n;
    }

    private static TypeMirror type(String text) {
        TypeMirror t = mock(TypeMirror.class);
        when(t.toString()).thenReturn(text);
        return t;
    }

    // Every mock these build is created before the when(...) that consumes it: Mockito treats a
    // mock() or a stubbing evaluated inside an argument to when(...) as an unfinished stubbing.

    private static VariableElement field(String typeName, String fieldName, Modifier... modifiers) {
        TypeMirror fieldType = type(typeName);
        Name simpleName = name(fieldName);
        VariableElement v = mock(VariableElement.class);
        when(v.getKind()).thenReturn(ElementKind.FIELD);
        when(v.asType()).thenReturn(fieldType);
        when(v.getSimpleName()).thenReturn(simpleName);
        when(v.getModifiers()).thenReturn(Set.of(modifiers));
        return v;
    }

    private static ExecutableElement method(String methodName, String returnType,
                                            List<String> params, List<String> thrown,
                                            Modifier... modifiers) {
        Name simpleName = name(methodName);
        TypeMirror returns = type(returnType);
        List<VariableElement> parameters = params.stream()
            .map(p -> field(p, "p", Modifier.FINAL)).toList();
        List<TypeMirror> thrownTypes = thrown.stream().map(ElementSignatureTest::type).toList();

        ExecutableElement m = mock(ExecutableElement.class);
        when(m.getKind()).thenReturn(ElementKind.METHOD);
        when(m.getSimpleName()).thenReturn(simpleName);
        when(m.getReturnType()).thenReturn(returns);
        when(m.getModifiers()).thenReturn(Set.of(modifiers));
        doReturn(parameters).when(m).getParameters();
        doReturn(thrownTypes).when(m).getThrownTypes();
        return m;
    }

    private static TypeElement classOf(String superclass, List<String> interfaces,
                                       List<? extends Element> members) {
        TypeMirror superType = type(superclass);
        List<TypeMirror> interfaceTypes =
            interfaces.stream().map(ElementSignatureTest::type).toList();

        TypeElement t = mock(TypeElement.class);
        when(t.getKind()).thenReturn(ElementKind.CLASS);
        when(t.getSuperclass()).thenReturn(superType);
        doReturn(interfaceTypes).when(t).getInterfaces();
        doReturn(members).when(t).getEnclosedElements();
        return t;
    }

    // -----------------------------------------------------------------------
    // Methods
    // -----------------------------------------------------------------------

    @Test
    void aMethodSignatureCarriesParametersAndReturnType() {
        assertEquals("charge(java.lang.String,int):boolean",
            ElementSignature.of(method("charge", "boolean",
                List.of("java.lang.String", "int"), List.of())));
    }

    @Test
    void aThrownCheckedExceptionIsPartOfTheSignature() {
        // Adding a checked exception breaks every caller, so a signature that ignored the throws
        // clause would let the most compile-visible break there is through unnoticed.
        String withThrows = ElementSignature.of(
            method("charge", "boolean", List.of(), List.of("java.io.IOException")));
        String without = ElementSignature.of(
            method("charge", "boolean", List.of(), List.of()));

        assertTrue(withThrows.contains("throws java.io.IOException"), withThrows);
        assertFalse(without.contains("throws"),
            "a method that throws nothing must not carry an empty throws clause: " + without);
        assertFalse(withThrows.equals(without));
    }

    @Test
    void thrownTypesAreSortedSoDeclarationOrderIsNotPartOfTheContract() {
        // Reordering a throws clause is not a source-incompatible change; if it moved the
        // signature, swapping two names would raise a violation on a file nobody had touched.
        assertEquals(
            ElementSignature.of(method("f", "void", List.of(),
                List.of("java.io.IOException", "java.sql.SQLException"))),
            ElementSignature.of(method("f", "void", List.of(),
                List.of("java.sql.SQLException", "java.io.IOException"))));
    }

    @Test
    void aConstructorIsSignedLikeAMethod() {
        ExecutableElement ctor = method("<init>", "void", List.of("int"), List.of());
        when(ctor.getKind()).thenReturn(ElementKind.CONSTRUCTOR);
        assertEquals("<init>(int):void", ElementSignature.of(ctor));
    }

    // -----------------------------------------------------------------------
    // Variables
    // -----------------------------------------------------------------------

    @Test
    void aFieldIsSignedByItsTypeAndName() {
        assertEquals("java.math.BigDecimal balance",
            ElementSignature.of(field("java.math.BigDecimal", "balance")));
    }

    @Test
    void retypingAFieldChangesItsSignature() {
        assertFalse(ElementSignature.of(field("long", "id"))
            .equals(ElementSignature.of(field("java.lang.String", "id"))),
            "a field whose type changed is a break the baseline has to see");
    }

    @Test
    void everyVariableKindIsSignedRatherThanSkipped() {
        for (ElementKind kind : List.of(ElementKind.FIELD, ElementKind.ENUM_CONSTANT,
                                        ElementKind.RECORD_COMPONENT, ElementKind.PARAMETER)) {
            VariableElement v = field("int", "x");
            when(v.getKind()).thenReturn(kind);
            assertEquals("int x", ElementSignature.of(v), kind + " should be signed like a field");
        }
    }

    // -----------------------------------------------------------------------
    // Types
    // -----------------------------------------------------------------------

    @Test
    void aTypeSignatureCarriesItsSupertypesAndVisibleMembers() {
        String signature = ElementSignature.of(classOf(
            "java.lang.Object", List.of("java.io.Serializable"),
            List.of(method("charge", "boolean", List.of(), List.of(), Modifier.PUBLIC))));

        assertTrue(signature.contains("java.lang.Object"), signature);
        assertTrue(signature.contains("java.io.Serializable"), signature);
        assertTrue(signature.contains("charge():boolean"), signature);
    }

    @Test
    void implementedInterfacesAreSortedSoDeclarationOrderDoesNotMatter() {
        assertEquals(
            ElementSignature.of(classOf("java.lang.Object",
                List.of("a.Alpha", "b.Beta"), List.of())),
            ElementSignature.of(classOf("java.lang.Object",
                List.of("b.Beta", "a.Alpha"), List.of())));
    }

    @Test
    void droppingAnInterfaceChangesTheSignature() {
        assertFalse(
            ElementSignature.of(classOf("java.lang.Object", List.of("a.Alpha"), List.of()))
                .equals(ElementSignature.of(classOf("java.lang.Object", List.of(), List.of()))),
            "removing an implemented interface breaks callers that bound to it");
    }

    @Test
    void protectedMembersAreVisibleAndPackagePrivateOnesAreNot() {
        // Protected members are part of the surface a subclass in another package binds to;
        // package-private ones are not reachable from outside and must not churn the baseline.
        String withProtected = ElementSignature.of(classOf("java.lang.Object", List.of(),
            List.of(field("int", "seen", Modifier.PROTECTED))));
        String withPackagePrivate = ElementSignature.of(classOf("java.lang.Object", List.of(),
            List.of(field("int", "hidden"))));

        assertTrue(withProtected.contains("int seen"), withProtected);
        assertFalse(withPackagePrivate.contains("hidden"),
            "a package-private field must not reach the signature: " + withPackagePrivate);
        assertEquals("extends[java.lang.Object] members[]", withPackagePrivate);
    }

    @Test
    void privateMembersAreExcluded() {
        String signature = ElementSignature.of(classOf("java.lang.Object", List.of(),
            List.of(field("int", "cache", Modifier.PRIVATE))));
        assertEquals("extends[java.lang.Object] members[]", signature,
            "renaming a private field is not a break, so it must not move the signature");
    }

    @Test
    void membersAreSortedSoSourceOrderIsNotPartOfTheContract() {
        List<? extends Element> oneOrder = List.of(
            field("int", "b", Modifier.PUBLIC), field("int", "a", Modifier.PUBLIC));
        List<? extends Element> other = List.of(
            field("int", "a", Modifier.PUBLIC), field("int", "b", Modifier.PUBLIC));
        assertEquals(
            ElementSignature.of(classOf("java.lang.Object", List.of(), oneOrder)),
            ElementSignature.of(classOf("java.lang.Object", List.of(), other)));
    }

    @Test
    void aVisibleMemberWithNoSignatureOfItsOwnIsSkipped() {
        // A nested initialiser or a member of an unhandled kind signs as "", and an empty entry in
        // the member list would be a stray ';' that changes whenever the compiler's element set does.
        Element odd = mock(Element.class);
        when(odd.getKind()).thenReturn(ElementKind.STATIC_INIT);
        when(odd.getModifiers()).thenReturn(Set.of(Modifier.PUBLIC));

        assertEquals("extends[java.lang.Object] members[]",
            ElementSignature.of(classOf("java.lang.Object", List.of(), List.of(odd))));
    }

    // -----------------------------------------------------------------------
    // Kinds with no signature, and elements that misbehave
    // -----------------------------------------------------------------------

    @Test
    void anElementKindWithNoStructuralSurfaceSignsAsEmpty() {
        for (ElementKind kind : List.of(ElementKind.PACKAGE, ElementKind.MODULE,
                                        ElementKind.TYPE_PARAMETER, ElementKind.OTHER)) {
            Element e = mock(Element.class);
            when(e.getKind()).thenReturn(kind);
            assertEquals("", ElementSignature.of(e), kind + " should have no signature");
        }
    }

    @Test
    void anElementThatThrowsWhileBeingInspectedSignsAsEmptyRatherThanFailingTheCompile() {
        // Enforcement is advisory scaffolding bolted onto someone else's build. A compiler
        // implementation that throws from asType() must cost them a baseline entry, not a build.
        VariableElement hostile = mock(VariableElement.class);
        when(hostile.getKind()).thenReturn(ElementKind.FIELD);
        when(hostile.asType()).thenThrow(new IllegalStateException("no type here"));

        assertEquals("", ElementSignature.of(hostile));
    }

    @Test
    void aNullTypeMirrorErasesToEmptyRatherThanTheStringNull() {
        // getSuperclass() answers a NoType for java.lang.Object and null in some implementations;
        // the literal text "null" in a baseline would be indistinguishable from a real type name.
        TypeElement t = mock(TypeElement.class);
        when(t.getKind()).thenReturn(ElementKind.INTERFACE);
        when(t.getSuperclass()).thenReturn(null);
        doReturn(List.of()).when(t).getInterfaces();
        doReturn(List.of()).when(t).getEnclosedElements();

        assertEquals("extends[] members[]", ElementSignature.of(t));
    }
}
