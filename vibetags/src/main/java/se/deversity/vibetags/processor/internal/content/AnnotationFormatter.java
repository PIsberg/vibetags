package se.deversity.vibetags.processor.internal.content;

import se.deversity.vibetags.processor.model.TaggedElement;

/**
 * Defines the contract to format cross-platform content fragments for an individual annotation type.
 */
@FunctionalInterface
public interface AnnotationFormatter {
    /**
     * Formats the annotation value for the given element and appends it to the platform's buffer.
     * Resolves {@link Platform#rendersAs()} here, once, so no implementation can treat an alias
     * differently from the platform it renders as (#764).
     *
     * @param element the annotated element
     * @param sb the platform-specific buffer
     * @param platform the target platform/service, alias or not
     */
    default void format(TaggedElement element, StringBuilder sb, Platform platform) {
        render(element, sb, platform.rendersAs());
    }

    /**
     * Appends the fragment for {@code family}, which is never an alias: {@link #format} has already
     * resolved it. Implementations switch on it and need no label for an aliased platform.
     *
     * @param element the annotated element
     * @param sb the platform-specific buffer
     * @param family the platform whose wording to print
     */
    void render(TaggedElement element, StringBuilder sb, Platform family);
}
