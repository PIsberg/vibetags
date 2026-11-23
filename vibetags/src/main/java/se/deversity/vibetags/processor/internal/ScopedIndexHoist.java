package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scoped-rules index of a reactor root's Markdown aggregate, written once. Every module renders
 * its own section, so stacked module blocks repeated the heading and its preamble once per module
 * (#930); {@link #hoist} takes the sections out of the blocks and writes one after them.
 *
 * <p>Applies only when at least two contributions carry a section, every section has the shape
 * {@code GranularIndexSection} renders (the heading on a line of its own, one preamble line, then
 * {@code - } lines up to the next {@code ## } heading or the end), and the preambles are identical.
 * Anything else leaves every contribution as it was, which is the stacked output this replaced:
 * repetitive, but nothing lost and nothing guessed. {@code ScopedIndexHoistTest} pins each case.
 */
final class ScopedIndexHoist {

    /** The heading a Markdown aggregate's scoped-rules section opens with. */
    static final String HEADING = "## Scoped Rules Index";

    private ScopedIndexHoist() { }

    /**
     * The one section for every contribution's, its index lines in order with duplicates dropped,
     * each contribution replaced by what is left of it; or {@code ""} with every contribution as it
     * was.
     */
    static String hoist(List<Map.Entry<String, String>> contributions) {
        try {
            return hoistEvery(contributions);
        } catch (Unhoistable declined) {
            return "";
        }
    }

    private static String hoistEvery(List<Map.Entry<String, String>> contributions) {
        List<String> remainders = new ArrayList<>(contributions.size());
        Set<String> items = new LinkedHashSet<>();
        String preamble = null;
        int found = 0;
        for (Map.Entry<String, String> contribution : contributions) {
            Section section = Section.in(contribution.getValue());
            if (section == null) {
                remainders.add(contribution.getValue());
                continue;
            }
            if (preamble != null && !preamble.equals(section.preamble())) {
                throw new Unhoistable();
            }
            preamble = section.preamble();
            items.addAll(section.items());
            remainders.add(section.remainder());
            found++;
        }
        if (found < 2 || items.isEmpty()) {
            return "";
        }
        for (int i = 0; i < contributions.size(); i++) {
            contributions.get(i).setValue(remainders.get(i));
        }
        return HEADING + "\n" + preamble + "\n\n" + String.join("\n", items);
    }

    /**
     * One contribution's scoped-rules section.
     *
     * @param preamble  the line between the heading and the index lines
     * @param items     the index lines, stripped
     * @param remainder the contribution without the section, the gap it leaves closed to one blank line
     */
    private record Section(String preamble, List<String> items, String remainder) {

        /**
         * The section in {@code body}, or {@code null} when it has none.
         *
         * @throws Unhoistable when it has one of another shape
         */
        static @Nullable Section in(String body) {
            int start = headingLine(body);
            if (start < 0) {
                return null;
            }
            int next = body.indexOf("\n## ", start + HEADING.length());
            int end = next < 0 ? body.length() : next + 1;
            String preamble = null;
            List<String> items = new ArrayList<>();
            for (String line : body.substring(start + HEADING.length(), end).split("\n")) {
                String text = line.strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.startsWith("- ")) {
                    items.add(text);
                } else if (preamble == null && items.isEmpty()) {
                    preamble = text;
                } else {
                    throw new Unhoistable();
                }
            }
            if (preamble == null) {
                throw new Unhoistable();
            }
            String remainder = body.substring(0, start).stripTrailing() + "\n\n" + body.substring(end).stripLeading();
            return new Section(preamble, items, remainder.strip());
        }

        /** Where {@link #HEADING} stands on a line of its own in {@code body}, or {@code -1}. */
        private static int headingLine(String body) {
            for (int i = body.indexOf(HEADING); i >= 0; i = body.indexOf(HEADING, i + 1)) {
                boolean lineStart = i == 0 || body.charAt(i - 1) == '\n';
                int after = i + HEADING.length();
                boolean lineEnd = after == body.length() || body.charAt(after) == '\n' || body.charAt(after) == '\r';
                if (lineStart && lineEnd) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** A section whose shape the hoist does not recognise: every contribution stays as it was. */
    private static final class Unhoistable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Stackless: it is control flow inside this class, never reported. */
        Unhoistable() {
            super(null, null, false, false);
        }
    }
}
