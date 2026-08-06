package se.deversity.vibetags.processor.internal.content;

import se.deversity.vibetags.processor.model.TaggedElement;

/**
 * Defines the contract to format cross-platform content fragments for an individual annotation type.
 */
@FunctionalInterface
public interface AnnotationFormatter {
    /**
     * Formats the annotation value for the given element and appends it to the platform's buffer.
     *
     * @param element the annotated element
     * @param sb the platform-specific buffer
     * @param platform the target platform/service
     */
    void format(TaggedElement element, StringBuilder sb, Platform platform);
}
