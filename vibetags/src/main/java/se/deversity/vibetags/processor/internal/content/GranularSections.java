package se.deversity.vibetags.processor.internal.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import se.deversity.vibetags.processor.internal.ElementNaming;

/**
 * Renders a granular rule file body from structured {@link GranularBody.Entry} stanzas, collapsing
 * the guardrail sentences that are compile-time constants instead of repeating them once per
 * annotated element (issue #313).
 *
 * <p>Stanzas are grouped by section title. Within a section of at least
 * {@link #MIN_GROUP_SIZE} stanzas, the lines shared by <em>every</em> stanza — its longest common
 * leading and trailing runs, which is where the constant {@code - **Rule**:} sentence always sits —
 * are hoisted once under the section heading and pluralized; each element keeps only the lines that
 * actually differ (typically its {@code - **Reason**:}). Elements whose stanza is entirely shared
 * collapse further into a single {@code - **Applies to**:} list.
 *
 * <p>When a section has fewer stanzas than the threshold, or its stanzas share no lines at all,
 * the per-class output is emitted exactly as before — byte-for-byte — so enabling this costs
 * nothing for the single-element case.
 *
 * <p>{@code qualified} mode is used for role/topic files, whose stanzas come from several owners:
 * headings there carry the element's fully-qualified path so two classes cannot produce the same
 * heading, and the file is organised by topic rather than by owning class.
 */
public final class GranularSections {

    /**
     * Minimum stanzas in one section before its shared lines are hoisted. Below this a heading
     * plus a member list is longer than the text it replaces, so a lone element keeps the plain
     * form. Mirrors the threshold constant in {@code ClaudeTestDrivenSection}.
     */
    public static final int MIN_GROUP_SIZE = 2;

    private GranularSections() {}

    /**
     * Renders {@code entries} into a rule file body.
     *
     * @param entries   stanzas in emission order (annotation-type order, as collected)
     * @param qualified {@code true} for role/topic files spanning several owners — headings use
     *                  fully-qualified element paths; {@code false} for per-class files, which
     *                  keep the historical heading shape
     */
    public static String render(List<GranularBody.Entry> entries, boolean qualified) {
        Map<String, List<GranularBody.Entry>> byTitle = new LinkedHashMap<>();
        for (GranularBody.Entry e : entries) {
            byTitle.computeIfAbsent(e.title(), k -> new ArrayList<>()).add(e);
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<GranularBody.Entry>> section : byTitle.entrySet()) {
            String title = section.getKey();
            List<GranularBody.Entry> group = section.getValue();
            Shared shared = group.size() >= MIN_GROUP_SIZE ? shared(group) : Shared.NONE;
            if (!shared.lines().isEmpty()) {
                appendCollapsed(out, title, group, shared, qualified);
            } else if (qualified) {
                appendQualified(out, title, group);
            } else {
                appendPlain(out, group);
            }
        }
        return out.toString();
    }

    /** Historical per-class layout: one heading + body per stanza, in emission order. */
    private static void appendPlain(StringBuilder out, List<GranularBody.Entry> group) {
        for (GranularBody.Entry e : group) {
            block(out, e.ownerLevel() ? "## " + e.title() : memberHeading(e.element()), e.lines());
        }
    }

