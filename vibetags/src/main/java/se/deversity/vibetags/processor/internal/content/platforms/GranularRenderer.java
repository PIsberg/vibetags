package se.deversity.vibetags.processor.internal.content.platforms;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.TaggedElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.AnnotationDescriptor;
import se.deversity.vibetags.processor.internal.content.AnnotationDescriptors;
import se.deversity.vibetags.processor.internal.content.GranularBody;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating per-class granular rules.
 */
public final class GranularRenderer implements PlatformRenderer {

    /**
     * A stanza line that is a bold label and nothing else: {@code - **Focus**:} and the line ends.
     *
     * <p>Trailing whitespace is part of the match, because the concatenation that builds these
     * bodies writes the separator before it knows whether a value follows, so the empty form is
     * {@code "- **Focus**: "} rather than {@code "- **Focus**:"}.
     */
    private static final Pattern EMPTY_LABEL = Pattern.compile("\\s*-\\s+\\*\\*[^*]+\\*\\*:\\s*");

    @Override
    public @Nullable String render(GuardrailModel model, Platform platform, RenderingContext context) {
        // Return null since granular output is written per-element via writeGranular, not as a single file.
        return null;
    }

    public Map<TaggedElement, GranularBody> renderGranular(GuardrailModel model) {
        Map<TaggedElement, GranularBody> elementRules = new LinkedHashMap<>();

        // Section order within a rule file is AnnotationDescriptors.ALL's order. Each entry owns
        // its title and its body; what stays here is the choke point they all pass through.
        for (AnnotationDescriptor descriptor : AnnotationDescriptors.ALL) {
            for (TaggedElement e : model.of(descriptor.type())) {
                String body = descriptor.granularStanza().of(e);
                if (body != null) {
                    appendToGranular(elementRules, e, descriptor.granularTitle(), body);
                }
            }
        }

        return elementRules;
    }

    /**
     * Records one stanza for {@code element} under {@code title}. The stanza is kept structured
     * (see {@link GranularBody}) rather than appended as text, so the file-level renderer can hoist
     * the constant rule sentence shared by every element in a section instead of repeating it.
     *
     * <p>Lines that carry nothing are dropped here, and a stanza left with no lines is not recorded
     * at all. The stanzas in {@code AnnotationDescriptors} are built by concatenation, so a member
     * the author never set still writes its label and its colon;
     * {@code CommonFormatterHelper.bullet} has guarded the
     * aggregate renderers against exactly that since the bare-annotation sweep, and the granular
     * renderer never grew an equivalent (#507). A bare {@code @AIContext} put {@code - **Focus**:}
     * and {@code - **Avoid**:} into the rule file with nothing after them: the label costs an
     * agent's context window, returns no guardrail, and reads as though the value went missing
     * rather than as though nobody wrote one.
     *
     * <p>The guard is here rather than in the forty-odd table entries deliberately. One choke point
     * covers the annotation added next as well as the forty-four that exist, and
     * {@code UnsetMemberRenderingTest.granularStanzasLeaveNoDanglingText} sweeps all of them
     * against it; a guard spelled out per entry is one the next annotation can be written
     * without.
     *
     * <p>Dropping the whole stanza is not a separate decision. A stanza is a heading plus a body,
     * and a heading with nothing under it reads to an agent as an annotation that says nothing,
     * which is why a bare {@code @AIAudit} has always been skipped. A bare {@code @AIContext} or
     * {@code @AIArchitecture} now takes the same route, and the author is told: {@code CoreRules}
     * and {@code ArchitectureRule} already warn at compile time that the annotation will be
     * ignored.
     */
    private void appendToGranular(Map<TaggedElement, GranularBody> elementRules, TaggedElement element, String title, String content) {
        List<String> lines = carryingLines(content);
        if (lines.isEmpty()) {
            return;
        }
        TaggedElement owner = element.owner();
        elementRules.computeIfAbsent(owner, k -> new GranularBody())
            .add(new GranularBody.Entry(owner, element, title, lines));
    }

    /**
     * {@code content} split into stanza lines, without the ones that are only a label.
     *
     * <p>Every surviving line is right-trimmed. A summary assembled from optional parts ends in the
     * separator that would have preceded the part nobody supplied, and this repository's own
     * {@code trailing-whitespace} pre-commit hook would then rewrite the generated file on commit
     * and hand the next build a diff to undo.
     */
    private static List<String> carryingLines(String content) {
        List<String> kept = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            if (!EMPTY_LABEL.matcher(trimmed).matches()) {
                kept.add(trimmed);
            }
        }
        return kept;
    }
}
