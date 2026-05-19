package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.ElementNaming;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ElementNaming} pure-function helpers.
 * Targets the edge-case branches not reached by the integration tests:
 * <ul>
 *   <li>The fallback {@code return e;} in {@code owningElement()} when the element
 *       hierarchy has no {@link TypeElement} or PACKAGE ancestor.</li>
 *   <li>The {@code kind == null} guard in the same loop.</li>
 *   <li>CONSTRUCTOR kind in {@code elementPath} and {@code elementDisplayName}.</li>
 * </ul>
 */
class ElementNamingUnitTest {

    // -----------------------------------------------------------------------
    // owningElement()
    // -----------------------------------------------------------------------

    @Test
    void owningElement_typeElement_returnsSelf() {
        TypeElement te = mock(TypeElement.class);
        assertSame(te, ElementNaming.owningElement(te),
            "A TypeElement should be returned as its own owning element");
    }

    @Test
    void owningElement_packageKind_returnsSelf() {
        Element pkg = mock(Element.class);
        when(pkg.getKind()).thenReturn(ElementKind.PACKAGE);
        assertSame(pkg, ElementNaming.owningElement(pkg),
            "A PACKAGE element should be returned as its own owning element");
    }

    @Test
    void owningElement_fieldEnclosedInTypeElement_returnsEnclosingType() {
        TypeElement enclosing = mock(TypeElement.class);

        Element field = mock(Element.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        when(field.getEnclosingElement()).thenReturn(enclosing);

        assertSame(enclosing, ElementNaming.owningElement(field),
            "A FIELD element should return its enclosing TypeElement");
    }

    @Test
    void owningElement_noTypeElementAncestor_returnsSelf() {
        // Exercises the fallback 'return e;' at the end of the loop — reached when
        // the element hierarchy ends (getEnclosingElement() returns null) without
        // ever encountering a TypeElement or PACKAGE element.
        Element orphan = mock(Element.class);
        when(orphan.getKind()).thenReturn(ElementKind.LOCAL_VARIABLE);
        when(orphan.getEnclosingElement()).thenReturn(null);

        assertSame(orphan, ElementNaming.owningElement(orphan),
            "owningElement must fall back to the original element when no ancestor qualifies");
    }

    @Test
    void owningElement_nullKind_doesNotThrow_andTraversesHierarchy() {
        // A mock element where getKind() returns null (defensive guard in owningElement).
        // The loop must handle null kind gracefully and continue traversal.
        TypeElement enclosing = mock(TypeElement.class);

        Element elem = mock(Element.class);
        when(elem.getKind()).thenReturn(null);            // null kind — edge case
        when(elem.getEnclosingElement()).thenReturn(enclosing);

        assertSame(enclosing, ElementNaming.owningElement(elem),
            "Null kind must not cause NPE; traversal should find the TypeElement ancestor");
    }

    // -----------------------------------------------------------------------
    // elementPath()
    // -----------------------------------------------------------------------

    @Test
    void elementPath_typeElement_usesToString() {
        Element type = mock(Element.class);
        when(type.getKind()).thenReturn(ElementKind.CLASS);
        when(type.toString()).thenReturn("com.example.Foo");

        assertEquals("com.example.Foo", ElementNaming.elementPath(type));
    }

    @Test
    void elementPath_field_prependsEnclosingFQN() {
        Element enclosing = mock(Element.class);
        when(enclosing.toString()).thenReturn("com.example.Foo");

        Element field = mock(Element.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        when(field.toString()).thenReturn("email");
        when(field.getEnclosingElement()).thenReturn(enclosing);

        assertEquals("com.example.Foo.email", ElementNaming.elementPath(field));
    }

    @Test
    void elementPath_method_prependsEnclosingFQN() {
        Element enclosing = mock(Element.class);
        when(enclosing.toString()).thenReturn("com.example.Foo");

        Element method = mock(Element.class);
        when(method.getKind()).thenReturn(ElementKind.METHOD);
        when(method.toString()).thenReturn("process(java.lang.String)");
        when(method.getEnclosingElement()).thenReturn(enclosing);

        assertEquals("com.example.Foo.process(java.lang.String)", ElementNaming.elementPath(method));
    }

    @Test
    void elementPath_constructor_prependsEnclosingFQN() {
        // CONSTRUCTOR kind must follow the same path as FIELD/METHOD.
        Element enclosing = mock(Element.class);
        when(enclosing.toString()).thenReturn("com.example.Foo");

        Element ctor = mock(Element.class);
        when(ctor.getKind()).thenReturn(ElementKind.CONSTRUCTOR);
        when(ctor.toString()).thenReturn("<init>()");
        when(ctor.getEnclosingElement()).thenReturn(enclosing);

        assertEquals("com.example.Foo.<init>()", ElementNaming.elementPath(ctor));
    }

    @Test
    void elementPath_fieldWithNullEnclosing_usesToString() {
        // If enclosing is null, falls back to element.toString().
        Element field = mock(Element.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        when(field.toString()).thenReturn("orphanField");
        when(field.getEnclosingElement()).thenReturn(null);

        assertEquals("orphanField", ElementNaming.elementPath(field));
    }

    // -----------------------------------------------------------------------
    // elementDisplayName()
    // -----------------------------------------------------------------------

    @Test
    void elementDisplayName_typeElement_usesSimpleName() {
        Name simpleName = mock(Name.class);
        when(simpleName.toString()).thenReturn("Foo");

        Element type = mock(Element.class);
        when(type.getKind()).thenReturn(ElementKind.CLASS);
        when(type.getSimpleName()).thenReturn(simpleName);

        assertEquals("Foo", ElementNaming.elementDisplayName(type));
    }

    @Test
    void elementDisplayName_field_usesEnclosingSimpleNameDotMember() {
        Name enclosingName = mock(Name.class);
        when(enclosingName.toString()).thenReturn("UserProfile");

        Element enclosing = mock(Element.class);
        when(enclosing.getSimpleName()).thenReturn(enclosingName);

        Element field = mock(Element.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        when(field.toString()).thenReturn("email");
        when(field.getEnclosingElement()).thenReturn(enclosing);

        assertEquals("UserProfile.email", ElementNaming.elementDisplayName(field));
    }

    @Test
    void elementDisplayName_constructor_usesEnclosingSimpleNameDotMember() {
        // CONSTRUCTOR kind must follow the FIELD/METHOD path.
        Name enclosingName = mock(Name.class);
        when(enclosingName.toString()).thenReturn("PaymentService");

        Element enclosing = mock(Element.class);
        when(enclosing.getSimpleName()).thenReturn(enclosingName);

        Element ctor = mock(Element.class);
        when(ctor.getKind()).thenReturn(ElementKind.CONSTRUCTOR);
        when(ctor.toString()).thenReturn("<init>(double)");
        when(ctor.getEnclosingElement()).thenReturn(enclosing);

        assertEquals("PaymentService.<init>(double)", ElementNaming.elementDisplayName(ctor));
    }

    @Test
    void elementDisplayName_fieldWithNullEnclosing_usesToStringFallback() {
        // Null enclosing falls through to the toString() branch of elementDisplayName.
        // But wait — the code uses element.toString() in elementPath but elementDisplayName
        // falls through the outer 'return element.getSimpleName().toString()'.
        Name simpleName = mock(Name.class);
        when(simpleName.toString()).thenReturn("orphanField");

        Element field = mock(Element.class);
        when(field.getKind()).thenReturn(ElementKind.FIELD);
        when(field.getEnclosingElement()).thenReturn(null);
        when(field.getSimpleName()).thenReturn(simpleName);

        assertEquals("orphanField", ElementNaming.elementDisplayName(field));
    }
}
