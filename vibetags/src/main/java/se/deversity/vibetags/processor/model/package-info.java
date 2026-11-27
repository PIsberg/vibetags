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
 * <p><b>What stays here.</b> Ordering, derivation and lookup are not the model's: they live in
 * {@code internal.GuardrailModelBuilder}, the only class that may construct a {@code GuardrailModel}
 * ({@code ArchitectureRulesTest.ONLY_THE_BUILDER_CONSTRUCTS_A_MODEL}). What makes these types values
 * stays with them (decided on #1090): {@code TaggedElement.owner()} answering the element itself when
 * no owner was set; the nested {@code TaggedElement.Builder}'s defaults and its unmodifiable copies
 * of the annotation and type-member maps; constructor validation and unmodifiable wrapping in both
 * types; {@code TaggedElement}'s {@code equals}, {@code hashCode} and {@code toString}, whose
 * identity is {@code kind} and {@code path}; and {@code GuardrailModel}'s named bucket accessors,
 * each a one-line {@code of(...)}. Those are the types' identity, invariants and immutability, not
 * logic about the data, and moving them out would make every caller re-establish them. New
 * ordering or derivation still goes in the builder.
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
