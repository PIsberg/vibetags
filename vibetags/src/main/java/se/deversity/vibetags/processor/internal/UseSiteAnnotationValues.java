package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * The values an annotation use writes at its site, for an element model that has no
 * {@link javax.lang.model.element.AnnotationMirror}s to read them from.
 *
 * <p>{@code vibetags-ksp} builds its elements from KSP symbols, and its annotation types are not in
 * its element model, so the mirror walk {@link MethodBodyGuardrailScanner} falls back to without the
 * Tree API found no annotation at all and counted no value from outside the round. The source digest
 * then vouched for a guardrail whose text came from a constant in another module, and a rebuild after
 * that constant changed was skipped as unchanged (#1121). An element implementing this hands the
 * scanner the same values a mirror's {@code getElementValues()} would.
 */
public interface UseSiteAnnotationValues {

    /** Stands for an enum constant or a class literal, which the source names wherever it is used. */
    Object NAMED = new Object() {
        @Override
        public String toString() {
            return "NAMED";
        }
    };

    /**
     * The members written at the use site of the annotation named {@code annotationType} on this
     * element, member name to value, or {@code null} when the element does not carry it. A value is a
     * {@link String}, a boxed primitive, {@link #NAMED}, a {@link java.util.List} of those, or anything
     * else, which the scanner counts as not spelled out.
     *
     * @param annotationType the annotation's binary name, as {@link Class#getName()} gives it
     */
    @Nullable Map<String, Object> useSiteValues(String annotationType);
}
