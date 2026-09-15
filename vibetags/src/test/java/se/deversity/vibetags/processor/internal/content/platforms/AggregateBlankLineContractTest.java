package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.annotations.AILocked;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code llms.txt}, {@code llms-full.txt}, {@code CONVENTIONS.md}, {@code .github/copilot-instructions.md}
 * and both Junie files set every heading off by exactly one blank line (#725, #726, #730).
 *
 * <p>The spacing between sections has two owners: the heading string and the formatter arm that
 * rendered the entry before it. #726 took the leading newline off every {@code llms-full.txt}
 * heading, so every {@code LLMS_FULL} arm of every formatter now has to close with a blank line,
 * including the arms whose last line is an optional bullet. A {@code \n\n\n} check alone stays green
 * when an arm closes with a single newline, which is the opposite defect: the next heading glued to
 * the last bullet, as #725 found in Aider. So both directions are asserted, for all 44 annotations,
 * with members populated, with every member unset, and with only the optional ones unset. The
 * nothing-locked model reaches the Copilot and Junie branch that drops the empty locked heading.
 *
 * <p>{@code llms.txt} and {@code llms-full.txt} printed their locked and contextual headings
 * unconditionally, so a module with nothing locked and no {@code @AIContext} got two headings with
 * nothing under them, once per module in a reactor (#730). The compact file's locked heading is the
 * only one with no leading newline, so dropping it is also what could leave two blank lines above
 * the next heading; the nothing-locked-or-contextual model covers that.
 */
class AggregateBlankLineContractTest {

    private static final RenderingContext CONTEXT = new RenderingContext(
        "Test Project", "# Generated Header\n",
        Set.of("llms", "llms_full", "aider_conventions", "copilot", "cursor", "junie", "junie_agents"));

    private static final List<Platform> PLATFORMS = List.of(
        Platform.LLMS, Platform.LLMS_FULL, Platform.AIDER_CONVENTIONS, Platform.COPILOT, Platform.JUNIE, Platform.JUNIE_AGENTS);

    static Stream<Arguments> renders() {
        List<Arguments> out = new ArrayList<>();
        for (Platform platform : PLATFORMS) {
            out.add(Arguments.of(platform, "populated",
                (Supplier<GuardrailModel>) GuardrailModels::everyAnnotation));
            out.add(Arguments.of(platform, "members unset",
                (Supplier<GuardrailModel>) GuardrailModels::everyAnnotationWithMembersUnset));
            out.add(Arguments.of(platform, "optional members unset",
                (Supplier<GuardrailModel>) GuardrailModels::everyAnnotationWithOptionalMembersUnset));
            out.add(Arguments.of(platform, "nothing locked",
                (Supplier<GuardrailModel>) AggregateBlankLineContractTest::everyAnnotationButLocked));
            out.add(Arguments.of(platform, "nothing locked or contextual",
                (Supplier<GuardrailModel>) AggregateBlankLineContractTest::everyAnnotationButLockedOrContext));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("renders")
    void everyHeadingIsSetOffByExactlyOneBlankLine(Platform platform, String fixture,
                                                   Supplier<GuardrailModel> model) {
        String text = PlatformRendererRegistry.getRenderer(platform).render(model.get(), platform, CONTEXT);
        assertTrue(text != null && !text.isEmpty(), platform + " rendered nothing for " + fixture);

        assertFalse(text.contains("\n\n\n"),
            platform + " (" + fixture + ") prints two blank lines in a row:\n" + text);

        List<String> glued = new ArrayList<>();
        List<String> lines = text.lines().toList();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean heading = line.startsWith("## ") || line.startsWith("### ") || line.startsWith("#### ");
            if (heading && !lines.get(i - 1).isEmpty()) {
                glued.add("'" + line + "' under '" + lines.get(i - 1) + "'");
            }
        }
        assertEquals(List.of(), glued,
            platform + " (" + fixture + ") prints these headings directly under the previous line:\n" + text);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lockedHeadingPlatforms")
    void aRenderWithNothingLockedPrintsNoLockedHeading(Platform platform) {
        String populated = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModels.everyAnnotation(), platform, CONTEXT);
        String unlocked = PlatformRendererRegistry.getRenderer(platform)
            .render(everyAnnotationButLocked(), platform, CONTEXT);

        assertTrue(populated.contains("## Locked Files"),
            "precondition: " + platform + " prints its locked heading when something is locked:\n" + populated);
        assertFalse(unlocked.contains("## Locked Files"),
            platform + " prints an empty locked heading when nothing is locked:\n" + unlocked);
        assertTrue(unlocked.startsWith(populated.substring(0, populated.indexOf('\n'))),
            platform + " must keep its own title when nothing is locked:\n" + unlocked);
        assertTrue(unlocked.contains(GuardrailModels.marker(AIContext.class)),
            platform + " must still render the rest of the file when nothing is locked:\n" + unlocked);
    }

    static Stream<Platform> lockedHeadingPlatforms() {
        return Stream.of(Platform.COPILOT, Platform.JUNIE, Platform.JUNIE_AGENTS, Platform.LLMS, Platform.LLMS_FULL);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("llmsPlatforms")
    void anLlmsRenderWithNoContextPrintsNoContextualHeading(Platform platform) {
        String populated = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModels.everyAnnotation(), platform, CONTEXT);
        String contextless = PlatformRendererRegistry.getRenderer(platform)
            .render(everyAnnotationBut(AIContext.class), platform, CONTEXT);
        String bare = PlatformRendererRegistry.getRenderer(platform)
            .render(everyAnnotationButLockedOrContext(), platform, CONTEXT);

        assertTrue(populated.contains("## Contextual Rules"),
            "precondition: " + platform + " prints its contextual heading when there is context:\n" + populated);
        assertFalse(contextless.contains("## Contextual Rules"),
            platform + " prints an empty contextual heading when nothing has @AIContext:\n" + contextless);
        assertTrue(contextless.contains("## Locked Files"),
            platform + " must keep its locked section when only context is missing:\n" + contextless);
        assertFalse(bare.contains("## Locked Files") || bare.contains("## Contextual Rules"),
            platform + " prints an empty locked or contextual heading:\n" + bare);
        assertTrue(bare.contains("## 🔐 Security-Critical Code"),
            platform + " must still render the remaining sections:\n" + bare);
    }

    static Stream<Platform> llmsPlatforms() {
        return Stream.of(Platform.LLMS, Platform.LLMS_FULL);
    }

    private static GuardrailModel everyAnnotationButLocked() {
        return everyAnnotationBut(AILocked.class);
    }

    private static GuardrailModel everyAnnotationButLockedOrContext() {
        return everyAnnotationBut(AILocked.class, AIContext.class);
    }

    @SafeVarargs
    private static GuardrailModel everyAnnotationBut(Class<? extends Annotation>... excluded) {
        List<Class<? extends Annotation>> skip = List.of(excluded);
        GuardrailModel.Builder builder = GuardrailModel.builder();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (!skip.contains(type)) {
                builder.add(type, GuardrailModels.element(type));
            }
        }
        return builder.build();
    }
}