    /** Role/topic layout without hoisting: one section heading, one qualified heading per stanza. */
    private static void appendQualified(StringBuilder out, String title, List<GranularBody.Entry> group) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append("## ").append(title).append('\n');
        for (GranularBody.Entry e : group) {
            out.append('\n').append("### ").append(ElementNaming.elementPath(e.element())).append('\n');
            out.append(String.join("\n", e.lines())).append('\n');
        }
    }

    /** Hoisted layout: the shared sentence once, then only what differs per element. */
    private static void appendCollapsed(StringBuilder out, String title, List<GranularBody.Entry> group,
                                        Shared shared, boolean qualified) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append("## ").append(title).append('\n');
        for (String line : shared.lines()) {
            out.append(pluralize(line)).append('\n');
        }

        List<GranularBody.Entry> sharedOnly = new ArrayList<>();
        List<GranularBody.Entry> withDetail = new ArrayList<>();
        for (GranularBody.Entry e : group) {
            (remaining(e, shared).isEmpty() ? sharedOnly : withDetail).add(e);
        }

        if (!sharedOnly.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (GranularBody.Entry e : sharedOnly) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append('`').append(display(e.element(), qualified)).append('`');
            }
            out.append("- **Applies to**: ").append(names).append('\n');
        }

        for (GranularBody.Entry e : withDetail) {
            out.append('\n')
               .append(qualified ? "### " + ElementNaming.elementPath(e.element()) : collapsedHeading(e))
               .append('\n')
               .append(String.join("\n", remaining(e, shared)))
               .append('\n');
        }
    }

    /** Appends one {@code heading + lines} block, separated from the previous block by a blank line. */
    private static void block(StringBuilder out, String heading, List<String> lines) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(heading).append('\n');
        out.append(String.join("\n", lines)).append('\n');
    }

    /** The lines of {@code e} that the section heading did not already state. */
    private static List<String> remaining(GranularBody.Entry e, Shared shared) {
        return e.lines().subList(shared.prefix(), e.lines().size() - shared.suffix());
    }

    /**
     * The longest run of leading and trailing lines shared by every stanza in {@code group}. The
     * two runs are bounded so they can never overlap within the shortest stanza.
     */
    private static Shared shared(List<GranularBody.Entry> group) {
        List<List<String>> all = new ArrayList<>(group.size());
        int min = Integer.MAX_VALUE;
        for (GranularBody.Entry e : group) {
            all.add(e.lines());
            min = Math.min(min, e.lines().size());
        }
        int prefix = 0;
        while (prefix < min && sameAt(all, prefix, true)) {
            prefix++;
        }
        int suffix = 0;
        while (prefix + suffix < min && sameAt(all, suffix, false)) {
            suffix++;
        }
        List<String> first = all.get(0);
        List<String> lines = new ArrayList<>(prefix + suffix);
        for (int i = 0; i < prefix; i++) {
            lines.add(first.get(i));
        }
        for (int i = suffix; i > 0; i--) {
            lines.add(first.get(first.size() - i));
        }
        return new Shared(prefix, suffix, lines);
    }

    /** True when every stanza has the same line at offset {@code i} from the start (or the end). */
    private static boolean sameAt(List<List<String>> all, int i, boolean fromStart) {
        List<String> head = all.get(0);
        String ref = fromStart ? head.get(i) : head.get(head.size() - 1 - i);
        for (int k = 1; k < all.size(); k++) {
            List<String> lines = all.get(k);
            String v = fromStart ? lines.get(i) : lines.get(lines.size() - 1 - i);
            if (!ref.equals(v)) {
                return false;
            }
        }
        return true;
    }

    /** Heading for a stanza that sits under an already-emitted section heading. */
    private static String collapsedHeading(GranularBody.Entry e) {
        return e.ownerLevel()
            ? "### Rules for " + kindOf(e.element()) + " " + e.element().getSimpleName()
            : memberHeading(e.element());
    }

    /** Historical member heading, e.g. {@code ### Rules for field privateKey}. */
    private static String memberHeading(Element element) {
        ElementKind kind = element.getKind();
        CharSequence name = (kind == ElementKind.PARAMETER)
                ? ElementNaming.elementDisplayName(element)
                : element.getSimpleName();
        return "### Rules for " + kindOf(element) + " " + name;
    }

    private static String kindOf(Element element) {
        ElementKind kind = element.getKind();
        return kind != null ? kind.toString().toLowerCase(Locale.ROOT) : "element";
    }

    private static String display(Element element, boolean qualified) {
        return qualified ? ElementNaming.elementPath(element) : ElementNaming.elementDisplayName(element);
    }

    /**
     * Rewrites a hoisted sentence from singular to plural — it now heads a list of elements rather
     * than describing one. Applied only to hoisted lines, so per-element text is never touched.
     * Ordered longest-match-first; entries are literal substrings, not patterns, so the rewrite is
     * deterministic and confined to the sentences VibeTags itself emits.
     */
    public static String pluralize(String line) {
        String s = line;
        for (String[] rule : PLURAL) {
            s = s.replace(rule[0], rule[1]);
        }
        return s;
    }

    private static final String[][] PLURAL = {
        {"Calling it multiple times must produce the same result as calling it once.",
         "Calling them multiple times must produce the same result as calling them once."},
        {"This type is immutable.", "These types are immutable."},
        {"This operation is idempotent.", "These operations are idempotent."},
        {"This element is strictly excluded", "These elements are strictly excluded"},
        {"Do not reference it.", "Do not reference them."},
        {"this raw variable", "these raw variables"},
        {"changing this file", "changing these files"},
        {"this element", "these elements"},
        {"This element", "These elements"},
    };

    /** The shared leading/trailing run of a section, and the lines it covers. */
    private record Shared(int prefix, int suffix, List<String> lines) {
        static final Shared NONE = new Shared(0, 0, List.of());
    }
}
