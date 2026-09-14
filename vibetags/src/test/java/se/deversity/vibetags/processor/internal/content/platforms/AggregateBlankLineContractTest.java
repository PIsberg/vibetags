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
 * {@code llms-full.txt}, {@code CONVENTIONS.md}, {@code .github/copilot-instructions.md} and both
 * Junie files set every heading off by exactly one blank line (#725, #726).
 *
 * <p>The spacing between sections has two owners: the heading string and the formatter arm that
 * rendered the entry before it. #726 took the leading newline off every {@code llms-full.txt}
 * heading, so every {@code LLMS_FULL} arm of every formatter now has to close with a blank line,
 * including the arms whose last line is an optional bullet. A {@code \n\n\n} check alone stays green
 * when an arm closes with a single newline, which is the opposite defect: the next heading glued to
 * the last bullet, as #725 found in Aider. So both directions are asserted, for all 44 annotations,
 * with members populated, with every member unset, and with only the optional ones unset. The
 * nothing-locked model reaches the Copilot and Junie branch that drops the empty locked heading.
 */
class AggregateBlankLineContractTest {

    private static final RenderingContext CONTEXT = new RenderingContext(
        "Test Project", "# Generated Header\n",
        Set.of("llms", "llms_full", "aider_conventions", "copilot", "cursor", "junie", "junie_agents"));

    private static final List<Platform> PLATFORMS = List.of(
        Platform.LLMS_FULL, Platform.AIDER_CONVENTIONS, Platform.COPILOT, Platform.JUNIE, Platform.JUNIE_AGENTS);

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
    void aFullRenderWithNothingLockedPrintsNoLockedHeading(Platform platform) {
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
        return Stream.of(Platform.COPILOT, Platform.JUNIE, Platform.JUNIE_AGENTS);
    }

    private static GuardrailModel everyAnnotationButLocked() {
        GuardrailModel.Builder builder = GuardrailModel.builder();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (type != AILocked.class) {
                builder.add(type, GuardrailModels.element(type));
            }
        }
        return builder.build();
    }
}
