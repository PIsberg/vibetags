package se.deversity.vibetags.processor.internal.content;

import se.deversity.vibetags.processor.internal.AnnotationCollector;

/**
 * Defines the contract to render a single, specific platform configuration file.
 */
@FunctionalInterface
public interface PlatformRenderer {
    /**
     * Renders the platform configuration based on the collected annotations.
     *
     * @param collector the accumulated annotations
     * @param platform the specific target platform/service
     * @param context the rendering context (project name, headers, etc.)
     * @return the rendered file contents, or null if this platform is not active or handled elsewhere
     */
    String render(AnnotationCollector collector, Platform platform, RenderingContext context);
}
