package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What {@link AnnotationDescriptors#ALL} must keep true now that five classes read it instead of
 * each spelling out forty-four arms
 * (<a href="https://github.com/PIsberg/vibetags/issues/765">issue #765</a>).
 *
 * <p>The table removed one kind of mistake, an arm forgotten in one of the five, and concentrated
 * another: an entry that is present but wired to its neighbour's formatter or extractor is now
 * wrong everywhere at once, and a count or a set comparison cannot see a swapped pair. So each
 * entry is checked against its own annotation, not just counted.
 *
 * <p>That the table holds the same annotations as {@link GuardrailAnnotations#ALL}, and that tags
 * are unique, is {@code BuildFingerprintTagUniquenessTest}'s. That every entry produces a stanza
 * and reaches every platform is {@code GranularRendererDropsNoAnnotationTest}'s and
 * {@code RendererDropsNoSupportedAnnotationTest}'s.
 */
@DisplayName("The per-annotation descriptor table")
class AnnotationDescriptorsTest {

    /**
     * The fingerprint's hashing order, written out. Append to it when an annotation is added. Any
     * other edit needed to make this pass is a reordered or renamed section of the hashed string,
     * which invalidates every consumer's {@code .vibetags-cache} once, and a reordered section of
     * every table-driven generated file.
     */
    private static final List<String> PINNED_TAG_ORDER = List.of(
        "L", "C", "I", "A", "D", "P", "K", "F", "T", "TD", "TS", "IM", "DP", "OB", "RG", "PT", "LB",
        "AR", "PA", "SE", "ST", "IT", "SC", "SS", "ID", "FF", "SEC", "CO", "SO", "MB", "PU", "DM",
        "EX", "IZ", "SL", "XP", "PR", "SN", "TM", "GEN", "LDB", "BA", "TA", "KIS");

    @Test
    @DisplayName("tags appear in the pinned order")
    void tagOrderIsPinned() {
        List<String> tags = new ArrayList<>();
        for (AnnotationDescriptor descriptor : AnnotationDescriptors.ALL) {
            tags.add(descriptor.fingerprintTag());
        }
        assertEquals(PINNED_TAG_ORDER, tags,
            "AnnotationDescriptors.ALL is append only: its order is the order BuildFingerprint "
                + "hashes in and the order sections are printed in");
    }

    @Test
    @DisplayName("an entry names its own annotation's formatter")
    void everyEntryCarriesItsOwnFormatter() {
        List<String> wrong = new ArrayList<>();
        for (AnnotationDescriptor descriptor : AnnotationDescriptors.ALL) {
            String expected = descriptor.type().getSimpleName() + "Formatter";
            String actual = descriptor.formatter().getClass().getSimpleName();
            if (!expected.equals(actual)) {
                wrong.add(descriptor.type().getSimpleName() + " -> " + actual);
            }
        }
        assertEquals(List.of(), wrong,
            "an entry wired to another annotation's formatter prints nothing for its own elements, "
                + "because that formatter asks the element for an annotation it does not carry");
    }

    @Test
    @DisplayName("an entry's extractor and stanza read its own annotation")
    void everyEntryReadsItsOwnAnnotation() {
        List<String> wrong = new ArrayList<>();
        for (AnnotationDescriptor descriptor : AnnotationDescriptors.ALL) {
            String name = descriptor.type().getSimpleName();
            TaggedElement own = GuardrailModels.element(descriptor.type());
            if (descriptor.fingerprintMembers().of(own).isEmpty()) {
                wrong.add(name + ": the extractor hashed nothing for a fully populated annotation");
            }
            String stanza = descriptor.granularStanza().of(own);
            if (stanza == null || stanza.isBlank()) {
                wrong.add(name + ": no stanza for a fully populated annotation");
            }
            if (descriptor.granularTitle().isBlank()) {
                wrong.add(name + ": blank stanza title");
            }
        }
        assertEquals(List.of(), wrong,
            "an extractor or stanza that reads a different annotation finds none on its own "
                + "elements, so it hashes or prints nothing and nothing else fails");
    }

    /**
     * The half of the check above that makes it mean something: the same extractor answers the
     * empty string for an element that does not carry the annotation, so a non-empty answer is
     * evidence the annotation was read rather than a constant every extractor returns.
     */
    @Test
    @DisplayName("an extractor hashes nothing for an element without the annotation")
    void extractorsAnswerEmptyWithoutAnInstance() {
        TaggedElement bare = TaggedElement.builder("com.example.Bare")
            .names("com.example.Bare", "Bare", "com.example.Bare", "com.example.Bare")
            .kind(ElementTag.CLASS)
            .signature("com.example.Bare")
            .build();
        for (AnnotationDescriptor descriptor : AnnotationDescriptors.ALL) {
            assertEquals("", descriptor.fingerprintMembers().of(bare), descriptor.type().getSimpleName());
        }
        assertNotEquals(0, AnnotationDescriptors.ALL.size());
    }
}
