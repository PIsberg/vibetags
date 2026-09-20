package se.deversity.vibetags.processor.model;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.processor.GuardrailModels;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code safetyOnly()} and {@code withoutSafety()} split a model in two with nothing lost and
 * nothing in both.
 *
 * <p>Routing a test round renders the always-loaded files from one half and {@code TESTING.md}
 * from the other. An annotation in neither half is a guardrail that silently stops being written
 * anywhere; one in both is written twice. The loop runs over {@link GuardrailAnnotations#ALL}, so
 * a 45th annotation is held to the same rule the day it is added.
 */
class GuardrailModelViewsTest {

    /**
     * The six that stay in the always-loaded files, written out rather than read from the code
     * under test: this list is the specification the views are checked against.
     */
    private static final Set<Class<? extends Annotation>> SAFETY = Set.of(
        AILocked.class, AICore.class, AIPrivacy.class, AIIgnore.class, AIAudit.class, AISecure.class);

    private final GuardrailModel full = GuardrailModels.everyAnnotation();

    @Test
    void everyAnnotationLandsInExactlyOneView() {
        GuardrailModel safety = full.safetyOnly();
        GuardrailModel rest = full.withoutSafety();

        List<String> wrong = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            Set<TaggedElement> expected = full.of(type);
            assertFalse(expected.isEmpty(), "fixture holds nothing for " + type.getSimpleName());
            GuardrailModel home = SAFETY.contains(type) ? safety : rest;
            GuardrailModel away = SAFETY.contains(type) ? rest : safety;
            if (!home.of(type).equals(expected) || !away.of(type).isEmpty()) {
                wrong.add(type.getSimpleName());
            }
        }
        assertEquals(List.of(), wrong, "annotations not in exactly the view they belong to");
    }

    @Test
    void theTwoViewsAddUpToTheModel() {
        GuardrailModel safety = full.safetyOnly();
        GuardrailModel rest = full.withoutSafety();

        assertEquals(SAFETY.size(), safety.totalAnnotatedReferences());
        assertEquals(full.totalAnnotatedReferences(),
            safety.totalAnnotatedReferences() + rest.totalAnnotatedReferences());

        Set<String> union = new TreeSet<>(safety.elementIds());
        union.addAll(rest.elementIds());
        assertEquals(new TreeSet<>(full.elementIds()), union);
    }

    /**
     * {@code .vibetags-locks} is rendered from the locked positions, and {@code @AILocked} is a
     * safety annotation, so the positions go where the locked elements go.
     */
    @Test
    void lockedPositionsFollowTheLockedElements() {
        TaggedElement locked = GuardrailModels.element(AILocked.class);
        GuardrailModel model = GuardrailModel.builder()
            .add(AILocked.class, locked)
            .lockedPosition(locked, new SourceLocation("src/test/java/Fixture.java", 12, 14))
            .build();

        assertEquals(model.lockedPosition(locked), model.safetyOnly().lockedPosition(locked));
        assertTrue(model.safetyOnly().lockedPosition(locked) != null, "the position must survive the view");
    }

    /**
     * Inherited rules come from dependency JARs, not from this round's test sources, so they are
     * not test-code guardrails and have no business in {@code TESTING.md}.
     */
    @Test
    void inheritedRulesStayWithTheAlwaysLoadedView() {
        TransitiveRule inherited = new TransitiveRule("lib-1.0.jar", "com.example.lib", "@AIContext",
            TransitiveRule.Tier.ADVISORY, Map.of("focus", "inherited"));
        GuardrailModel model = GuardrailModel.builder()
            .add(AILocked.class, GuardrailModels.element(AILocked.class))
            .transitiveRule(inherited)
            .build();

        assertEquals(List.of(inherited), model.safetyOnly().transitiveRules());
        assertFalse(model.withoutSafety().anyTransitiveRules());
    }
}
