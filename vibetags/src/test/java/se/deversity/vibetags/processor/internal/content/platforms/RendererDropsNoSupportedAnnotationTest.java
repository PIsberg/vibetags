package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A platform must not silently drop an annotation it has formatting for.
 *
 * <p>Every {@code @AI...} annotation has one formatter, and a formatter decides per platform what
 * that annotation looks like there — or emits nothing, which is how a platform opts out. A
 * renderer then walks the buckets it wants. Nothing connected the two, so a formatter could carry
 * a hand-written arm for a platform whose renderer never called it: the annotation compiled, the
 * formatting existed, and the guardrail simply never reached the file. No warning, no failing
 * test, and the annotation still showed up in the other platforms' files, which is what makes it
 * hard to notice from the outside.
 *
 * <p>Found exactly that in four renderers on 2026-08-11: Codex and Qwen dropped 17 annotations
 * each, Open Interpreter and Aider 12 each, all of them with formatter arms written and never
 * invoked. The renderers hand-list their buckets and the lists had stopped being extended, while
 * Cursor, Copilot, Windsurf, Zed, Claude, Gemini and llms carried all 44.
 *
 * <p>The check is derived rather than listed, which is the point: it reads
 * {@link GuardrailAnnotations#ALL} and {@link Platform#values()}, so annotation 45 and platform 38
 * are covered the day they land, without anyone remembering to extend a fixture. Its predecessor
 * hand-listed 39 of the 44 annotations and had been quietly missing the five newest.
 *
 * <p>The assertion matches on the element's own name rather than the formatter's exact bytes: a
 * renderer is free to re-wrap, indent or strip a trailing comma (Mentat's JSON does), but it is
 * not free to leave the element out.
 */
class RendererDropsNoSupportedAnnotationTest {

    private static final RenderingContext CONTEXT = new RenderingContext(
        "Test Project", "# Generated Header\n",
        Set.of("llms", "llms_full", "mentat", "pr_agent", "cody", "qwen_settings",
               "codex_config", "sweep", "plandex", "interpreter", "aider_conventions"));

    /**
     * Aggregate platforms only. The {@code *_GRANULAR} services write one file per element through
     * {@code GranularRulesWriter}, not through a renderer's aggregate output, and their renderer
     * answers {@code null} here.
     */
    static Stream<Platform> aggregatePlatforms() {
        return Stream.of(Platform.values()).filter(p -> !p.name().endsWith("_GRANULAR"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregatePlatforms")
    @DisplayName("every annotation the platform's formatters render reaches the platform's file")
    void rendersEveryAnnotationItsFormattersSupport(Platform platform) {
        String rendered = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModels.everyAnnotation(), platform, CONTEXT);
        assertTrue(rendered != null, platform + " rendered nothing at all");

        List<String> dropped = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (formatterOutput(type, platform).isBlank()) {
                continue;
            }
            if (!rendered.contains(elementName(type))) {
                dropped.add(type.getSimpleName());
            }
        }

        assertEquals(List.of(), dropped,
            platform + " has formatting for these annotations but never renders them — the "
                + "renderer's bucket walk is missing them, so a user who applies one of these gets "
                + "it in every other platform's file and silently not in this one");
    }

    /**
     * Guards the fixture itself: an annotation nothing renders anywhere would make the test above
     * vacuously pass for it. Every annotation must reach at least one platform's file.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyAnnotation")
    void everyAnnotationReachesAtLeastOnePlatform(Class<? extends Annotation> type) {
        GuardrailModel model = GuardrailModels.everyAnnotation();
        boolean rendered = aggregatePlatforms().anyMatch(platform -> {
            String out = PlatformRendererRegistry.getRenderer(platform).render(model, platform, CONTEXT);
            return out != null && out.contains(elementName(type));
        });
        assertTrue(rendered,
            type.getSimpleName() + " is collected but no platform renders it — either its formatter "
                + "has no platform arms, or the fixture stopped producing a usable element for it");
    }

    static Stream<Class<? extends Annotation>> everyAnnotation() {
        return GuardrailAnnotations.ALL.stream();
    }

    /** The fixture element's simple name, unique per annotation and stable under re-wrapping. */
    private static String elementName(Class<? extends Annotation> type) {
        String marker = GuardrailModels.marker(type);
        return marker.substring(marker.lastIndexOf('.') + 1);
    }

    /** What {@code type}'s formatter writes for {@code platform}; blank means "not supported here". */
    private static String formatterOutput(Class<? extends Annotation> type, Platform platform) {
        String name = "se.deversity.vibetags.processor.internal.content.annotations."
            + type.getSimpleName() + "Formatter";
        try {
            AnnotationFormatter formatter = (AnnotationFormatter)
                Class.forName(name).getDeclaredConstructor().newInstance();
            TaggedElement element = GuardrailModels.element(type);
            StringBuilder sb = new StringBuilder();
            formatter.format(element, sb, platform);
            return sb.toString();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "every annotation must have a matching <name>Formatter; none resolved for "
                    + type.getSimpleName(), e);
        }
    }

    /** Referenced so the aggregate list above is exercised through the real registry type. */
    @SuppressWarnings("unused")
    private static final Class<?> PINNED = PlatformRenderer.class;
}
