/**
 * Per-platform renderer implementations ({@link se.deversity.vibetags.processor.internal.content.PlatformRenderer}).
 *
 * <p>Each class renders the full guardrail output for one target platform
 * (Cursor, Claude, Gemini, llms.txt, …). All types are {@code @NullMarked}:
 * every parameter and return type is non-null by default; exceptions are
 * annotated with {@code @Nullable} explicitly.
 */
@NullMarked
package se.deversity.vibetags.processor.internal.content.platforms;

import org.jspecify.annotations.NullMarked;
