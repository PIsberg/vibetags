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
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIFeatureFlag;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;

/**
 * Compile-time consistency checks for VibeTags annotations. Emits compiler warnings for
 * contradictory or no-op combinations so the developer notices at build time.
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidCatchingGenericException", "PMD.AvoidCatchingThrowable"})
public final class AnnotationValidator {

    private AnnotationValidator() {}

    /**
     * Runs all validations against the given round environment, emitting warnings via the messager.
     */
    public static void validate(Messager messager, RoundEnvironment roundEnv, ProcessingEnvironment processingEnv) {
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

        // No replacement target: @AIDeprecated with blank replacedBy
        for (Element element : roundEnv.getElementsAnnotatedWith(AIDeprecated.class)) {
            AIDeprecated dep = element.getAnnotation(AIDeprecated.class);
            if (dep.replacedBy().isBlank()) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: @AIDeprecated on " + element.toString()
                        + " has no 'replacedBy' target. AI assistants will flag the element as deprecated "
                        + "but cannot route callers to a replacement. Add replacedBy = \"com.example.NewClass\" "
                        + "to make the migration actionable.",
                    element);
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

        // Contradiction: @AILegacyBridge + @AIDraft
        for (Element element : roundEnv.getElementsAnnotatedWith(AILegacyBridge.class)) {
            if (element.getAnnotation(AIDraft.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AILegacyBridge and @AIDraft. These intents are contradictory.",
                    element);
            }
        }

        // Redundancy: @AIPublicAPI + @AILocked
        for (Element element : roundEnv.getElementsAnnotatedWith(AIPublicAPI.class)) {
            if (element.getAnnotation(AILocked.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIPublicAPI and @AILocked. "
                        + "@AILocked already locks this element; @AIPublicAPI is redundant.",
                    element);
            }
        }

        // Redundancy: @AIParallelTests + @AILocked
        for (Element element : roundEnv.getElementsAnnotatedWith(AIParallelTests.class)) {
            if (element.getAnnotation(AILocked.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIParallelTests and @AILocked. "
                        + "@AILocked already locks this element; @AIParallelTests is redundant.",
                    element);
            }
        }

        // Redundancy: @AISchemaSafe + @AIIgnore
        for (Element element : roundEnv.getElementsAnnotatedWith(AISchemaSafe.class)) {
            if (element.getAnnotation(AIIgnore.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AISchemaSafe and @AIIgnore. "
                        + "@AIIgnore already excludes the element; @AISchemaSafe is redundant.",
                    element);
            }
        }

        // Redundancy: @AIStrictClasspath + @AILocked
        for (Element element : roundEnv.getElementsAnnotatedWith(AIStrictClasspath.class)) {
            if (element.getAnnotation(AILocked.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIStrictClasspath and @AILocked. "
                        + "@AILocked already locks this element; @AIStrictClasspath is redundant.",
                    element);
            }
        }

        // Empty config warning: @AIArchitecture with blank belongsTo
        for (Element element : roundEnv.getElementsAnnotatedWith(AIArchitecture.class)) {
            AIArchitecture arch = element.getAnnotation(AIArchitecture.class);
            if (arch.belongsTo() == null || arch.belongsTo().isBlank()) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: @AIArchitecture on " + element.toString()
                        + " has a blank 'belongsTo' attribute. Name the layer or component it belongs to.",
                    element);
            }

            // Compile-time checks for forbidden dependency imports/references
            String[] forbidden = arch.cannotReference();
            if (forbidden != null && forbidden.length > 0 && processingEnv != null) {
                try {
                    com.sun.source.util.Trees trees = com.sun.source.util.Trees.instance(processingEnv);
                    if (trees != null) {
                        com.sun.source.util.TreePath path = trees.getPath(element);
                        if (path != null) {
                            com.sun.source.tree.CompilationUnitTree cu = path.getCompilationUnit();
                            if (cu != null) {
                                for (com.sun.source.tree.ImportTree imp : cu.getImports()) {
                                    String importStr = imp.getQualifiedIdentifier().toString();
                                    for (String forbiddenPkg : forbidden) {
                                        if (forbiddenPkg == null || forbiddenPkg.isBlank()) {
                                            continue;
                                        }
                                        boolean match = false;
                                        if (importStr.equals(forbiddenPkg)) {
                                            match = true;
                                        } else if (importStr.startsWith(forbiddenPkg + ".")) {
                                            match = true;
                                        } else if (importStr.startsWith(forbiddenPkg + "*")) {
                                            match = true;
                                        }
                                        if (match) {
                                            messager.printMessage(Diagnostic.Kind.ERROR,
                                                "VibeTags: Class " + element.toString()
                                                    + " is annotated with @AIArchitecture and is strictly prohibited from referencing '"
                                                    + forbiddenPkg + "', but has an illegal import of '" + importStr + "'.",
                                                element);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    // Gracefully fallback/ignore if Trees API is unavailable or throws exception (e.g. unit tests or non-standard JDKs)
                    messager.printMessage(Diagnostic.Kind.NOTE,
                        "VibeTags: Trees API not available for AST architectural import scanning: " + t.getMessage(),
                        element);
                }
            }
        }

        // @AIFeatureFlag + @AILocked — contradictory
        for (Element element : roundEnv.getElementsAnnotatedWith(AIFeatureFlag.class)) {
            if (element.getAnnotation(AILocked.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIFeatureFlag and @AILocked. These are contradictory: "
                        + "@AILocked freezes the code; @AIFeatureFlag implies conditional execution and @AILocked is redundant.",
                    element);
            }
            AIFeatureFlag ff = element.getAnnotation(AIFeatureFlag.class);
            if (ff != null && (ff.flag() == null || ff.flag().isBlank())) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: @AIFeatureFlag on " + element.toString()
                        + " has a blank 'flag' attribute. Specify the feature flag key (e.g., @AIFeatureFlag(flag = \"my.feature.enabled\")).",
                    element);
            }
        }

        // Contradiction: @AIIdempotent + @AIDraft
        for (Element element : roundEnv.getElementsAnnotatedWith(AIIdempotent.class)) {
            if (element.getAnnotation(AIDraft.class) != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "VibeTags: " + element.toString()
                        + " is annotated with both @AIIdempotent and @AIDraft. This is contradictory: "
                        + "@AIIdempotent declares a stable behavioral contract while @AIDraft marks the element as unfinished.",
                    element);
            }
        }
    }
}
