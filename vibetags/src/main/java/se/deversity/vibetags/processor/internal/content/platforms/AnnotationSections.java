package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.SectionCatalog;

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

    /**
     * Builds a {@link Section} by looking up its header text in {@link SectionCatalog} for the
     * given platform/key, instead of a renderer hardcoding the string itself. Returns a headerless
     * section when the catalog says the platform folds this bucket into the previous one.
     */
    static Section section(Platform platform, SectionCatalog.Key key, Function<AnnotationCollector, Set<Element>> accessor, AnnotationFormatter formatter) {
        String header = SectionCatalog.header(platform, key);
        return header == null ? Section.headerless(accessor, formatter) : Section.of(header, accessor, formatter);
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

    /**
     * The "# AUTO-GENERATED AI RULES ... LOCKED FILES ... CONTEXTUAL RULES" opening shared
     * verbatim by {@link CursorRenderer} and {@link WindsurfRenderer}.
     */
    static void renderLockedAndContextPreamble(StringBuilder sb, AnnotationCollector collector, Platform platform, String generatedHeader) {
        sb.append("# AUTO-GENERATED AI RULES\n")
          .append(generatedHeader)
          .append("# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n");
        for (Element e : collector.locked()) {
            FormatterRegistry.locked().format(e, sb, platform);
        }
        sb.append("\n## CONTEXTUAL RULES\n");
        for (Element e : collector.context()) {
            FormatterRegistry.context().format(e, sb, platform);
        }
    }

    /** Appends {@code tail} after {@code head} into one immutable list. */
    static List<Section> concat(List<Section> head, List<Section> tail) {
        List<Section> combined = new java.util.ArrayList<>(head.size() + tail.size());
        combined.addAll(head);
        combined.addAll(tail);
        return List.copyOf(combined);
    }

    /**
     * The twelve newest annotation sections, in the emoji-headed wording shared verbatim by
     * {@link CursorRenderer} and {@link WindsurfRenderer} — sourced from {@link SectionCatalog}'s
     * default (Cursor) wording so the text isn't duplicated source-side across both files.
     */
    static final List<Section> EMOJI_STYLE_NEWEST_ANNOTATIONS = List.of(
        section(Platform.CURSOR, SectionCatalog.Key.CALLERS_ONLY, AnnotationCollector::callersOnly, FormatterRegistry.callersOnly()),
        section(Platform.CURSOR, SectionCatalog.Key.SANDBOX_ONLY, AnnotationCollector::sandboxOnly, FormatterRegistry.sandboxOnly()),
        section(Platform.CURSOR, SectionCatalog.Key.MEMORY_BUDGET, AnnotationCollector::memoryBudget, FormatterRegistry.memoryBudget()),
        section(Platform.CURSOR, SectionCatalog.Key.PURE, AnnotationCollector::pure, FormatterRegistry.pure()),
        section(Platform.CURSOR, SectionCatalog.Key.DOMAIN_MODEL, AnnotationCollector::domainModel, FormatterRegistry.domainModel()),
        section(Platform.CURSOR, SectionCatalog.Key.EXTENSIBLE, AnnotationCollector::extensible, FormatterRegistry.extensible()),
        section(Platform.CURSOR, SectionCatalog.Key.INPUT_SANITIZED, AnnotationCollector::inputSanitized, FormatterRegistry.inputSanitized()),
        section(Platform.CURSOR, SectionCatalog.Key.SECURE_LOGGING, AnnotationCollector::secureLogging, FormatterRegistry.secureLogging()),
        section(Platform.CURSOR, SectionCatalog.Key.EXPLAIN, AnnotationCollector::explain, FormatterRegistry.explain()),
        section(Platform.CURSOR, SectionCatalog.Key.PROTOTYPE, AnnotationCollector::prototype, FormatterRegistry.prototype()),
        section(Platform.CURSOR, SectionCatalog.Key.SUNSET, AnnotationCollector::sunset, FormatterRegistry.sunset()),
        section(Platform.CURSOR, SectionCatalog.Key.TEMPORARY, AnnotationCollector::temporary, FormatterRegistry.temporary())
    );
}
