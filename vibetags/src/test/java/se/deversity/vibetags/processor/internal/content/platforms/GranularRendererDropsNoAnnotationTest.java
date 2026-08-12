package se.deversity.vibetags.processor.internal.content.platforms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.GuardrailModels;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.model.GuardrailAnnotations;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-element rule files must carry every annotation, for the same reason the aggregate files
 * must: {@code .claude/rules/com-example-Foo.md} is the only place a scoped-rules project keeps the
 * detail, and a bucket the renderer forgets to walk is a guardrail that reaches nobody.
 *
 * <p>{@code renderGranular} is one 250-line method with forty-four hand-written bucket loops, and
 * PIT could delete nineteen of those {@code appendToGranular} calls outright without a single test
 * failing. Deleting one is exactly the bug that had already happened four times in the aggregate
 * renderers — a loop that never got added when the annotation did.
 *
 * <p>Derived from {@link GuardrailAnnotations#ALL}, so annotation 45 is covered on the day it lands
 * and the failure names it. The stanza's text is deliberately not pinned: wording is the renderer's
 * business, presence is the contract.
 */
class GranularRendererDropsNoAnnotationTest {

    @Test
    @DisplayName("every annotation produces a stanza in the element's granular rule file")
    void everyAnnotationReachesTheGranularFile() {
        Map<TaggedElement, GranularBody> rules =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotation());

        List<String> dropped = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            if (stanzasFor(rules, type).isEmpty()) {
                dropped.add(type.getSimpleName());
            }
        }

        assertEquals(List.of(), dropped,
            "renderGranular walks a hand-written list of buckets and these are not in it, so an "
                + "element carrying one of them gets a rule file with no mention of it at all");
    }

    @Test
    @DisplayName("a stanza carries a title and a body, not an empty heading")
    void everyStanzaHasContent() {
        Map<TaggedElement, GranularBody> rules =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotation());

        List<String> empty = new ArrayList<>();
        for (Class<? extends Annotation> type : GuardrailAnnotations.ALL) {
            for (GranularBody.Entry stanza : stanzasFor(rules, type)) {
                boolean hasBody = stanza.lines().stream().anyMatch(line -> !line.isBlank());
                if (stanza.title().isBlank() || !hasBody) {
                    empty.add(type.getSimpleName() + " -> \"" + stanza.title() + "\" " + stanza.lines());
                }
            }
        }

        assertEquals(List.of(), empty,
            "a stanza with no title or no body renders as a heading with nothing under it, which "
                + "reads to an agent as an annotation that says nothing");
    }

    /** Every stanza recorded for the fixture element of {@code type}. */
    private static List<GranularBody.Entry> stanzasFor(
            Map<TaggedElement, GranularBody> rules, Class<? extends Annotation> type) {
        String marker = GuardrailModels.marker(type);
        List<GranularBody.Entry> found = new ArrayList<>();
        for (GranularBody body : rules.values()) {
            for (GranularBody.Entry stanza : body.entries()) {
                if (marker.equals(stanza.element().qualifiedName())) {
                    found.add(stanza);
                }
            }
        }
        return found;
    }

    /** Guards the fixture: a model that lost its elements would make both tests above vacuous. */
    @Test
    void theFixtureCarriesOneElementPerAnnotation() {
        Map<TaggedElement, GranularBody> rules =
            new GranularRenderer().renderGranular(GuardrailModels.everyAnnotation());

        assertTrue(rules.size() >= GuardrailAnnotations.ALL.size(),
            "expected at least one owner per annotation, got " + rules.size()
                + " for " + GuardrailAnnotations.ALL.size() + " annotations");
    }
}
