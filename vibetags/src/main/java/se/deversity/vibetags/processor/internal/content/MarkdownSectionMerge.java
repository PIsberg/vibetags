package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Joins the Markdown bodies of several source sets into one document (issue #841), the prose
 * counterpart of {@link ClaudeSectionMerge}.
 *
 * <p>Stacked, a test round's body repeated the generated header and every section heading and
 * intro under the main round's. On this repository that was about 5 KB of {@code AGENTS.md} and
 * 4 KB of {@code GEMINI.md}, files their tools load on every session.
 *
 * <p>A body is a lead (the {@code #} header lines and anything before the first {@code ##} to
 * {@code ######} heading) and sections keyed by their heading line. A section holds paragraphs
 * and bullets, a bullet being its {@code - }/{@code * } line plus every indented line under it,
 * compared as one block: the transitive appendix nests rules under a package bullet, and two
 * source sets naming one package with different rules must keep both. Each section appears once,
 * with every source set's bullets in source-set order. A section only a later body has goes before
 * the next section both share, so the renderer's order holds.
 *
 * <p>Text after the last bullet of a body's last section, which is where the {@code TESTING.md}
 * pointer is appended, is written once at the very end. So is a trailing {@code # } comment line,
 * the pointer's form in a hash-marker file.
 *
 * <p>Declines, so the caller concatenates as before, when the leads differ, when one section
 * carries two different intros or closing paragraphs (a sidecar written by another processor
 * version), or on a line no renderer writes. Every declined case keeps every guardrail.
 */
public final class MarkdownSectionMerge {

    private static final Pattern SECTION_HEADING = Pattern.compile("#{2,6} \\S.*");
    private static final Pattern ANY_HEADING = Pattern.compile("#{1,6} \\S.*");
    private static final Pattern BULLET = Pattern.compile("(?:[-*+]|\\d+\\.) .*");

    private MarkdownSectionMerge() {
    }

    /**
     * {@link SourceSetMerge} for the Markdown renderers.
     *
     * @param bodies the rendered bodies, main source set first
     * @return one document, or {@code null} to decline
     */
    public static @Nullable String merge(List<String> bodies) {
        if (bodies.size() < 2) {
            return bodies.isEmpty() ? null : bodies.get(0);
        }
        List<Container> merged = null;
        List<Item> tail = new ArrayList<>();
        for (String body : bodies) {
            List<Container> parsed = parse(body);
            if (parsed == null) {
                return null;
            }
            List<Item> bodyTail = takeTail(parsed, merged);
            if (merged == null) {
                merged = parsed;
            } else if (!fold(merged, parsed)) {
                return null;
            }
            for (Item item : bodyTail) {
                if (indexOfText(tail, item.text()) < 0) {
                    tail.add(item);
                }
            }
        }
        return merged == null ? null : render(merged, tail);
    }

    private static @Nullable List<Container> parse(String body) {
        List<Container> containers = new ArrayList<>();
        Container current = new Container(null);
        containers.add(current);
        boolean inSections = false;
        Item open = null;
        int blanks = 0;
        for (String line : body.split("\n", -1)) {
            if (line.isBlank()) {
                blanks++;
                open = null;
                continue;
            }
            if ((inSections ? ANY_HEADING : SECTION_HEADING).matcher(line).matches()) {
                current.trailingBlanks = blanks;
                current = new Container(line);
                containers.add(current);
                inSections = true;
                open = null;
                blanks = 0;
                continue;
            }
            boolean indented = Character.isWhitespace(line.charAt(0));
            if (open != null && open.bullet && indented) {
                open.append(line);
                continue;
            }
            if (BULLET.matcher(line).matches()) {
                open = new Item(true, line, blanks);
                current.items.add(open);
                blanks = 0;
                continue;
            }
            if (open != null && open.bullet) {
                return null;
            }
            if (open != null) {
                open.append(line);
                continue;
            }
            open = new Item(false, line, blanks);
            current.items.add(open);
            blanks = 0;
        }
        current.trailingBlanks = blanks;
        return containers;
    }

    /**
     * Removes and returns what follows a body's last entry: the paragraphs after the last bullet of
     * its last section, or a closing {@code # } comment line. When the last section has no
     * bullets, its paragraphs the merged document does not already hold there are the tail, so a
     * pointer after an empty section is not taken for that section's intro.
     */
    private static List<Item> takeTail(List<Container> parsed, @Nullable List<Container> merged) {
        List<Item> tail = new ArrayList<>();
        Container last = parsed.get(parsed.size() - 1);
        if (parsed.size() > 1 && last.heading != null && last.heading.startsWith("# ") && last.items.isEmpty()) {
            Container previous = parsed.get(parsed.size() - 2);
            tail.add(new Item(false, last.heading, Math.max(1, previous.trailingBlanks)));
            parsed.remove(parsed.size() - 1);
            previous.trailingBlanks = 0;
            return tail;
        }
        int lastBullet = lastBullet(last.items);
        if (lastBullet >= 0) {
            while (last.items.size() > lastBullet + 1) {
                tail.add(last.items.remove(lastBullet + 1));
            }
            return tail;
        }
        Container same = merged == null ? null : find(merged, last.heading);
        if (same == null) {
            return tail;
        }
        for (Item item : last.items) {
            if (indexOfText(same.items, item.text()) < 0) {
                tail.add(item);
            }
        }
        last.items.removeAll(tail);
        return tail;
    }

    /** Folds {@code incoming} into {@code merged}; {@code false} to decline. */
    private static boolean fold(List<Container> merged, List<Container> incoming) {
        Container lead = merged.get(0);
        Container incomingLead = incoming.get(0);
        if (!incomingLead.items.isEmpty() && !texts(lead.items).equals(texts(incomingLead.items))) {
            return false;
        }
        int cursor = 0;
        boolean anyShared = false;
        for (int k = 1; k < incoming.size(); k++) {
            Container section = incoming.get(k);
            int at = indexOf(merged, section.heading);
            if (at < 0) {
                int before = nextShared(merged, incoming, k);
                int position = before >= 0 ? before : anyShared ? cursor + 1 : merged.size();
                merged.add(position, section);
                cursor = position;
                continue;
            }
            if (!foldSection(merged.get(at), section)) {
                return false;
            }
            cursor = at;
            anyShared = true;
        }
        return true;
    }

    /**
     * A section already present gains the bullets it lacks, after its own last bullet. Its intro
     * and closing paragraphs must agree with the incoming ones where both have any.
     */
    private static boolean foldSection(Container existing, Container incoming) {
        if (hasParagraphBetweenBullets(existing.items) || hasParagraphBetweenBullets(incoming.items)) {
            return false;
        }
        int firstBullet = firstBullet(incoming.items);
        int lastBullet = lastBullet(incoming.items);
        List<Item> intro = firstBullet < 0 ? incoming.items : incoming.items.subList(0, firstBullet);
        for (Item paragraph : intro) {
            if (indexOfText(existing.items, paragraph.text()) >= 0) {
                continue;
            }
            int existingFirst = firstBullet(existing.items);
            if ((existingFirst < 0 ? existing.items.size() : existingFirst) > 0) {
                return false;
            }
            existing.items.add(0, paragraph);
        }
        if (firstBullet < 0) {
            return true;
        }
        int insertAt = lastBullet(existing.items) + 1;
        if (insertAt == 0) {
            insertAt = existing.items.size();
        }
        boolean existingHasBullets = firstBullet(existing.items) >= 0;
        List<Item> bullets = incoming.items.subList(firstBullet, lastBullet + 1);
        boolean first = true;
        for (Item bullet : bullets) {
            if (indexOfText(existing.items, bullet.text()) >= 0) {
                continue;
            }
            int blanks = first && existingHasBullets ? bulletSpacing(existing.items, bullets) : bullet.blanksBefore;
            existing.items.add(insertAt++, new Item(true, bullet.text(), blanks));
            first = false;
        }
        for (Item paragraph : incoming.items.subList(lastBullet + 1, incoming.items.size())) {
            if (indexOfText(existing.items, paragraph.text()) >= 0) {
                continue;
            }
            if (lastBullet(existing.items) < existing.items.size() - 1) {
                return false;
            }
            existing.items.add(paragraph);
        }
        return true;
    }

    /** The blank lines between two bullets of one list, from whichever side has two; else none. */
    private static int bulletSpacing(List<Item> existing, List<Item> incomingBullets) {
        for (List<Item> items : List.of(existing, incomingBullets)) {
            int seen = 0;
            for (Item item : items) {
                if (item.bullet && ++seen == 2) {
                    return item.blanksBefore;
                }
            }
        }
        return 0;
    }

    private static boolean hasParagraphBetweenBullets(List<Item> items) {
        int first = firstBullet(items);
        int last = lastBullet(items);
        for (int i = first + 1; first >= 0 && i < last; i++) {
            if (!items.get(i).bullet) {
                return true;
            }
        }
        return false;
    }

    /** Where in {@code merged} the first section after {@code incoming[k]} that both have sits, or -1. */
    private static int nextShared(List<Container> merged, List<Container> incoming, int k) {
        for (int j = k + 1; j < incoming.size(); j++) {
            int at = indexOf(merged, incoming.get(j).heading);
            if (at >= 0) {
                return at;
            }
        }
        return -1;
    }

    private static String render(List<Container> containers, List<Item> tail) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get(i);
            if (c.heading != null) {
                sb.append(c.heading).append('\n');
            }
            for (Item item : c.items) {
                sb.append("\n".repeat(item.blanksBefore)).append(item.text()).append('\n');
            }
            boolean emptyLead = c.heading == null && c.items.isEmpty();
            if (i < containers.size() - 1 && !emptyLead) {
                sb.append("\n".repeat(Math.max(1, c.trailingBlanks)));
            }
        }
        for (Item item : tail) {
            sb.append("\n".repeat(Math.max(1, item.blanksBefore))).append(item.text()).append('\n');
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '\n') {
            end--;
        }
        return sb.substring(0, end);
    }

    private static @Nullable Container find(List<Container> containers, @Nullable String heading) {
        int at = heading == null ? 0 : indexOf(containers, heading);
        return at < 0 ? null : containers.get(at);
    }

    private static int indexOf(List<Container> containers, @Nullable String heading) {
        for (int i = 1; i < containers.size(); i++) {
            String candidate = containers.get(i).heading;
            if (candidate != null && candidate.equals(heading)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfText(List<Item> items, String text) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).text().equals(text)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> texts(List<Item> items) {
        List<String> texts = new ArrayList<>();
        for (Item item : items) {
            texts.add(item.text());
        }
        return texts;
    }

    private static int firstBullet(List<Item> items) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).bullet) {
                return i;
            }
        }
        return -1;
    }

    private static int lastBullet(List<Item> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).bullet) {
                return i;
            }
        }
        return -1;
    }

    /** The lead ({@code heading == null}) or one section, with the blank lines that follow it. */
    private static final class Container {
        final @Nullable String heading;
        final List<Item> items = new ArrayList<>();
        int trailingBlanks;

        Container(@Nullable String heading) {
            this.heading = heading;
        }
    }

    /** A paragraph or a bullet with its nested lines, and the blank lines before it. */
    private static final class Item {
        final boolean bullet;
        private final StringBuilder lines;
        final int blanksBefore;

        Item(boolean bullet, String text, int blanksBefore) {
            this.bullet = bullet;
            this.lines = new StringBuilder(text);
            this.blanksBefore = blanksBefore;
        }

        void append(String line) {
            lines.append('\n').append(line);
        }

        String text() {
            return lines.toString();
        }
    }
}
