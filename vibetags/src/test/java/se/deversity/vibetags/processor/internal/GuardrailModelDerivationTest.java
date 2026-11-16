package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.SourceLocation;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.model.TransitiveRule;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything a {@link GuardrailModel} derives from what it was given: the element ids persisted
 * per module, the reference count that sizes render buffers, the "did this round annotate
 * anything" flag, the labelled buckets the log summary walks, the excluded view
 * {@code -Avibetags.exclude} publishes, and the builder's null handling.
 *
 * <p>Pinned before that logic leaves the model. The builder and the views are reached through the
 * adapters at the top of this class, so moving them changes those lines and nothing below. Each
 * case was seen red against a mutant of the behaviour it pins.
 */
class GuardrailModelDerivationTest {

    // ---- the only lines that name where the code under test lives --------------------------

    private static GuardrailModel.Builder builder() {
        return GuardrailModel.builder();
    }

    private static GuardrailModel excluding(GuardrailModel model, Predicate<TaggedElement> excluded) {
        return model.excluding(excluded);
    }

    private static GuardrailModel withoutSafety(GuardrailModel model) {
        return model.withoutSafety();
    }

    private static Map<String, Set<TaggedElement>> labeled(GuardrailModel model) {
        return model.labeledSets();
    }

    // -----------------------------------------------------------------------------------------

    private static TaggedElement element(String simpleName) {
        String path = "com.acme." + simpleName;
        return TaggedElement.builder(path)
            .names(path, simpleName, simpleName, "com-acme-" + simpleName)
            .kind(ElementTag.CLASS)
            .build();
    }

    private static TransitiveRule rule(String pkg) {
        return new TransitiveRule("com.acme:lib:1.0", pkg, "@AIContext", TransitiveRule.Tier.ADVISORY, Map.of());
    }

    private final TaggedElement alpha = element("Alpha");
    private final TaggedElement bravo = element("Bravo");

    /** {@code bravo} carries two annotations, {@code alpha} one. */
    private GuardrailModel twoElements() {
        return builder()
            .add(AIContext.class, bravo)
            .add(AIContext.class, alpha)
            .add(AILocked.class, bravo)
            .lockedPosition(bravo, new SourceLocation("src/main/java/com/acme/Bravo.java", 3, 9))
            .transitiveRule(rule("com.acme.api"))
            .build();
    }

    @Test
    void elementIdsAreGranularNamesInBucketOrderWithoutDuplicates() {
        // ALL lists @AILocked before @AIContext: the locked bucket contributes Bravo first, then the
        // context bucket adds Alpha; Bravo's second appearance is not repeated.
        assertEquals(List.of("com-acme-Bravo", "com-acme-Alpha"), new ArrayList<>(twoElements().elementIds()));
    }

    @Test
    void annotatedReferencesCountAnElementOncePerAnnotation() {
        assertEquals(3, twoElements().totalAnnotatedReferences());
        assertEquals(0, GuardrailModel.EMPTY.totalAnnotatedReferences());
    }

    @Test
    void onlyThisProjectsOwnAnnotationsCountAsFound() {
        assertTrue(twoElements().anyAnnotationsFound());
        assertFalse(GuardrailModel.EMPTY.anyAnnotationsFound());
        assertFalse(builder().transitiveRule(rule("com.acme.api")).build().anyAnnotationsFound(),
            "an inherited rule is a dependency's guardrail, not one of this project's");
    }

    @Test
    void theSameElementAddedTwiceToABucketIsHeldOnce() {
        GuardrailModel model = builder().add(AIAudit.class, alpha).add(AIAudit.class, alpha).build();
        assertEquals(1, model.of(AIAudit.class).size());
        assertEquals(1, model.totalAnnotatedReferences());
    }

    @Test
    void theBuilderIgnoresNullsItIsHanded() {
        GuardrailModel model = builder()
            .add(AILocked.class, alpha)
            .lockedPosition(alpha, null)
            .transitiveRule(null)
            .transitiveRules(null)
            .build();

        assertNull(model.lockedPosition(alpha));
        assertEquals(List.of(), model.transitiveRules());
    }

    @Test
    void bucketsCannotBeChangedThroughTheModel() {
        GuardrailModel model = twoElements();
        assertThrows(UnsupportedOperationException.class, () -> model.of(AIContext.class).add(element("Charlie")));
        assertTrue(model.of(AIAudit.class).isEmpty(), "an annotation nobody used reads as an empty bucket");
    }

    @Test
    void labeledSetsNameEveryAnnotationInRegistryOrderEvenWhenEmpty() {
        GuardrailModel model = twoElements();
        Map<String, Set<TaggedElement>> labeled = labeled(model);

        List<String> expectedLabels = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            expectedLabels.add("@" + type.getSimpleName());
        }
        assertEquals(expectedLabels, new ArrayList<>(labeled.keySet()));
        assertEquals(Set.of(alpha, bravo), labeled.get("@AIContext"));
        assertEquals(Set.of(bravo), labeled.get("@AILocked"));
        assertEquals(Set.of(), labeled.get("@AIAudit"));
    }

    @Test
    void anExcludedElementLeavesEveryBucketAndItsLockedPosition() {
        GuardrailModel model = excluding(twoElements(), e -> e.equals(bravo));

        assertEquals(Set.of(alpha), model.of(AIContext.class));
        assertTrue(model.of(AILocked.class).isEmpty());
        assertNull(model.lockedPosition(bravo), "no entry may be left behind in .vibetags-locks");
        assertEquals(List.of("com-acme-Alpha"), new ArrayList<>(model.elementIds()));
        assertEquals(1, model.totalAnnotatedReferences());
    }

    @Test
    void exclusionKeepsTheElementsItDoesNotMatchAndEveryInheritedRule() {
        GuardrailModel original = twoElements();
        GuardrailModel model = excluding(original, e -> e.equals(alpha));

        assertNotNull(model.lockedPosition(bravo));
        assertEquals(original.lockedPosition(bravo), model.lockedPosition(bravo));
        assertEquals(original.transitiveRules(), model.transitiveRules(),
            "an inherited rule is a dependency's text, and no pattern over local names may drop it");
    }

    @Test
    void excludingEverythingLeavesAModelThatFoundNothing() {
        GuardrailModel model = excluding(twoElements(), e -> true);
        assertFalse(model.anyAnnotationsFound());
        assertTrue(model.elementIds().isEmpty());
        assertTrue(model.anyTransitiveRules());
    }

    @Test
    void theTestingViewCarriesNoLockedPositions() {
        // @AILocked is a safety annotation, so its elements and their positions stay in the
        // always-loaded half; TESTING.md has nothing to point .vibetags-locks at.
        assertNull(withoutSafety(twoElements()).lockedPosition(bravo));
    }
}
