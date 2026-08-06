package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.model.ElementTag;

import javax.lang.model.element.ElementKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins {@link ElementTag} to {@code javax.lang.model.element.ElementKind}.
 *
 * <p>The two enums are mapped by name, and the names leak into published output: the
 * {@code .vibetags-locks} JSON the bundled GitHub Action parses carries {@code kind}, and granular
 * rule-file headings carry it lower-cased. A silent mismatch would not fail a build — it would just
 * write "other" where it used to write "method", in a file somebody else's tooling reads.
 *
 * <p>If a future JDK adds an {@code ElementKind}, the first test here fails. That is the intent:
 * the constant should be added deliberately, not discovered from a corrupted lock report.
 */
class ElementTagMappingTest {

    @Test
    void everyElementKind_hasATagWithTheSameName() {
        for (ElementKind kind : ElementKind.values()) {
            assertNotNull(ElementTag.fromName(kind.name()),
                "ElementKind." + kind.name() + " has no ElementTag counterpart — add the constant to "
                    + "ElementTag, or generated output will report it as UNKNOWN");
        }
    }

    @Test
    void isClassAndIsInterface_agreeWithElementKind() {
        for (ElementKind kind : ElementKind.values()) {
            ElementTag tag = ElementTag.fromName(kind.name());
            assertNotNull(tag);
            assertEquals(kind.isClass(), tag.isClass(),
                "isClass() must agree with ElementKind for " + kind.name());
            assertEquals(kind.isInterface(), tag.isInterface(),
                "isInterface() must agree with ElementKind for " + kind.name());
        }
    }

    @Test
    void unknown_isNotAnElementKind() {
        // UNKNOWN is VibeTags' own "the compiler told us nothing" value, deliberately outside the
        // mirrored set — so a real ElementKind can never collide with it.
        for (ElementKind kind : ElementKind.values()) {
            assertEquals(false, kind.name().equals(ElementTag.UNKNOWN.name()),
                "ElementKind must not define a constant named UNKNOWN");
        }
    }

    @Test
    void fromName_returnsNullForAnUnknownName() {
        assertNull(ElementTag.fromName("NOT_A_KIND"));
        assertNull(ElementTag.fromName(null));
    }
}
