/**
 * The compile-time consistency checks VibeTags runs over its own annotations.
 *
 * <p>Entry point is {@code AnnotationValidator} in the parent package; everything here is the
 * implementation behind it. A check is a {@link se.deversity.vibetags.processor.internal.validation.ValidationRule}
 * bound to the annotation whose elements it inspects, and
 * {@link se.deversity.vibetags.processor.internal.validation.ValidationRules} both holds the registry
 * and runs it — one {@code getElementsAnnotatedWith} query per annotation type, however many rules
 * share it.
 *
 * <p>Three families live here:
 *
 * <ul>
 *   <li>{@link se.deversity.vibetags.processor.internal.validation.PairRule} — two annotations that
 *       contradict each other, expressed as a table;</li>
 *   <li>{@link se.deversity.vibetags.processor.internal.validation.CoreRules} — an annotation whose
 *       own attributes leave it instructing nobody;</li>
 *   <li>{@link se.deversity.vibetags.processor.internal.validation.ModernJavaRules} — an annotation
 *       that contradicts the declaration it sits on, where the contradiction only exists because of
 *       a language feature newer than Java 8.</li>
 * </ul>
 *
 * <p>Unlike {@code processor/internal/content/}, this package is allowed to see javac: a validation
 * rule reads {@code javax.lang.model} elements while the round is live, which is the only moment
 * they are valid.
 */
package se.deversity.vibetags.processor.internal.validation;
