package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.AnnotationDescriptor;
import se.deversity.vibetags.processor.internal.content.AnnotationDescriptors;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every annotation's fingerprint tag is its own.
 *
 * <p>{@code BuildFingerprint} folds each annotation's contents into the hash under a short tag,
 * the {@code fingerprintTag} of its entry in {@code AnnotationDescriptors.ALL}. This test used to
 * parse the forty-four {@code appendAnnotationSet(sb, "L", model.locked(), ...)} calls out of the
 * source; it reads the table now, which is what {@code BuildFingerprint} itself reads, so the tags
 * checked here are the tags hashed rather than the ones a pattern happened to match. The
 * {@code add-annotation} skill says those tags must be unique, and until this test nothing
 * checked it: {@code "LB"} was used by both
 * {@code legacyBridge} and {@code loadBearing}
 * (<a href="https://github.com/PIsberg/vibetags/issues/765">issue #765</a>).
 *
 * <p>Two annotations sharing a tag was harmless, because the sections are positional: each
 * call appends at a fixed point in a fixed order, so the tag is a label in the hashed string rather
 * than a key anything looks up. It stops being harmless the moment anything keys on the tag, or the
 * order stops being fixed, and the failure then is a fingerprint that cannot tell two different
 * builds apart, which is the quietest kind of wrong this processor can produce.
 *
 * <p>That collision was first allowed here by name, because fixing it changes the hashed string and
 * so invalidates every consumer's {@code .vibetags-cache} once. It was fixed right after a release was
 * prepared ({@code loadBearing} is now {@code "LDB"}), the cost being one missed short-circuit
 * per consumer, and {@link #KNOWN_COLLISIONS} is empty: the rule is enforced
 * outright. The map stays so that a future collision that truly cannot be fixed at once has
 * somewhere to be recorded as a decision rather than a relaxed rule.
 */
@DisplayName("Fingerprint tags are unique per annotation")
class BuildFingerprintTagUniquenessTest {

    /**
     * Collisions that exist today and are deliberately not fixed yet, tag to reason.
     *
     * <p>An entry here is a decision somebody has to remove, not a rule somebody quietly relaxed.
     */
    private static final Map<String, String> KNOWN_COLLISIONS = Map.of();

    @Test
    @DisplayName("no two annotations share a tag, beyond the collisions recorded here")
    void everyFingerprintTagIsUniqueOrAKnownCollision() {
        Map<String, List<String>> byTag = tagsToAnnotations();

        Map<String, List<String>> collisions = new LinkedHashMap<>();
        byTag.forEach((tag, annotations) -> {
            if (annotations.size() > 1) {
                collisions.put(tag, annotations);
            }
        });

        Set<String> unexpected = new LinkedHashSet<>(collisions.keySet());
        unexpected.removeAll(KNOWN_COLLISIONS.keySet());
        assertEquals(Set.of(), unexpected,
            "two annotations share a fingerprint tag: " + collisions
                + ". The add-annotation skill requires tags to be unique. A tag is a label in the "
                + "hashed string today, so this is not yet a wrong fingerprint, but it is the rule "
                + "being broken silently. Pick a free tag, or record it in KNOWN_COLLISIONS with "
                + "the reason it cannot be fixed now.");
    }

    /**
     * The allowance cannot outlive the collision it excuses. A stale entry here would silently
     * permit a future collision on the same tag, which is the failure this file exists to prevent.
     */
    @Test
    @DisplayName("every recorded collision still exists")
    void noKnownCollisionIsStale() {
        Map<String, List<String>> byTag = tagsToAnnotations();

        KNOWN_COLLISIONS.forEach((tag, reason) -> {
            List<String> annotations = byTag.getOrDefault(tag, List.of());
            assertTrue(annotations.size() > 1,
                "KNOWN_COLLISIONS still allows the tag " + tag + ", but it is no longer shared "
                    + "(used by " + annotations + "). Delete the entry: leaving it in place lets a "
                    + "new collision on that tag pass unnoticed.");
        });
    }

    /**
     * Every annotation in the registry has exactly one descriptor, so exactly one section of the
     * hash. Compared as sets on purpose: the table's order is its own and is pinned elsewhere.
     */
    @Test
    @DisplayName("one descriptor per registered annotation")
    void everyRegisteredAnnotationIsHashed() {
        List<Class<? extends Annotation>> described = new ArrayList<>();
        for (AnnotationDescriptor descriptor : AnnotationDescriptors.ALL) {
            described.add(descriptor.type());
        }

        assertEquals(new LinkedHashSet<>(described).size(), described.size(),
            "an annotation has two descriptors, so BuildFingerprint hashes it twice and every "
                + "table-driven renderer prints it twice: " + described);
        assertEquals(new HashSet<>(GuardrailAnnotations.ALL), new HashSet<>(described),
            "AnnotationDescriptors.ALL and GuardrailAnnotations.ALL do not hold the same "
                + "annotations. Anything that becomes generated content reaches BuildFingerprint "
                + "(invariant 12); an annotation missing from the table changes the output "
                + "without changing the hash, so the write cache serves the old file.");
    }

    /** Tag to the annotations that use it, read from the table {@code BuildFingerprint} walks. */
    private static Map<String, List<String>> tagsToAnnotations() {
        Map<String, List<String>> byTag = new LinkedHashMap<>();
        for (AnnotationDescriptor descriptor : AnnotationDescriptors.ALL) {
            byTag.computeIfAbsent(descriptor.fingerprintTag(), k -> new ArrayList<>())
                .add(descriptor.type().getSimpleName());
        }
        assertTrue(byTag.size() > 30,
            "only " + byTag.size() + " fingerprint tags were read from AnnotationDescriptors.ALL. "
                + "A table that yields nothing passes every assertion below it.");
        return byTag;
    }
}
