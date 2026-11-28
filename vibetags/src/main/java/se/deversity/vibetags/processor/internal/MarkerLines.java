package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

/**
 * Where a VibeTags marker is in a file's text, by the rules the writer reads it with: a marker
 * counts only on a line of its own, a block start counts only outside a CommonMark code fence,
 * and a marker that turns up inside a generated body is defused before it is written.
 *
 * <p>Every reader of a generated block goes through these, so the writer, the TOML and JSON span
 * mergers, the duplicate-key check and {@code vibetags doctor} all find the same block. A second
 * search with slightly different rules is how a file gets a block one of them cannot see.
 */
public final class MarkerLines {

    private MarkerLines() {}

    /**
     * Defuses any VibeTags delimiter that appears as a standalone line inside the generated body.
     *
     * <p>The body is assembled from annotation attribute values, and the markdown platforms emit
     * those verbatim (there is no XML/JSON structure to escape there). A {@code reason} containing
     * a marker on its own line would therefore close the managed region early: on the next compile
     * the true remainder of the block is mistaken for hand-authored content, kept, and re-appended,
     * so the file grows a stale duplicate of itself on every build.
     *
     * <p>A zero-width space after the {@code <!--} / {@code #} keeps the text readable while making
     * the line no longer equal to a delimiter.
     */
    static String neutraliseMarkers(String body, String[] markers) {
        String out = body;
        for (String marker : markers) {
            if (indexOfMarkerLine(out, marker, 0) < 0) continue;
            String defused = marker.startsWith("<!--")
                ? "<!--​" + marker.substring(4)
                : "#​" + marker.substring(1);
            StringBuilder sb = new StringBuilder(out.length() + 8);
            int cursor = 0;
            while (cursor < out.length()) {
                int hit = indexOfMarkerLine(out, marker, cursor);
                if (hit < 0) {
                    sb.append(out, cursor, out.length());
                    break;
                }
                sb.append(out, cursor, hit).append(defused);
                cursor = hit + marker.length();
            }
            out = sb.toString();
        }
        return out;
    }

    /**
     * Finds a VibeTags marker only where it stands alone on its own line, the only form this
     * writer ever emits ({@code markerStart + "\n" + body + "\n" + markerEnd}).
     *
     * <p>A bare {@code indexOf} also matches a marker <em>mentioned</em> in hand-authored prose,
     * e.g. a CLAUDE.md documenting VibeTags itself:
     *
     * <pre>- **HTML comments**: `&lt;!-- VIBETAGS-START --&gt;` / `&lt;!-- VIBETAGS-END --&gt;`</pre>
     *
     * <p>Treating that as the managed region cuts the sentence in half and overwrites everything
     * after it: this repository's own CLAUDE.md was corrupted exactly that way. Requiring the
     * marker to own its line distinguishes a real delimiter from a citation of one.
     *
     * @param content   file content to search
     * @param marker    the marker literal
     * @param fromIndex index to start searching at
     * @return index of the first character of the marker, or {@code -1} if it appears nowhere as a
     *         standalone line at or after {@code fromIndex}
     */
    public static int indexOfMarkerLine(String content, String marker, int fromIndex) {
        if (content == null || content.isEmpty() || marker == null || marker.isEmpty()) return -1;
        for (int i = content.indexOf(marker, Math.max(0, fromIndex)); i >= 0;
             i = content.indexOf(marker, i + 1)) {
            if (ownsItsLine(content, i, marker.length())) return i;
        }
        return -1;
    }

    /**
     * {@link #indexOfMarkerLine} for the marker that opens a block, skipping any occurrence inside
     * a fenced code block. A file that documents VibeTags can show the marker pair as an example in
     * a {@code ```} or {@code ~~~} fence, where each marker owns its line: taken for the block, the
     * example's contents were replaced by the generated rules on every build, rendered as code no
     * agent reads as guardrails, and a real block below it was never refreshed.
     */
    public static int indexOfBlockStart(String content, String marker) {
        for (int i = indexOfMarkerLine(content, marker, 0); i >= 0; i = indexOfMarkerLine(content, marker, i + 1)) {
            if (!insideFence(content, i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether {@code content}'s marker pair is broken as this writer reads it: a block start with no
     * end line after it, or an end line with no block start anywhere. The first is what
     * {@code writeWithMarkers} repairs by discarding the text after the start, or refuses (#865);
     * the second is what a lost start leaves, the old block kept as hand text above a new one.
     * Uses the same line-owned, fence-aware search as the write path, so {@code vibetags doctor}
     * cannot pass a file the next build breaks, nor flag a marker quoted in prose (#919).
     */
    public static boolean hasBrokenMarkers(String content, String markerStart, String markerEnd) {
        int start = indexOfBlockStart(content, markerStart);
        if (start >= 0) {
            return indexOfMarkerLine(content, markerEnd, start + markerStart.length()) < 0;
        }
        return indexOfBlockStart(content, markerEnd) >= 0;
    }

    /** The shortest run of backticks or tildes that makes a code fence. */
    private static final int MIN_FENCE_LENGTH = 3;

    /**
     * Whether {@code index} lies inside a fenced code block, by CommonMark's rules for one. A fence
     * opens with a line starting with three or more backticks or tildes (an info string may
     * follow) and closes with a line of the same character, at least as many of them, and nothing
     * else. A backtick fence's info string cannot contain a backtick, so a line that begins with an
     * inline code span is not a fence.
     *
     * <p>Both halves of that matter here (#1161). Taking every line that starts with three
     * backticks for an opener, or closing a four-backtick fence at the first line of three, leaves
     * this believing a fence is open for the rest of the file: the real block below is then never
     * found, and every build appends another one beneath it.
     */
    static boolean insideFence(String content, int index) {
        String open = null;
        int start = 0;
        while (start < index) {
            int end = content.indexOf('\n', start);
            if (end < 0) {
                end = content.length();
            }
            open = fenceOpenAfter(open, content.substring(start, end).strip());
            start = end + 1;
        }
        return open != null;
    }

    /**
     * The fence that is open after {@code line}, as the run of backticks or tildes that opened it,
     * or {@code null} for none.
     *
     * @param open the fence open before this line, in the same form
     */
    private static @Nullable String fenceOpenAfter(@Nullable String open, String line) {
        int run = fenceRun(line);
        if (run < MIN_FENCE_LENGTH) {
            return open;
        }
        String rest = line.substring(run);
        if (open == null) {
            boolean opens = line.charAt(0) == '~' || rest.indexOf('`') < 0;
            return opens ? line.substring(0, run) : null;
        }
        boolean closes = line.charAt(0) == open.charAt(0) && run >= open.length() && rest.isBlank();
        return closes ? null : open;
    }

    /** How many backticks, or how many tildes, {@code line} opens with; 0 when it opens with neither. */
    private static int fenceRun(String line) {
        if (line.isEmpty() || (line.charAt(0) != '`' && line.charAt(0) != '~')) {
            return 0;
        }
        int run = 1;
        while (run < line.length() && line.charAt(run) == line.charAt(0)) {
            run++;
        }
        return run;
    }

    /** True when only whitespace separates the match from the start and end of its line. */
    private static boolean ownsItsLine(String content, int start, int length) {
        for (int i = start - 1; i >= 0 && content.charAt(i) != '\n'; i--) {
            if (!Character.isWhitespace(content.charAt(i))) return false;
        }
        for (int i = start + length; i < content.length() && content.charAt(i) != '\n'; i++) {
            if (!Character.isWhitespace(content.charAt(i))) return false;
        }
        return true;
    }
}
