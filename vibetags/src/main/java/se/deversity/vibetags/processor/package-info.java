/**
 * VibeTags annotation processor entry point.
 *
 * <p>This package contains {@link se.deversity.vibetags.processor.AIGuardrailProcessor},
 * the JSR 269 annotation processor that generates AI-platform guardrail files at
 * compile time, and {@link se.deversity.vibetags.processor.VibeTagsLogger}, the
 * file-based logger used throughout the processor.
 *
 * <p>All types in this package and its sub-packages are {@code @NullMarked}: every
 * parameter and return type is non-null by default; exceptions are annotated
 * with {@code @Nullable} explicitly.
 */
@NullMarked
package se.deversity.vibetags.processor;

import org.jspecify.annotations.NullMarked;
