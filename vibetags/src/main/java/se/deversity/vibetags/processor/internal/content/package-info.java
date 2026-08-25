/**
 * Content rendering infrastructure: platform abstractions, formatter registry,
 * rendering context, and the platform-renderer registry.
 *
 * <p>All types are {@code @NullMarked}: every parameter and return type is
 * non-null by default; exceptions are annotated with {@code @Nullable}
 * explicitly.
 */
@NullMarked
@AIArchitecture(
    belongsTo = "rendering",
    cannotReference = {
        "javax.lang.model",
        "javax.annotation.processing",
        "javax.tools",
        "com.sun.source",
        "se.deversity.vibetags.processor.internal"
    })
package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.NullMarked;
import se.deversity.vibetags.annotations.AIArchitecture;
