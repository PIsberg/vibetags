package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins the {@code CLAUDE.md} bodies of several source sets into one {@code <project_guardrails>}
 * document (issue #839).
 *
 * <p>Stacked, a test round's body repeated the generated header, the wrapper, the rule sentence
 * under every section and the closing rule. On this repository that was 955 of the 1,755 bytes the
 * test round added to a file loaded on every session. Here each section appears once, holding every
 * source set's entries in source-set order, with its rule sentence once under it.
 *
 * <p>Entries are compared as whole blocks, an opening line at four spaces plus everything nested
 * under it, never line by line: two locked elements with the same reason share a
 * {@code <reason>} line, and dropping the second copy of that line would corrupt the entry.
 *
 * <p>Declines, so the caller concatenates as before, when a body is not the shape
 * {@code ClaudeRenderer} writes: no wrapper, a line outside a section, an unclosed section, a
 * different header, or the same section carrying a different rule sentence (a sidecar written by
 * another processor version). Every declined case keeps every guardrail.
 */
public final class ClaudeSectionMerge {

    private static final String OPEN = "<project_guardrails>";
    private static final String CLOSE = "</project_guardrails>";
    private static final Pattern SECTION_OPEN = Pattern.compile(" {2}<([a-z_]+)>");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\n[ \t]*\n");
    private static final String ENTRY_INDENT = "    ";

    private ClaudeSectionMerge() {
    }

    /**
     * {@link SourceSetMerge} for the Claude renderers.
     *
     * @param bodies the rendered bodies, main source set first
     * @return one {@code <project_guardrails>} document, or {@code null} to decline
     */
    public static @Nullable String merge(List<String> bodies) {
        if (bodies.size() < 2) {
            return bodies.isEmpty() ? null : bodies.get(0);
        }
        String lead = null;
        List<Section> merged = new ArrayList<>();
        List<String> tail = new ArrayList<>();
        for (String body : bodies) {
            Parsed parsed = parse(body);
            if (parsed == null) {
                return null;
            }
            if (lead == null) {
                lead = parsed.lead();
            } else if (!lead.equals(parsed.lead())) {
                return null;
            }
            if (!mergeSections(merged, parsed.sections())) {
                return null;
            }
            for (String paragraph : parsed.tail()) {
                if (!tail.contains(paragraph)) {
                    tail.add(paragraph);
                }
            }
        }
        return render(lead == null ? "" : lead, merged, tail);
    }

    /**
     * Folds {@code incoming} into {@code merged}. A section already present gains the entries it
     * lacks. A new one goes before the next section of its own body that {@code merged} already
     * has, or after the last one it shared when none follows, so the renderer's order holds
     * wherever the two bodies say anything about it.
     */
    private static boolean mergeSections(List<Section> merged, List<Section> incoming) {
        int cursor = -1;
        for (int k = 0; k < incoming.size(); k++) {
            Section section = incoming.get(k);
            int at = indexOf(merged, section.tag());
            if (at < 0) {
                int before = nextShared(merged, incoming, k);
                cursor = before >= 0 ? before : cursor + 1;
                merged.add(cursor, section);
                continue;
            }
            Section existing = merged.get(at);
            if (!String.join("\n", existing.after()).strip().equals(String.join("\n", section.after()).strip())) {
                return false;
            }
            for (String block : section.blocks()) {
                if (!existing.blocks().contains(block)) {
                    existing.blocks().add(block);
                }
            }
            cursor = at;
        }
        return true;
    }

    /** Where in {@code merged} the first section after {@code incoming[k]} that both have sits, or -1. */
    private static int nextShared(List<Section> merged, List<Section> incoming, int k) {
        for (int j = k + 1; j < incoming.size(); j++) {
            int at = indexOf(merged, incoming.get(j).tag());
            if (at >= 0) {
                return at;
            }
        }
        return -1;
    }

    private static int indexOf(List<Section> sections, String tag) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).tag().equals(tag)) {
                return i;
            }
        }
        return -1;
    }

    private static @Nullable Parsed parse(String body) {
        List<String> lines = Arrays.asList(body.split("\n", -1));
        int open = lines.indexOf(OPEN);
        int close = lines.lastIndexOf(CLOSE);
        if (open < 0 || close < open) {
            return null;
        }
        List<Section> sections = new ArrayList<>();
        int i = open + 1;
        while (i < close) {
            Matcher m = SECTION_OPEN.matcher(lines.get(i));
            if (!m.matches()) {
                if (!lines.get(i).isBlank()) {
                    return null;
                }
                i++;
                continue;
            }
            String tag = m.group(1);
            int end = lines.subList(i + 1, close).indexOf("  </" + tag + ">");
            if (end < 0) {
                return null;
            }
            end += i + 1;
            List<String> blocks = blocks(lines.subList(i + 1, end));
            if (blocks == null) {
                return null;
            }
            int next = end + 1;
            while (next < close && !SECTION_OPEN.matcher(lines.get(next)).matches()) {
                next++;
            }
            sections.add(new Section(tag, blocks, List.copyOf(lines.subList(end + 1, next))));
            i = next;
        }
        String lead = String.join("\n", lines.subList(0, open)).strip();
        List<String> tail = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BREAK.split(String.join("\n", lines.subList(close + 1, lines.size())))) {
            if (!paragraph.isBlank()) {
                tail.add(paragraph.strip());
            }
        }
        return new Parsed(lead, sections, tail);
    }

    /**
     * A section's entry lines grouped into blocks, each an opening line at four spaces with the
     * deeper lines and the four-space closing tag that follow it. {@code null} when a line sits
     * shallower than an entry, which is not a shape this merge knows.
     */
    private static @Nullable List<String> blocks(List<String> lines) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = null;
        for (String line : lines) {
            if (!line.isBlank() && !line.startsWith(ENTRY_INDENT)) {
                return null;
            }
            boolean opensEntry = line.length() > ENTRY_INDENT.length()
                && line.startsWith(ENTRY_INDENT)
                && !Character.isWhitespace(line.charAt(ENTRY_INDENT.length()))
                && !line.startsWith(ENTRY_INDENT + "</");
            if (opensEntry || current == null) {
                if (current != null) {
                    blocks.add(current.toString());
                }
                current = new StringBuilder(line);
            } else {
                current.append('\n').append(line);
            }
        }
        if (current != null) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private static String render(String lead, List<Section> sections, List<String> tail) {
        StringBuilder sb = new StringBuilder();
        if (!lead.isEmpty()) {
            sb.append(lead).append('\n');
        }
        sb.append(OPEN).append('\n');
        for (Section section : sections) {
            sb.append("  <").append(section.tag()).append(">\n");
            for (String block : section.blocks()) {
                sb.append(block).append('\n');
            }
            sb.append("  </").append(section.tag()).append(">\n");
            for (String line : section.after()) {
                sb.append(line).append('\n');
            }
        }
        sb.append(CLOSE);
        for (String paragraph : tail) {
            sb.append("\n\n").append(paragraph);
        }
        return sb.toString();
    }

    /** One {@code <tag>} section, its entry blocks, and the lines up to the next section. */
    private record Section(String tag, List<String> blocks, List<String> after) {
    }

    private record Parsed(String lead, List<Section> sections, List<String> tail) {
    }
}
