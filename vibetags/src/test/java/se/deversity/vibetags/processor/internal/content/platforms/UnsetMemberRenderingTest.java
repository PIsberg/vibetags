package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bare annotation — {@code @AISecure} with no members — has to render as well as the fully
 * populated one.
 *
 * <p>This is the form people actually write. Nearly every formatter reads its optional members
 * through a ternary of the shape {@code reason.isEmpty() ? "" : "- **Reason**: " + reason}, written
 * out once per platform, and every existing renderer test fed it a fixture with every member
 * populated. The empty side of all of those ternaries — several hundred of them across the
 * formatters, the granular renderer and the index sections — was reached by no test at all, which
 * is why {@code AIKeepInSyncFormatter}'s "nothing checks this automatically" fallback text could
 * have been deleted without a failure.
 *
 * <p>The contract pinned here is that an annotation whose optional members are unset still reaches
 * the file, in the aggregate rendering and in the per-element rule file. Dropping it would mean the
 * bare form of an annotation silently does nothing, which is the worst of the failure modes because
 * the annotation is visibly there in the source.
 *
 * <p>Derived from {@link GuardrailAnnotations#ALL} and {@link Platform#values()}, like its
 * populated-fixture counterpart {@link RendererDropsNoSupportedAnnotationTest}, so a new annotation
 * or platform is covered on the day it lands.
 */
class UnsetMemberRenderingTest {

    private static final RenderingContext CONTEXT = new RenderingContext(
        "Test Project", "# Generated Header\n",
        Set.of("llms", "llms_full", "mentat", "pr_agent", "cody", "qwen_settings",
               "codex_config", "sweep", "plandex", "interpreter", "aider_conventions"));

    /**
     * Annotations that legitimately render nothing when written bare, because their only member is
     * the content itself.
     *
     * <p>{@code @AIAudit}'s sole member is {@code checkFor()}, the list of things to check for. A
     * bare {@code @AIAudit} therefore asks for an audit against nothing, and every formatter
     * returns early rather than emitting a heading with no checks under it. That is a decision, not
     * a drop, so it is named here rather than papered over by weakening the assertion for everyone.
     */
    private static final Set<Class<? extends Annotation>> RENDERS_NOTHING_WHEN_BARE =
        Set.of(se.deversity.vibetags.annotations.AIAudit.class);

    static Stream<Platform> aggregatePlatforms() {
        return Stream.of(Platform.values()).filter(p -> !p.name().endsWith("_GRANULAR"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregatePlatforms")
    @DisplayName("an annotation with every optional member unset still reaches the platform file")
    void bareAnnotationsAreNotDropped(Platform platform) {
        String populated = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModels.everyAnnotation(), platform, CONTEXT);
        String bare = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModels.everyAnnotationWithMembersUnset(), platform, CONTEXT);
        assertTrue(bare != null && !bare.isEmpty(), platform + " rendered nothing at all");

        List<String> dropped = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (RENDERS_NOTHING_WHEN_BARE.contains(type)) {
                continue;
            }
            String marker = GuardrailModels.marker(type);
            // Only annotations this platform renders at all are in scope: a platform that opts out
            // of an annotation opts out of it in both fixtures, and that is not this test's subject.
            if (populated.contains(marker) && !bare.contains(marker)) {
                dropped.add(type.getSimpleName());
            }
        }

        assertEquals(List.of(), dropped,
            platform + " renders these annotations when their members are populated but drops them "
                + "when written bare, so the plain form of the annotation reaches nobody");
    }

    @Test
    @DisplayName("every annotation still produces a granular stanza when written bare")
    void bareAnnotationsStillProduceGranularStanzas() {
        Map<TaggedElement, GranularBody> populated =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotation());
        Map<TaggedElement, GranularBody> bare =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotationWithMembersUnset());

        List<String> dropped = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (RENDERS_NOTHING_WHEN_BARE.contains(type)) {
                continue;
            }
            if (hasStanza(populated, type) && !hasStanza(bare, type)) {
                dropped.add(type.getSimpleName());
            }
        }

        assertEquals(List.of(), dropped,
            "these annotations produce a per-element rule stanza only when their optional members "
                + "are populated, so the bare form gets a rule file with no mention of it");
    }

    @Test
    @DisplayName("a granular stanza written bare still has a body")
    void bareGranularStanzasAreNotEmpty() {
        Map<TaggedElement, GranularBody> bare =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotationWithMembersUnset());

        List<String> empty = new ArrayList<>();
        for (Map.Entry<TaggedElement, GranularBody> owner : bare.entrySet()) {
            for (GranularBody.Entry stanza : owner.getValue().entries()) {
                boolean noBody = stanza.lines().stream().allMatch(String::isBlank);
                if (stanza.title().isBlank() || noBody) {
                    empty.add(owner.getKey().qualifiedName() + " -> " + stanza.title());
                }
            }
        }

        assertEquals(List.of(), empty,
            "a stanza whose whole body came from an optional member collapses to a heading with "
                + "nothing under it when the member is unset");
    }

    /** True when any stanza on any element mentions {@code type}'s fixture element. */
    private static boolean hasStanza(Map<TaggedElement, GranularBody> rules,
                                     Class<? extends Annotation> type) {
        String marker = GuardrailModels.marker(type);
        for (Map.Entry<TaggedElement, GranularBody> owner : rules.entrySet()) {
            if (!owner.getKey().qualifiedName().equals(marker)) {
                continue;
            }
            if (!owner.getValue().entries().isEmpty()) {
                return true;
            }
        }
        return false;
    }

}
