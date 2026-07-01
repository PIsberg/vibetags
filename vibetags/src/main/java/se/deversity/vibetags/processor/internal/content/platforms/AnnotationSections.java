package se.deversity.vibetags.processor.internal.content.platforms;

import javax.lang.model.element.Element;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
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
     * {@link CursorRenderer} and {@link WindsurfRenderer} — kept in one place so the identical
     * text isn't duplicated source-side across both files.
     */
    static final List<Section> EMOJI_STYLE_NEWEST_ANNOTATIONS = List.of(
        Section.of("\n## 🚫 ACCESS & CALLS LIMITATIONS\nThe following elements have strict caller access limits. AI must not invoke them from outside the allowed boundaries.\n\n", AnnotationCollector::callersOnly, FormatterRegistry.callersOnly()),
        Section.of("\n## 🛡️ SANDBOX & TEST HARNESS EXCLUSION\nThe following elements are strictly sandbox/test code. Production code must never import or reference them.\n\n", AnnotationCollector::sandboxOnly, FormatterRegistry.sandboxOnly()),
        Section.of("\n## ⚡ MEMORY ALLOCATION BUDGETS\nThe following elements have strict heap allocation, autoboxing, or garbage budgets. Optimize allocations carefully.\n\n", AnnotationCollector::memoryBudget, FormatterRegistry.memoryBudget()),
        Section.of("\n## 🧠 DETERMINISTIC PURE FUNCTIONS\nThe following elements must remain pure functions without side effects or mutations.\n\n", AnnotationCollector::pure, FormatterRegistry.pure()),
        Section.of("\n## 🧱 FRAMEWORK-FREE DOMAIN ENTITIES\nThe following elements are pure Domain Models. Do not import Spring, JPA/Hibernate, Jackson, or other framework packages.\n\n", AnnotationCollector::domainModel, FormatterRegistry.domainModel()),
        Section.of("\n## ❄️ open-closed EXTENSION PATTERNS\nThe following elements require extension using polymorphic patterns (Strategy/Visitor). Do not append branch conditionals.\n\n", AnnotationCollector::extensible, FormatterRegistry.extensible()),
        Section.of("\n## 🚨 MANDATORY INPUT SANITIZATION\nThe following parameters/fields must go through strict sanitizers before hitting queries or renderers.\n\n", AnnotationCollector::inputSanitized, FormatterRegistry.inputSanitized()),
        Section.of("\n## 🔒 SECURE LOGGING MASKING\nThe following sensitive elements must be masked, hashed, or omitted from log/stdout streams.\n\n", AnnotationCollector::secureLogging, FormatterRegistry.secureLogging()),
        Section.of("\n## 📋 REQUIRED CHAIN-OF-THOUGHT EXPLANATIONS\nAny change made to these elements requires a step-by-step mathematical/architectural proof of correctness in the PR/walkthrough.\n\n", AnnotationCollector::explain, FormatterRegistry.explain()),
        Section.of("\n## 🛠️ EXPERIMENTAL PROTOTYPE STUBS\nStrict QA constraints and tests are relaxed for these elements, but production classes must never import them.\n\n", AnnotationCollector::prototype, FormatterRegistry.prototype()),
        Section.of("\n## ⚠️ SUNSET DEPRACTED APIs\nStrictly sunset under deprecation. Introducing *new* references or calls to these elements is forbidden.\n\n", AnnotationCollector::sunset, FormatterRegistry.sunset()),
        Section.of("\n## 🚧 TEMPORARY CODE WORKAROUNDS\nTemporary stubs or hacks that must be refactored or removed before their expiration limit.\n\n", AnnotationCollector::temporary, FormatterRegistry.temporary())
    );
}
