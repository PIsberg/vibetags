package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Shared driver for the markdown renderers (Cursor, Copilot, Windsurf, Zed) whose bodies are all
 * the same long, ordered walk over every annotation bucket: if it's non-empty, print a heading,
 * then format each element. Each renderer supplies its own heading text and section order as
 * data via {@link Section}; this class is the one place that walks the list, so wiring a new
 * annotation into these renderers is a one-line addition per platform instead of a hand-written
 * block — the shape of duplication that once let a shared section slip out of sync across files.
 */
final class AnnotationSections {

    private AnnotationSections() {}

    /**
     * One renderable section. A {@code null} header means the section is folded into the
     * preceding one: its elements are still formatted, but with no heading of their own and
     * without gating on emptiness (matching the source renderers, where a handful of buckets are
     * appended directly after the contextual-rules preamble).
     */
    record Section(String header, Function<AnnotationCollector, Set<Element>> accessor, AnnotationFormatter formatter) {
        static Section of(String header, Function<AnnotationCollector, Set<Element>> accessor, AnnotationFormatter formatter) {
            return new Section(header, accessor, formatter);
        }

        static Section headerless(Function<AnnotationCollector, Set<Element>> accessor, AnnotationFormatter formatter) {
            return new Section(null, accessor, formatter);
        }
    }

    static void render(StringBuilder sb, AnnotationCollector collector, Platform platform, List<Section> sections) {
        for (Section s : sections) {
            Set<Element> elements = s.accessor().apply(collector);
            if (s.header() != null) {
                if (elements.isEmpty()) continue;
                sb.append(s.header());
            }
            for (Element e : elements) {
                s.formatter().format(e, sb, platform);
            }
        }
    }
}
