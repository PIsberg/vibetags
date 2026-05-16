package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;

/**
 * Compile-time consistency checks for VibeTags annotations. Emits compiler warnings for
 * contradictory or no-op combinations so the developer notices at build time.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
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

        // Contradiction: @AIDraft + @AIIgnore
        for (Element element : roundEnv.getElementsAnnotatedWith(AIDraft.class)) {
            if (element.getAnnotation(AIIgnore.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIDraft and @AIIgnore. "
                        + "@AIIgnore excludes the element from AI context entirely; "
                        + "@AIDraft cannot surface implementation instructions for an ignored element. "
                        + "Remove one of the two annotations.",
                    element);
            }
        }

        // No-op: @AIContext with both focus and avoids blank
        for (Element element : roundEnv.getElementsAnnotatedWith(AIContext.class)) {
            AIContext ctx = element.getAnnotation(AIContext.class);
            if (ctx.focus().isBlank() && ctx.avoids().isBlank()) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: @AIContext on " + element.toString()
                        + " has blank 'focus' and 'avoids' attributes. The annotation will be ignored.",
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

        // @AIImmutable on a type with non-final instance fields
        for (Element type : roundEnv.getElementsAnnotatedWith(AIImmutable.class)) {
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) continue;
                if (enclosed.getModifiers().contains(Modifier.STATIC)) continue;
                if (!enclosed.getModifiers().contains(Modifier.FINAL)) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                        "VibeTags: @AIImmutable on " + type.toString()
                            + " but field '" + enclosed.getSimpleName() + "' is not final. "
                            + "Immutable types must declare all instance fields final.",
                        enclosed);
                }
            }
        }

        // Contradiction: @AIDeprecated + @AILocked
        for (Element element : roundEnv.getElementsAnnotatedWith(AIDeprecated.class)) {
            if (element.getAnnotation(AILocked.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIDeprecated and @AILocked. "
                        + "@AILocked preserves the element; @AIDeprecated routes callers away from it. "
                        + "These intents are contradictory — pick one.",
                    element);
            }
        }

        // Contradiction: @AIThreadSafe + @AIImmutable.IMMUTABLE strategy on the same type is redundant
        for (Element element : roundEnv.getElementsAnnotatedWith(AIThreadSafe.class)) {
            AIThreadSafe ts = element.getAnnotation(AIThreadSafe.class);
            if (ts.strategy() == AIThreadSafe.Strategy.IMMUTABLE
                    && element.getAnnotation(AIImmutable.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with @AIThreadSafe(IMMUTABLE) and @AIImmutable. "
                        + "Use @AIImmutable alone — immutability already implies thread-safety.",
                    element);
            }
        }

        // @AIObservability with no metrics, traces, or logs is a no-op
        for (Element element : roundEnv.getElementsAnnotatedWith(AIObservability.class)) {
            AIObservability obs = element.getAnnotation(AIObservability.class);
            if (obs.metrics().length == 0 && obs.traces().length == 0 && obs.logs().length == 0) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: @AIObservability on " + element.toString()
                        + " declares no metrics, traces, or logs. The annotation will be ignored.",
                    element);
            }
        }

        // @AIRegulation with blank standard
        for (Element element : roundEnv.getElementsAnnotatedWith(AIRegulation.class)) {
            AIRegulation reg = element.getAnnotation(AIRegulation.class);
            if (reg.standard() == null || reg.standard().isBlank()) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: @AIRegulation on " + element.toString()
                        + " has a blank 'standard' attribute. Name the compliance standard "
                        + "(e.g., GDPR, PCI-DSS, HIPAA).",
                    element);
            }
        }
    }
}
