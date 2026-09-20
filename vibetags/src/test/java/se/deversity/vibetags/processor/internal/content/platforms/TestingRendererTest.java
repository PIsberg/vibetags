package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code TESTING.md} holds, and when it holds anything.
 *
 * <p>The renderer is reached through the registry, the way the content builder reaches it, so a
 * missing {@code case TESTING:} fails here as "Unsupported platform" instead of aborting a
 * consumer's whole generation pass, which is what an unregistered platform does.
 */
class TestingRendererTest {

    private static final RenderingContext MAIN_ROUND =
        new RenderingContext("Test Project", "# Generated Header\n", Set.of("testing", "claude"));

    private static String render(GuardrailModel model, RenderingContext context) {
        return PlatformRendererRegistry.getRenderer(Platform.TESTING).render(model, Platform.TESTING, context);
    }

    @Test
    void aContextIsAMainRoundUntilToldOtherwise() {
        assertFalse(MAIN_ROUND.testRound());
        assertTrue(MAIN_ROUND.asTestRound().testRound());
    }

    @Test
    void asTestRoundKeepsEverythingElseAboutTheContext() {
        RenderingContext testRound = MAIN_ROUND.asSafetyDigest().asTestRound();
        assertTrue(testRound.safetyDigest(), "the safety-digest mode must survive the copy");
        assertEquals(MAIN_ROUND.getActiveServices(), testRound.getActiveServices());
        assertEquals(MAIN_ROUND.getGeneratedHeader(), testRound.getGeneratedHeader());
        assertEquals(MAIN_ROUND.getProjectName(), testRound.getProjectName());
        assertTrue(MAIN_ROUND.asTestRound().asSafetyDigest().testRound(),
            "and the test-round flag must survive the other copy method");
    }

    /**
     * A main round has no test guardrails to contribute. Rendering nothing, rather than an empty
     * body, is what leaves the region a test round wrote earlier in place.
     */
    @Test
    void aMainRoundRendersNothing() {
        assertNull(render(GuardrailModels.everyAnnotation(), MAIN_ROUND));
    }

    @Test
    void aTestRoundSaysTheseGuardrailsAreForTestCodeAndWhereTheSafetyOnesWent() {
        String rendered = render(GuardrailModels.everyAnnotation(), MAIN_ROUND.asTestRound());

        assertNotNull(rendered);
        assertTrue(rendered.contains(GuardrailModels.marker(AIContext.class)),
            "a test round's guardrails must reach the file");
        assertTrue(rendered.contains("test code"), "the preamble must say what the file is scoped to");
        assertTrue(rendered.toLowerCase(java.util.Locale.ROOT).contains("safety"),
            "the preamble must say the safety guardrails for test code live elsewhere, or a reader "
                + "takes their absence here for their absence everywhere");
    }

    /**
     * A heading that can never have anything under it is worse than no heading. The content
     * builder hands this renderer {@code model.withoutSafety()}, so the locked section is empty by
     * construction, and a reader who meets "LOCKED FILES (DO NOT EDIT)" with nothing beneath it
     * reads "no test file is locked" two lines after the preamble said the locked ones are
     * somewhere else. An opted-in file holding only a header is a shape this repository has
     * shipped before.
     *
     * <p>The rule is emptiness, not the file: a model that does carry a locked element keeps the
     * heading, so this cannot quietly become "TESTING.md never shows locks".
     */
    @Test
    void aSectionWithNothingUnderItIsNotPrinted() {
        String routed = render(GuardrailModels.everyAnnotation().withoutSafety(), MAIN_ROUND.asTestRound());
        assertNotNull(routed);
        assertFalse(routed.contains("LOCKED FILES"),
            "the routed model has no locked element, so this heading can only mislead:\n" + routed);
        assertTrue(routed.contains("CONTEXTUAL RULES"),
            "and a section that does have content is untouched:\n" + routed);

        String full = render(GuardrailModels.everyAnnotation(), MAIN_ROUND.asTestRound());
        assertNotNull(full);
        assertTrue(full.contains("LOCKED FILES"),
            "a model carrying a locked element keeps the heading:\n" + full);
    }

    /**
     * {@code TESTING.md} borrows the {@code AGENTS.md} rendering, so whatever reaches that file
     * must reach this one. No formatter has a {@code TESTING} arm, which means the derived check
     * in {@link RendererDropsNoSupportedAnnotationTest} sees nothing to compare for this platform
     * and passes vacuously. This is the comparison that does run.
     */
    @Test
    void aTestRoundDropsNoAnnotationThatAgentsMdCarries() {
        GuardrailModel model = GuardrailModels.everyAnnotation();
        String agents = PlatformRendererRegistry.getRenderer(Platform.CODEX)
            .render(model, Platform.CODEX, MAIN_ROUND);
        String testing = render(model, MAIN_ROUND.asTestRound());
        assertNotNull(agents);
        assertNotNull(testing);

        List<String> dropped = new ArrayList<>();
        int compared = 0;
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            String marker = GuardrailModels.marker(type);
            if (!agents.contains(marker)) {
                continue;
            }
            compared++;
            if (!testing.contains(marker)) {
                dropped.add(type.getSimpleName());
            }
        }

        assertTrue(compared > 30, "AGENTS.md carried only " + compared + " annotations, so this compared almost nothing");
        assertEquals(List.of(), dropped, "in AGENTS.md but missing from TESTING.md");
    }
}
