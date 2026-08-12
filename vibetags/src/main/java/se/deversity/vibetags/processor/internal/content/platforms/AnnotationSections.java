package se.deversity.vibetags.processor.internal.content.platforms;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.TaggedElement;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import se.deversity.vibetags.processor.model.GuardrailModel;
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
    record Section(@Nullable String header, Function<GuardrailModel, Set<TaggedElement>> accessor, AnnotationFormatter formatter) {
        static Section of(@Nullable String header, Function<GuardrailModel, Set<TaggedElement>> accessor, AnnotationFormatter formatter) {
            return new Section(header, accessor, formatter);
        }

        static Section headerless(Function<GuardrailModel, Set<TaggedElement>> accessor, AnnotationFormatter formatter) {
            return new Section(null, accessor, formatter);
        }
    }

    /**
     * Builds a {@link Section} by looking up its header text in {@link SectionCatalog} for the
     * given platform/key, instead of a renderer hardcoding the string itself. Returns a headerless
     * section when the catalog says the platform folds this bucket into the previous one.
     */
    static Section section(Platform platform, SectionCatalog.Key key, Function<GuardrailModel, Set<TaggedElement>> accessor, AnnotationFormatter formatter) {
        String header = SectionCatalog.header(platform, key);
        return header == null ? Section.headerless(accessor, formatter) : Section.of(header, accessor, formatter);
    }

    static void render(StringBuilder sb, GuardrailModel model, Platform platform, List<Section> sections) {
        for (Section s : sections) {
            Set<TaggedElement> elements = s.accessor().apply(model);
            if (s.header() != null) {
                if (elements.isEmpty()) continue;
                sb.append(s.header());
            }
            for (TaggedElement e : elements) {
                s.formatter().format(e, sb, platform);
            }
        }
    }

    /**
     * The "# AUTO-GENERATED AI RULES ... LOCKED FILES" opening (locked entries only, no contextual
     * rules). Used by {@link CursorRenderer} and {@link WindsurfRenderer} in scoped-index mode,
     * where {@code @AIContext} detail moves to the scoped rule files rather than the aggregate.
     */
    static void renderLockedPreamble(StringBuilder sb, GuardrailModel model, Platform platform, String generatedHeader) {
        sb.append("# AUTO-GENERATED AI RULES\n")
          .append(generatedHeader)
          .append("# Do not edit manually.\n\n## LOCKED FILES (DO NOT EDIT)\n");
        for (TaggedElement e : model.locked()) {
            FormatterRegistry.locked().format(e, sb, platform);
        }
    }

    /**
     * The same preamble for an aggregate that has collapsed to a scoped-rules index, with the
     * "LOCKED FILES" heading dropped when nothing is locked.
     *
     * <p>An empty heading costs one line in a single-module file and is invisible. In a reactor it
     * is emitted <em>once per module</em> — six modules with no {@code @AILocked} between them turn
     * a file that is supposed to be a lean index into pages of empty headings
     * (<a href="https://github.com/PIsberg/vibetags/issues/319">issue #319</a>). Full (non-indexed)
     * output keeps the unconditional heading, so single-opt-in aggregates stay byte-identical.
     */
    static void renderIndexedPreamble(StringBuilder sb, GuardrailModel model, Platform platform, String generatedHeader) {
        sb.append("# AUTO-GENERATED AI RULES\n")
          .append(generatedHeader)
          .append("# Do not edit manually.\n");
        if (model.locked().isEmpty()) {
            return;
        }
        sb.append("\n## LOCKED FILES (DO NOT EDIT)\n");
        for (TaggedElement e : model.locked()) {
            FormatterRegistry.locked().format(e, sb, platform);
        }
    }

    /**
     * The "# AUTO-GENERATED AI RULES ... LOCKED FILES ... CONTEXTUAL RULES" opening shared
     * verbatim by {@link CursorRenderer} and {@link WindsurfRenderer}.
     */
    static void renderLockedAndContextPreamble(StringBuilder sb, GuardrailModel model, Platform platform, String generatedHeader) {
        renderLockedPreamble(sb, model, platform, generatedHeader);
        sb.append("\n## CONTEXTUAL RULES\n");
        for (TaggedElement e : model.context()) {
            FormatterRegistry.context().format(e, sb, platform);
        }
    }

    /**
     * The always-inline safety buckets an aggregate keeps even when it collapses to a scoped-rules
     * index: audit, ignore, privacy, core, secure (locked is rendered by the preamble). Every other
     * bucket moves to the scoped files. Headers use the given platform's own wording, so these
     * sections read identically to full mode.
     */
    static void renderInlineSafetySections(StringBuilder sb, GuardrailModel model, Platform platform) {
        render(sb, model, platform, List.of(
            section(platform, SectionCatalog.Key.AUDIT, GuardrailModel::audit, FormatterRegistry.audit()),
            section(platform, SectionCatalog.Key.IGNORE, GuardrailModel::ignore, FormatterRegistry.ignore()),
            section(platform, SectionCatalog.Key.PRIVACY, GuardrailModel::privacy, FormatterRegistry.privacy()),
            section(platform, SectionCatalog.Key.CORE, GuardrailModel::core, FormatterRegistry.core()),
            section(platform, SectionCatalog.Key.SECURE, GuardrailModel::secure, FormatterRegistry.secure())
        ));
    }

    /** Appends {@code tail} after {@code head} into one immutable list. */
    static List<Section> concat(List<Section> head, List<Section> tail) {
        List<Section> combined = new java.util.ArrayList<>(head.size() + tail.size());
        combined.addAll(head);
        combined.addAll(tail);
        return List.copyOf(combined);
    }

    /**
     * The seventeen annotations added after {@code @AISecure}, as sections carrying
     * {@code platform}'s own heading wording from {@link SectionCatalog}.
     *
     * <p>Built per platform rather than shared as one constant because every markdown renderer
     * that walks these buckets has to walk <em>all</em> of them, and a hand-maintained tail per
     * renderer is how Codex and Qwen ended up rendering 27 of 44 annotations while Cursor rendered
     * 44 — the formatters had the arms, the bucket lists had stopped growing. One list, taken by
     * every caller, means annotation 45 is added here once.
     */
    static List<Section> newestAnnotationSections(Platform platform) {
        return List.of(
            section(platform, SectionCatalog.Key.CALLERS_ONLY, GuardrailModel::callersOnly, FormatterRegistry.callersOnly()),
            section(platform, SectionCatalog.Key.SANDBOX_ONLY, GuardrailModel::sandboxOnly, FormatterRegistry.sandboxOnly()),
            section(platform, SectionCatalog.Key.MEMORY_BUDGET, GuardrailModel::memoryBudget, FormatterRegistry.memoryBudget()),
            section(platform, SectionCatalog.Key.PURE, GuardrailModel::pure, FormatterRegistry.pure()),
            section(platform, SectionCatalog.Key.DOMAIN_MODEL, GuardrailModel::domainModel, FormatterRegistry.domainModel()),
            section(platform, SectionCatalog.Key.EXTENSIBLE, GuardrailModel::extensible, FormatterRegistry.extensible()),
            section(platform, SectionCatalog.Key.INPUT_SANITIZED, GuardrailModel::inputSanitized, FormatterRegistry.inputSanitized()),
            section(platform, SectionCatalog.Key.SECURE_LOGGING, GuardrailModel::secureLogging, FormatterRegistry.secureLogging()),
            section(platform, SectionCatalog.Key.EXPLAIN, GuardrailModel::explain, FormatterRegistry.explain()),
            section(platform, SectionCatalog.Key.PROTOTYPE, GuardrailModel::prototype, FormatterRegistry.prototype()),
            section(platform, SectionCatalog.Key.SUNSET, GuardrailModel::sunset, FormatterRegistry.sunset()),
            section(platform, SectionCatalog.Key.TEMPORARY, GuardrailModel::temporary, FormatterRegistry.temporary()),
            section(platform, SectionCatalog.Key.GENERATED, GuardrailModel::generated, FormatterRegistry.generated()),
            section(platform, SectionCatalog.Key.LOAD_BEARING, GuardrailModel::loadBearing, FormatterRegistry.loadBearing()),
            section(platform, SectionCatalog.Key.BANNED_API, GuardrailModel::bannedApi, FormatterRegistry.bannedApi()),
            section(platform, SectionCatalog.Key.THREAD_AFFINITY, GuardrailModel::threadAffinity, FormatterRegistry.threadAffinity()),
            section(platform, SectionCatalog.Key.KEEP_IN_SYNC, GuardrailModel::keepInSync, FormatterRegistry.keepInSync())
        );
    }

    /**
     * The same sections in Cursor's emoji-headed wording, shared verbatim by
     * {@link CursorRenderer} and {@link WindsurfRenderer}.
     */
    static final List<Section> EMOJI_STYLE_NEWEST_ANNOTATIONS =
        newestAnnotationSections(Platform.CURSOR);
}
