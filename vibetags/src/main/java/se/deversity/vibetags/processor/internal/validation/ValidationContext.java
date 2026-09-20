package se.deversity.vibetags.processor.internal.validation;

import org.jspecify.annotations.Nullable;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a {@link ValidationRule} is allowed to see and say.
 *
 * <p>Every message goes out with the {@code VibeTags: } prefix attached here rather than at each
 * call site, because that prefix is what lets a consumer grep our diagnostics out of a build log
 * that also contains their own.
 */
public final class ValidationContext {

    /** Prefix on every diagnostic, so ours are greppable in somebody else's build log. */
    public static final String PREFIX = "VibeTags: ";

    private final Messager messager;
    private final RoundEnvironment roundEnv;
    private final @Nullable ProcessingEnvironment processingEnv;
    private final @Nullable Set<String> present;

    /**
     * What the collector already found this round, or {@code null} when nothing handed it over.
     *
     * <p>The collector runs first and asks javac the same question the rules are about to ask.
     * Each query walks every root element, so reusing its answer removes one full scan per scanned
     * type. Measured on examples/multimodule: 78 validation scans per build, all of them repeats.
     */
    private final @Nullable Map<Class<? extends Annotation>, Set<? extends Element>> roundIndex;

    /**
     * @param present fully-qualified names of the annotation types javac reported present this
     *                round, or {@code null} to query every type (see {@link #elementsWith})
     */
    public ValidationContext(Messager messager, RoundEnvironment roundEnv,
                             @Nullable ProcessingEnvironment processingEnv, @Nullable Set<String> present) {
        this(messager, roundEnv, processingEnv, present, null);
    }

    /**
     * @param roundIndex what the collector already found this round, consulted before querying
     *                   javac again, or {@code null} to always query
     */
    public ValidationContext(Messager messager, RoundEnvironment roundEnv,
                             @Nullable ProcessingEnvironment processingEnv, @Nullable Set<String> present,
                             @Nullable Map<Class<? extends Annotation>, Set<? extends Element>> roundIndex) {
        this.messager = messager;
        this.roundEnv = roundEnv;
        this.processingEnv = processingEnv;
        // Copied once per round: the caller's set is javac's, and a rule reading it mid-iteration
        // must see the same answer every time.
        this.present = present == null ? null : Set.copyOf(present);
        // Copied, not referenced: the collector replaces its own map on the next round, and the
        // rules must keep seeing the round they were built for. Copying also makes that invariant
        // local, rather than resting on the caller happening to hand over an unmodifiable view.
        this.roundIndex = roundIndex == null ? null : Map.copyOf(roundIndex);
    }

    /**
     * Elements annotated with {@code type} this round, or an empty set when javac already reported
     * the type absent — the scan would return empty anyway, and skipping it avoids walking every
     * root element once per annotation type on a large compilation unit.
     */
    public Set<? extends Element> elementsWith(Class<? extends Annotation> type) {
        if (present != null && !present.contains(type.getName())) {
            return Collections.emptySet();
        }
        if (roundIndex != null) {
            Set<? extends Element> already = roundIndex.get(type);
            if (already != null) {
                return already; // the collector asked javac this exact question a moment ago
            }
        }
        return roundEnv.getElementsAnnotatedWith(type);
    }

    /** The processing environment, or {@code null} under a mocked or non-javac environment. */
    public @Nullable ProcessingEnvironment processingEnv() {
        return processingEnv;
    }

    /**
     * This round's root elements, for the rules whose subject is a relationship between
     * declarations (an override of a locked method) rather than one annotated element. May be
     * {@code null} under a mocked round environment; rules must treat that as an empty round.
     */
    public @Nullable Set<? extends Element> rootElements() {
        return roundEnv.getRootElements();
    }

    /** Reports a contradiction, redundancy or no-op the developer should resolve. */
    public void warn(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.WARNING, PREFIX + message, element);
    }

    /**
     * As {@link #warn(Element, String)}, anchored at {@code element}'s mirror of
     * {@code annotation} so the IDE caret lands on the annotation being discussed rather than on
     * the whole declaration. Falls back to the element when the mirror cannot be found (mocked
     * environments hand back elements with no mirrors).
     */
    public void warn(Element element, Class<? extends Annotation> annotation, String message) {
        AnnotationMirror mirror = mirrorOf(element, annotation);
        if (mirror == null) {
            warn(element, message);
        } else {
            messager.printMessage(Diagnostic.Kind.WARNING, PREFIX + message, element, mirror);
        }
    }

    /** Reports something worth a second look that is not necessarily wrong. */
    public void note(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.NOTE, PREFIX + message, element);
    }

    /** As {@link #note(Element, String)}, anchored at the mirror of {@code annotation}. */
    public void note(Element element, Class<? extends Annotation> annotation, String message) {
        AnnotationMirror mirror = mirrorOf(element, annotation);
        if (mirror == null) {
            note(element, message);
        } else {
            messager.printMessage(Diagnostic.Kind.NOTE, PREFIX + message, element, mirror);
        }
    }

    /** {@code element}'s mirror of {@code type}, or {@code null} when it cannot be resolved. */
    private static @Nullable AnnotationMirror mirrorOf(Element element, Class<? extends Annotation> type) {
        List<? extends AnnotationMirror> mirrors = element.getAnnotationMirrors();
        if (mirrors == null) {
            return null; // mocked elements return no mirror list at all
        }
        String fqn = type.getName();
        for (AnnotationMirror mirror : mirrors) {
            if (mirror.getAnnotationType().asElement() instanceof TypeElement annotationType
                    && annotationType.getQualifiedName().contentEquals(fqn)) {
                return mirror;
            }
        }
        return null;
    }

    /** Fails the compilation. Reserved for guardrails that are checkable and were violated. */
    public void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, PREFIX + message, element);
    }
}
