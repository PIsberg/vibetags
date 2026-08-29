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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /**
     * The same, for the per-element rule files, which drop two more.
     *
     * <p>A stanza is a heading plus a body. {@code @AIContext} written with neither a focus nor an
     * avoid, and {@code @AIArchitecture} with neither a layer nor a prohibited reference, have no
     * body to put under the heading, and this class already states that a heading with nothing
     * under it reads to an agent as an annotation that says nothing. So they take the same route
     * {@code @AIAudit} has always taken and record no stanza (#507).
     *
     * <p>They are not in {@link #RENDERS_NOTHING_WHEN_BARE}, because the aggregate files still
     * name the element: there the annotation's presence is the line, and the members are detail
     * hung off it. Only the granular form needs a body of its own.
     */
    private static final Set<Class<? extends Annotation>> GRANULAR_RENDERS_NOTHING_WHEN_BARE =
        Set.of(se.deversity.vibetags.annotations.AIAudit.class,
               se.deversity.vibetags.annotations.AIContext.class,
               se.deversity.vibetags.annotations.AIArchitecture.class);

    /**
     * A markdown bullet whose bold label is followed by nothing: {@code - **Reason**:} and the line
     * ends. The label is the whole cost — an agent reading the file pays for it and learns nothing.
     */
    private static final Pattern EMPTY_LABELLED_BULLET =
        Pattern.compile("(?m)^\\s*-\\s+\\*\\*[^*\\n]+\\*\\*:[ \\t]*$");

    /** An XML element with nothing but whitespace between its tags: {@code <reason></reason>}. */
    private static final Pattern EMPTY_XML_ELEMENT =
        Pattern.compile("<([a-zA-Z_][\\w-]*)>\\s*</\\1>");

    /**
     * The same defect in the shapes the two patterns above do not match: a bullet that ends on a
     * separator with nothing after it, {@code * `Foo` - Reason:} or just {@code * `Foo` -}.
     *
     * <p>Scoped to list items on purpose. A bare line ending in {@code :} is ordinary YAML, and a
     * line of dashes is an ordinary markdown rule; neither is this bug.
     */
    private static final Pattern DANGLING_BULLET_SEPARATOR =
        Pattern.compile("(?m)^\\s*[-*]\\s.*\\S[ \\t]*[:\\-][ \\t]*$");

    /** An empty code span — {@code Belongs to layer: ``} — where a member should have been. */
    private static final Pattern EMPTY_CODE_SPAN = Pattern.compile("``");

    /** An empty list — {@code Only callable by: []} — where members should have been. */
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\[\\]");

    /** A label whose value is missing and whose punctuation closed over the hole: {@code Must not use: .} */
    private static final Pattern LABEL_THEN_PUNCTUATION = Pattern.compile(":[ \\t]*[.,)]");

    static Stream<Platform> aggregatePlatforms() {
        return Stream.of(Platform.values()).filter(p -> !p.name().endsWith("_GRANULAR"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregatePlatforms")
    @DisplayName("an unset optional member leaves no dangling separator, empty span or empty list")
    void unsetMembersLeaveNoDanglingText(Platform platform) {
        String bare = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModels.everyAnnotationWithMembersUnset(), platform, CONTEXT);

        List<String> dangling = new ArrayList<>();
        collect(DANGLING_BULLET_SEPARATOR, bare, dangling);
        collectLinesContaining(EMPTY_CODE_SPAN, bare, dangling);
        collectLinesContaining(EMPTY_BRACKETS, bare, dangling);
        collectLinesContaining(LABEL_THEN_PUNCTUATION, bare, dangling);

        assertEquals(List.of(), dangling,
            platform + " renders a member that was never set as a separator with nothing after "
                + "it, an empty `` span, an empty [] list, or a label swallowed by its own "
                + "punctuation. An annotation written bare must read as though the member does "
                + "not exist, not as though its value went missing");
    }

    private static void collect(Pattern pattern, String rendered, List<String> into) {
        for (Matcher m = pattern.matcher(rendered); m.find(); ) {
            into.add(m.group().trim());
        }
    }

    /** Reports the whole line, so a mid-line {@code ``} or {@code []} is identifiable. */
    private static void collectLinesContaining(Pattern pattern, String rendered, List<String> into) {
        for (String line : rendered.split("\n", -1)) {
            if (pattern.matcher(line).find()) {
                into.add(line.trim());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregatePlatforms")
    @DisplayName("an unset optional member emits no label at all, rather than a label with nothing after it")
    void unsetMembersLeaveNoEmptyLabels(Platform platform) {
        String bare = PlatformRendererRegistry.getRenderer(platform)
            .render(GuardrailModels.everyAnnotationWithMembersUnset(), platform, CONTEXT);

        List<String> empty = new ArrayList<>();
        for (Matcher m = EMPTY_LABELLED_BULLET.matcher(bare); m.find(); ) {
            empty.add(m.group().trim());
        }
        for (Matcher m = EMPTY_XML_ELEMENT.matcher(bare); m.find(); ) {
            empty.add(m.group().replace("\n", "\\n"));
        }

        assertEquals(List.of(), empty,
            platform + " emits a label with nothing after it when the member behind it is unset. "
                + "Guard the member the way the same formatter already guards it on other "
                + "platforms — CommonFormatterHelper.bullet and .element do it in one place");
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
            if (GRANULAR_RENDERS_NOTHING_WHEN_BARE.contains(type)) {
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

    /**
     * The same contract for the per-element rule files: an optional member nobody set must leave no
     * label, no empty list, no empty code span and no dangling separator behind (#507).
     *
     * <p>The two granular assertions above ask only whether a bare annotation still produces a
     * stanza with a body. It does, and the body was a bold label with nothing after it: the
     * granular renderer builds its stanzas by string concatenation and never grew the guard
     * {@code CommonFormatterHelper.bullet} applies once for every aggregate platform.
     *
     * <p>Uses {@link GuardrailModels#everyAnnotationWithOptionalMembersUnset()} rather than the
     * fully-emptied fixture, so every finding is a state a source file can reach: a member with no
     * declared default does not compile when omitted, and blanking it would report defects nobody
     * can trigger.
     */
    @Test
    @DisplayName("an unset optional member leaves nothing dangling in the granular rule file")
    void granularStanzasLeaveNoDanglingText() {
        List<String> dangling = new ArrayList<>();
        for (Map.Entry<TaggedElement, GranularBody> owner : granularWithOptionalMembersUnset().entrySet()) {
            for (GranularBody.Entry stanza : owner.getValue().entries()) {
                for (String line : stanza.lines()) {
                    if (EMPTY_LABELLED_BULLET.matcher(line).find()
                            || DANGLING_BULLET_SEPARATOR.matcher(line).find()
                            || EMPTY_CODE_SPAN.matcher(line).find()
                            || EMPTY_BRACKETS.matcher(line).find()
                            || LABEL_THEN_PUNCTUATION.matcher(line).find()) {
                        dangling.add(owner.getKey().qualifiedName() + " -> " + line.strip());
                    }
                }
            }
        }

        assertEquals(List.of(), dangling,
            "the per-element rule file renders a member that was never set as a label with nothing "
                + "after it, an empty [] list, an empty `` span, or a separator with nothing "
                + "following. An annotation written the short way must read as though the member "
                + "does not exist, not as though its value went missing");
    }

    /**
     * Guards the fixture: an empty model, or one whose annotations stopped producing stanzas, would
     * make the sweep above vacuous. Exact rather than a lower bound, so a bucket that quietly stops
     * rendering shows up here instead of shrinking the sweep in silence. Three annotations are
     * subtracted, for the reason {@link #GRANULAR_RENDERS_NOTHING_WHEN_BARE} states.
     */
    @Test
    void theOptionalUnsetFixtureStillProducesStanzas() {
        assertEquals(GuardrailAnnotations.ALL.size() - GRANULAR_RENDERS_NOTHING_WHEN_BARE.size(),
            granularWithOptionalMembersUnset().size(),
            "expected one owner per annotation that renders anything when written the short way");
    }

    private static Map<TaggedElement, GranularBody> granularWithOptionalMembersUnset() {
        return new GranularRenderer().renderGranular(
            GuardrailModels.everyAnnotationWithOptionalMembersUnset());
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
