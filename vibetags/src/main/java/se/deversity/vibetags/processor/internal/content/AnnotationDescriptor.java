package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.annotation.Annotation;

/**
 * Everything the table-driven consumers need to know about one {@code @AI...} annotation. One
 * entry of {@link AnnotationDescriptors#ALL}; the reasons for the shape are on that class.
 *
 * @param type               the annotation, which is also the key of its bucket in
 *                           {@code GuardrailModel}
 * @param fingerprintTag     the label {@code BuildFingerprint} opens this annotation's section
 *                           with. Unique per annotation, and part of the hashed string, so
 *                           changing one invalidates every consumer's cache
 * @param formatter          the annotation's formatter, for the renderers that print every
 *                           annotation through one platform arm
 * @param fingerprintMembers what {@code BuildFingerprint} hashes for one element
 * @param granularTitle      the heading of this annotation's stanza in a granular rule file
 * @param granularStanza     the body under that heading
 */
public record AnnotationDescriptor(
        Class<? extends Annotation> type,
        String fingerprintTag,
        AnnotationFormatter formatter,
        FingerprintMembers fingerprintMembers,
        String granularTitle,
        GranularStanza granularStanza) {

    /**
     * The member values of one element's annotation, joined into the text the fingerprint hashes.
     * Every member that reaches generated content has to be in it (invariant 12): a member left
     * out changes the output without changing the hash, and the write cache serves the old file.
     */
    @FunctionalInterface
    public interface FingerprintMembers {
        String of(TaggedElement element);
    }

    /**
     * The stanza body for one element, lines separated by a newline, or {@code null} when this
     * element gets no stanza at all. {@code null} rather than an empty string because an empty
     * body is still one blank line to {@code GranularRenderer}, and a heading over a blank line
     * reads to an agent as an annotation that says nothing.
     */
    @FunctionalInterface
    public interface GranularStanza {
        @Nullable String of(TaggedElement element);
    }
}
