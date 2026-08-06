package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.validation.ValidationContext;
import se.deversity.vibetags.processor.internal.validation.ValidationRules;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import java.util.Set;

/**
 * Compile-time consistency checks for VibeTags annotations. Emits compiler warnings for
 * contradictory or no-op combinations so the developer notices at build time.
 *
 * <p>The checks themselves live in {@code processor.internal.validation} as individually testable
 * rules; this class is the entry point the processor calls and the place a round environment
 * becomes a {@link ValidationContext}. Adding a check means adding a rule there, not editing this
 * file.
 */
public final class AnnotationValidator {

    private AnnotationValidator() {}

    /**
     * Runs all validations against the given round environment, emitting warnings via the messager.
     */
    public static void validate(Messager messager, RoundEnvironment roundEnv, ProcessingEnvironment processingEnv) {
        validate(messager, roundEnv, processingEnv, null);
    }

    /**
     * Runs all validations, querying javac only for the annotation types reported present this
     * round. {@code presentFqns} is the set of fully-qualified annotation names from the
     * {@code annotations} argument of {@code process()}; when non-null, the per-type
     * {@code getElementsAnnotatedWith} scan is skipped for any type absent from it (the scan would
     * return empty anyway). Passing {@code null} queries every type (used by direct unit tests
     * that mock {@code getElementsAnnotatedWith} without populating {@code annotations}).
     */
    public static void validate(Messager messager, RoundEnvironment roundEnv, ProcessingEnvironment processingEnv,
                                @Nullable Set<String> presentFqns) {
        ValidationRules.run(new ValidationContext(messager, roundEnv, processingEnv, presentFqns));
    }
}
