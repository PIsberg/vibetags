package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AITestDriven;

import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Compile-time consistency checks for VibeTags annotations. Emits compiler warnings for
 * contradictory or no-op combinations so the developer notices at build time.
 */
public final class AnnotationValidator {

    private AnnotationValidator() {}

    /**
     * Runs all validations against the given round environment, emitting warnings via the messager.
     */
    public static void validate(Messager messager, RoundEnvironment roundEnv) {
        // Contradiction: @AIDraft + @AILocked
        for (Element element : roundEnv.getElementsAnnotatedWith(AILocked.class)) {
            if (element.getAnnotation(AIDraft.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIDraft and @AILocked. This is contradictory.",
                    element);
            }
        }

        // Empty audit: @AIAudit with no checkFor
        for (Element element : roundEnv.getElementsAnnotatedWith(AIAudit.class)) {
            AIAudit audit = element.getAnnotation(AIAudit.class);
            if (audit.checkFor().length == 0) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: @AIAudit on " + element.toString()
                        + " has no 'checkFor' items list. It will be ignored.",
                    element);
            }
        }

        // Redundancy: @AIPrivacy + @AIIgnore
        for (Element element : roundEnv.getElementsAnnotatedWith(AIPrivacy.class)) {
            if (element.getAnnotation(AIIgnore.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIPrivacy and @AIIgnore. "
                        + "@AIIgnore already excludes the element from AI context; @AIPrivacy is redundant.",
                    element);
            }
        }

        // Contradiction: @AIContract + @AIDraft
        for (Element element : roundEnv.getElementsAnnotatedWith(AIContract.class)) {
            if (element.getAnnotation(AIDraft.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIContract and @AIDraft. "
                        + "@AIContract freezes the signature, but @AIDraft implies the element is not yet implemented. "
                        + "Remove one of the two annotations.",
                    element);
            }
        }

        // Overlap: @AIContract + @AILocked
        for (Element element : roundEnv.getElementsAnnotatedWith(AIContract.class)) {
            if (element.getAnnotation(AILocked.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIContract and @AILocked. "
                        + "@AILocked prohibits all modifications; @AIContract permits internal-logic changes. "
                        + "Consider using only @AILocked if no changes at all are intended.",
                    element);
            }
        }

        // Contradiction: @AITestDriven + @AIIgnore
        for (Element element : roundEnv.getElementsAnnotatedWith(AITestDriven.class)) {
            if (element.getAnnotation(AIIgnore.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AITestDriven and @AIIgnore. "
                        + "@AIIgnore excludes the element from AI context entirely; "
                        + "@AITestDriven cannot enforce test coverage on an ignored element. "
                        + "Remove one of the two annotations.",
                    element);
            }
        }

        // Contradiction: @AITestDriven + @AILocked
        for (Element element : roundEnv.getElementsAnnotatedWith(AITestDriven.class)) {
            if (element.getAnnotation(AILocked.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AITestDriven and @AILocked. "
                        + "@AILocked prohibits all modifications; @AITestDriven permits changes only when tests are updated. "
                        + "Consider using only @AILocked if no changes at all are intended.",
                    element);
            }
        }

        // Invalid coverage goal
        for (Element element : roundEnv.getElementsAnnotatedWith(AITestDriven.class)) {
            AITestDriven td = element.getAnnotation(AITestDriven.class);
            if (td.coverageGoal() < 0 || td.coverageGoal() > 100) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: @AITestDriven on " + element.toString()
                        + " has an invalid coverageGoal (" + td.coverageGoal() + "). "
                        + "Value must be between 0 and 100 (inclusive).",
                    element);
            }
        }
    }
}
