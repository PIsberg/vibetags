package se.deversity.vibetags.processor.internal;

import java.lang.annotation.Annotation;

/**
 * The {@code @AI...} label an annotation type is known by outside the compiler: in the log
 * summary, and in the {@code annotation} field of a transitive manifest, where a consuming build
 * reads it back to decide a rule's tier. One function, so the writer and the reader of a manifest
 * cannot disagree about the spelling.
 */
public final class AnnotationLabels {

    private AnnotationLabels() {}

    /** The label of {@code type}, e.g. {@code @AILocked}. */
    public static String label(Class<? extends Annotation> type) {
        return "@" + type.getSimpleName();
    }
}
