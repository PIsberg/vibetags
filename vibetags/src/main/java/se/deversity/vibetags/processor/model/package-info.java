/**
 * The compiler-free data model the rendering layer reads.
 *
 * <p>This package is the seam between the two halves of the processor. Above it,
 * {@code ...processor.internal} talks to javac: it drains {@code RoundEnvironment}, walks
 * {@code Element} hierarchies, and resolves source positions through the Compiler Tree API. Below
 * it, {@code ...processor.internal.content} renders guardrail files and knows nothing about a
 * compiler at all — it sees {@link se.deversity.vibetags.processor.model.GuardrailModel} and
 * {@link se.deversity.vibetags.processor.model.TaggedElement} and nothing else.
 *
 * <p>The dependency runs one way, {@code internal → model ← content}, which is what keeps the two
 * halves acyclic and lets every formatter and renderer be tested without invoking a compiler.
 * Nothing here may import {@code javax.lang.model}, {@code javax.annotation.processing}, or
 * {@code com.sun.source}; {@code ArchitectureRulesTest} enforces that.
 *
 * <p>All types are {@code @NullMarked}: every parameter and return type is non-null by default;
 * exceptions are annotated with {@code @Nullable} explicitly.
 */
@NullMarked
@AIArchitecture(
    belongsTo = "model",
    cannotReference = {
        "javax.lang.model",
        "javax.annotation.processing",
        "javax.tools",
        "com.sun.source",
        "se.deversity.vibetags.processor",
        "se.deversity.vibetags.processor.internal"
    })
package se.deversity.vibetags.processor.model;

import org.jspecify.annotations.NullMarked;
import se.deversity.vibetags.annotations.AIArchitecture;
