package se.deversity.vibetags.processor.internal.content;

import javax.lang.model.element.Element;

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
    void format(Element element, StringBuilder sb, Platform platform);
}
