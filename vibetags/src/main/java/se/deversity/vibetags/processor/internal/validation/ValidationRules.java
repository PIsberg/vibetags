package se.deversity.vibetags.processor.internal.validation;

import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.annotations.AISandboxOnly;
import se.deversity.vibetags.annotations.AISchemaSafe;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadAffinity;
import se.deversity.vibetags.annotations.AIThreadSafe;

import javax.lang.model.element.Element;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every compile-time consistency check VibeTags performs, and the loop that runs them.
 *
 * <p>Adding a check is a line in {@link #PAIRS} or an entry in {@link CoreRules} or
 * {@link ModernJavaRules}. That is the point of the split: what used to be one 450-line method with
 * forty hand-written {@code for} loops in it is now a table plus a dispatcher, and a new rule is a
 * line rather than a loop somebody has to place correctly among the others.
 *
 * <p>The dispatcher is also the cheaper arrangement. Rules are indexed by the annotation they scan,
 * so {@code getElementsAnnotatedWith} runs once per annotation type rather than once per check —
 * {@code @AITestDriven} used to be queried four times per round, {@code @AILoadBearing} three. That
 * query walks the round's root elements, so on a large compilation unit the difference is real, and
 * it compounds with the existing short-circuit that skips annotations javac reports absent.
 */
public final class ValidationRules {

    private ValidationRules() {
    }

    /**
     * Two annotations that contradict each other, or where the first makes the second pointless.
     *
     * <p>The scanned annotation (first argument) should be the rarer of the two: it decides which
     * round query runs. {@code @AILocked} and {@code @AIIgnore} are the common partners, so they
     * are almost always the second argument.
     */
    private static final List<ValidationRule> PAIRS = List.of(
        PairRule.warn(AILocked.class, AIDraft.class,
            " is annotated with both @AIDraft and @AILocked. This is contradictory."),

        PairRule.warn(AIDraft.class, AIIgnore.class,
            " is annotated with both @AIDraft and @AIIgnore. "
                + "@AIIgnore excludes the element from AI context entirely; "
                + "@AIDraft cannot surface implementation instructions for an ignored element. "
                + "Remove one of the two annotations."),

        PairRule.warn(AIPrivacy.class, AIIgnore.class,
            " is annotated with both @AIPrivacy and @AIIgnore. "
                + "@AIIgnore already excludes the element from AI context; @AIPrivacy is redundant."),

        PairRule.warn(AIContract.class, AIDraft.class,
            " is annotated with both @AIContract and @AIDraft. "
                + "@AIContract freezes the signature, but @AIDraft implies the element is not yet implemented. "
                + "Remove one of the two annotations."),

        PairRule.warn(AIContract.class, AILocked.class,
            " is annotated with both @AIContract and @AILocked. "
                + "@AILocked prohibits all modifications; @AIContract permits internal-logic changes. "
                + "Consider using only @AILocked if no changes at all are intended."),

        PairRule.warn(AITestDriven.class, AIIgnore.class,
            " is annotated with both @AITestDriven and @AIIgnore. "
                + "@AIIgnore excludes the element from AI context entirely; "
                + "@AITestDriven cannot enforce test coverage on an ignored element. "
                + "Remove one of the two annotations."),

        PairRule.warn(AITestDriven.class, AILocked.class,
            " is annotated with both @AITestDriven and @AILocked. "
                + "@AILocked prohibits all modifications; @AITestDriven permits changes only when tests are updated. "
                + "Consider using only @AILocked if no changes at all are intended."),

        PairRule.warn(AIDeprecated.class, AILocked.class,
            " is annotated with both @AIDeprecated and @AILocked. "
                + "@AILocked preserves the element; @AIDeprecated routes callers away from it. "
                + "These intents are contradictory — pick one."),

        PairRule.warn(AILegacyBridge.class, AIDraft.class,
            " is annotated with both @AILegacyBridge and @AIDraft. These intents are contradictory."),

        PairRule.warn(AIPublicAPI.class, AILocked.class,
            " is annotated with both @AIPublicAPI and @AILocked. "
                + "@AILocked already locks this element; @AIPublicAPI is redundant."),

        PairRule.warn(AIParallelTests.class, AILocked.class,
            " is annotated with both @AIParallelTests and @AILocked. "
                + "@AILocked already locks this element; @AIParallelTests is redundant."),

        PairRule.warn(AISchemaSafe.class, AIIgnore.class,
            " is annotated with both @AISchemaSafe and @AIIgnore. "
                + "@AIIgnore already excludes the element; @AISchemaSafe is redundant."),

        PairRule.warn(AIStrictClasspath.class, AILocked.class,
            " is annotated with both @AIStrictClasspath and @AILocked. "
                + "@AILocked already locks this element; @AIStrictClasspath is redundant."),

        PairRule.warn(AIFeatureFlag.class, AILocked.class,
            " is annotated with both @AIFeatureFlag and @AILocked. These are contradictory: "
                + "@AILocked freezes the code; @AIFeatureFlag implies conditional execution and @AILocked is redundant."),

        PairRule.warn(AISecure.class, AIIgnore.class,
            " is annotated with both @AISecure and @AIIgnore. This is contradictory: "
                + "@AIIgnore hides the element but @AISecure requires it to be visible for security review."),

        PairRule.warn(AIIdempotent.class, AIDraft.class,
            " is annotated with both @AIIdempotent and @AIDraft. This is contradictory: "
                + "@AIIdempotent declares a stable behavioral contract while @AIDraft marks the element as unfinished."),

        PairRule.warn(AISandboxOnly.class, AIDomainModel.class,
            " is annotated with both @AISandboxOnly and @AIDomainModel. "
                + "Sandbox mocks should not be subjected to framework-free domain model constraints."),

        PairRule.warn(AISunset.class, AIDraft.class,
            " is annotated with both @AISunset and @AIDraft. "
                + "Sunset elements must not be actively drafted or expanded."),

        PairRule.warn(AISecureLogging.class, AIIgnore.class,
            " is annotated with both @AISecureLogging and @AIIgnore. "
                + "@AIIgnore already completely excludes this element; @AISecureLogging is redundant."),

        PairRule.warn(AIGenerated.class, AIIgnore.class,
            " is annotated with both @AIGenerated and @AIIgnore. This is contradictory: "
                + "@AIGenerated means read but never write, while @AIIgnore hides the element entirely."),

        PairRule.warn(AIGenerated.class, AIDraft.class,
            " is annotated with both @AIGenerated and @AIDraft. This is contradictory: "
                + "@AIDraft asks the AI to implement the element, but generated code is overwritten on the next build."),

        PairRule.warn(AIThreadAffinity.class, AIThreadSafe.class,
            " is annotated with both @AIThreadAffinity and @AIThreadSafe. These are opposite "
                + "claims: affinity means safe on exactly one thread, thread-safety means safe on any. "
                + "Keep whichever is true."),

        PairRule.note(AIKeepInSync.class, AIContract.class,
            " carries both @AIKeepInSync and @AIContract. Verify the mirrors track something other "
                + "than the frozen signature, which cannot change in the first place.")
    );

    /** Every rule, flat. Ordered pairs → attributes → architecture → modern-Java. */
    private static final List<ValidationRule> ALL = buildAll();

    /** {@link #ALL} indexed by the annotation each rule scans, so each type is queried once. */
    private static final Map<Class<? extends Annotation>, List<ValidationRule>> BY_ANNOTATION = index(ALL);

    private static List<ValidationRule> buildAll() {
        List<ValidationRule> rules = new ArrayList<>(PAIRS);
        rules.addAll(CoreRules.all());
        rules.add(new ArchitectureRule());
        rules.addAll(ModernJavaRules.all());
        return List.copyOf(rules);
    }

    private static Map<Class<? extends Annotation>, List<ValidationRule>> index(List<ValidationRule> rules) {
        Map<Class<? extends Annotation>, List<ValidationRule>> byType = new LinkedHashMap<>();
        for (ValidationRule rule : rules) {
            byType.computeIfAbsent(rule.scans(), k -> new ArrayList<>()).add(rule);
        }
        byType.replaceAll((k, v) -> List.copyOf(v));
        return Map.copyOf(byType);
    }

    /** Every registered rule, for tests that assert on the registry rather than on a compile. */
    public static List<ValidationRule> all() {
        return new ArrayList<>(ALL);
    }

    /** The annotation types the dispatcher queries — one round query each. */
    public static Set<Class<? extends Annotation>> scannedAnnotations() {
        return BY_ANNOTATION.keySet();
    }

    /** Runs every rule against {@code ctx}, one round query per scanned annotation type. */
    public static void run(ValidationContext ctx) {
        for (Map.Entry<Class<? extends Annotation>, List<ValidationRule>> entry : BY_ANNOTATION.entrySet()) {
            Set<? extends Element> elements = ctx.elementsWith(entry.getKey());
            if (elements.isEmpty()) {
                continue;
            }
            for (Element element : elements) {
                for (ValidationRule rule : entry.getValue()) {
                    rule.check(ctx, element);
                }
            }
        }
    }
}
