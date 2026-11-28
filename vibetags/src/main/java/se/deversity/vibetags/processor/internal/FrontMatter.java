package se.deversity.vibetags.processor.internal;

/**
 * Where a YAML front-matter block that opens a file ends.
 *
 * <p>Several generated files must carry a YAML header above everything else, outside the
 * VibeTags markers: the {@code globs:} list of a granular rule file, the Claude skill's
 * {@code name} and {@code description}. The writer replaces such a header with the one it
 * rendered, and the reactor merge writes one shared header once, so both need to know exactly
 * where a header stops. Taking too much loses hand-written text; taking too little leaves the
 * rest of a header behind as if it were the body.
 */
public final class FrontMatter {

    private FrontMatter() {}

    /**
     * Index just past the closing {@code ---} of a YAML front-matter block that opens
     * {@code text}, or {@code -1} when the text does not open with one.
     *
     * <p>Both fences must own their line and start it, and every line between them must read as
     * YAML: a mapping entry, a list item, a comment, or an indented continuation. Splitting at the
     * first {@code ---} found anywhere after the opener did two things wrong: a hand-written file
     * that opens with a horizontal rule lost everything up to its next rule as if it were a
     * header, and a {@code ---} inside a value (a description, say) cut the header mid-value and
     * spilled the rest of it into the body.
     */
    public static int end(String text) {
        if (text == null || !text.startsWith("---")) return -1;
        int openerEnd = text.indexOf('\n');
        if (openerEnd < 0 || !text.substring(3, openerEnd).isBlank()) return -1;
        int close = closingFence(text, openerEnd);
        if (close < 0) return -1;
        for (String line : text.substring(openerEnd + 1, close).split("\n", -1)) {
            if (!isFrontMatterLine(line)) {
                return -1;
            }
        }
        return close + 3;
    }

    /**
     * The first {@code ---} after {@code from} that owns its line and starts it. YAML reads an
     * indented one as content, a line of a block scalar for instance, so it does not close the
     * header (#1241). Trailing whitespace is allowed, as on the opener.
     */
    private static int closingFence(String text, int from) {
        int close = MarkerLines.indexOfMarkerLine(text, "---", from);
        while (close >= 0 && text.charAt(close - 1) != '\n') {
            close = MarkerLines.indexOfMarkerLine(text, "---", close + 1);
        }
        return close;
    }

    private static boolean isFrontMatterLine(String line) {
        if (line.isBlank() || Character.isWhitespace(line.charAt(0))) {
            return true;
        }
        String t = line.strip();
        if (t.startsWith("#") || t.equals("-") || t.startsWith("- ")) {
            return true;
        }
        int colon = t.indexOf(':');
        return colon > 0 && t.substring(0, colon).strip().matches(KEY_PATTERN);
    }

    /** A YAML mapping key as it appears in front matter: a bare or quoted identifier. */
    private static final String KEY_PATTERN = "[\"']?[A-Za-z_][A-Za-z0-9_.-]*[\"']?";
}
