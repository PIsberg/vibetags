package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AIContext;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Merges the guardrails into a {@code .pr_agent.toml} somebody configured by hand (#933).
 *
 * <p>TOML has nowhere for a marker line outside a string, so VibeTags used to write this file whole,
 * replacing whatever was there, and then left a hand-written one alone with a warning. A team that already
 * configures PR-Agent should not have to choose between its settings and its guardrails. So, as
 * {@link JsonValueSpans} does for {@code greptile.json} (#639), VibeTags owns a delimited span inside
 * the value it shares with the user and nothing else: the {@code extra_instructions} multi-line
 * string of {@code [pr_reviewer]} and of {@code [pr_code_suggestions]}. Text the user wrote in that
 * string stays first, with the span after it; every other byte of the document stays where it was.
 * A table or key the file lacks is added.
 *
 * <p>A document that cannot be merged without guessing is refused, and the caller writes nothing:
 * a table defined twice, the key defined twice, a value that is not a {@code """} string, a start
 * marker with no end, or the table reached through a dotted key or an array of tables, which the
 * table scan cannot see.
 *
 * <p>The span body is the renderer's {@code extra_instructions} text, already
 * {@code Escape.tomlMultiline}-encoded, so it cannot close the string; its own marker lines are
 * defused like a marker file's, so it cannot end the span early.
 */
@AIContext(
    focus = "The span body is annotation text, dependency JARs' included, spliced into a PR-Agent config the user owns: "
        + "keep it Escape.tomlMultiline-encoded and marker-defused",
    avoids = "Splicing raw text: a dependency could close the string and add review settings, or end the span early "
        + "so the value grows a copy of itself on every build")
public final class TomlValueSpans {

    private TomlValueSpans() {
    }

    /** The two tables PR-Agent reads {@code extra_instructions} from, in the order the renderer writes them. */
    private static final String[] TABLES = {"pr_reviewer", "pr_code_suggestions"};

    private static final Pattern KEY_LINE = Pattern.compile("^\\s*extra_instructions\\s*=");

    private static final String OPEN = "extra_instructions = \"\"\"";

    private static final String QUOTES = "\"\"\"";

    /**
     * The merged document, or {@code null} with the reason the document was refused.
     *
     * @param document   the merged file, or {@code null} when nothing may be written
     * @param skipReason the log reason, {@code null} when merged
     * @param detail     what was wrong, for the build warning; {@code null} when merged
     */
    public record Outcome(@Nullable String document, @Nullable String skipReason, @Nullable String detail) {
        static Outcome refused(String reason, String detail) {
            return new Outcome(null, reason, detail);
        }
    }

    /** The {@code extra_instructions} text of the renderer's document, or {@code null} if it has none. */
    public static @Nullable String bodyFrom(String rendered) {
        int open = rendered.indexOf(OPEN);
        if (open < 0) {
            return null;
        }
        int start = open + OPEN.length();
        int close = closingQuotes(rendered, start);
        if (close < 0) {
            return null;
        }
        String body = rendered.substring(start, close);
        return body.replaceFirst("^\\r?\\n", "").stripTrailing();
    }

    /** Puts {@code body} into the span of each table's {@code extra_instructions} in {@code existing}. */
    public static Outcome merge(String existing, String body) {
        String nl = existing.contains("\r\n") ? "\r\n" : "\n";
        String safeBody = GuardrailFileWriter.neutraliseMarkers(body.strip(),
            new String[]{GuardrailFileWriter.MARKER_START_MD, GuardrailFileWriter.MARKER_END_MD});
        String span = GuardrailFileWriter.MARKER_START_MD + nl + safeBody.replace("\n", nl) + nl
            + GuardrailFileWriter.MARKER_END_MD;
        String doc = existing;
        for (String table : TABLES) {
            Outcome step = mergeTable(doc, table, span, nl);
            String next = step.document();
            if (next == null) {
                return step;
            }
            doc = next;
        }
        return merged(doc);
    }

    private static Outcome merged(String document) {
        return new Outcome(document, null, null);
    }

    /** The document with {@code table}'s span in place, or the {@link Outcome} that refuses it. */
    private static Outcome mergeTable(String doc, String table, String span, String nl) {
        List<Line> lines = scan(doc);
        List<Line> headers = new ArrayList<>();
        for (Line line : lines) {
            if (!line.normal) {
                continue;
            }
            String text = doc.substring(line.start, line.end).strip();
            String compact = text.replaceAll("\\s", "");
            if (compact.startsWith("[[") && compact.startsWith("[[" + table + "]]")) {
                return Outcome.refused("array-of-tables", "[[" + table + "]] is an array of tables");
            }
            if (compact.equals("[" + table + "]") || compact.startsWith("[" + table + "]#")) {
                headers.add(line);
            } else if (compact.startsWith(table + ".") || compact.startsWith(table + "=")) {
                return Outcome.refused("dotted-key", table + " is set with a dotted key or an inline table");
            }
        }
        if (headers.size() > 1) {
            return Outcome.refused("duplicate-table", "[" + table + "] is defined twice");
        }
        String newKey = OPEN + nl + span + nl + QUOTES + nl;
        if (headers.isEmpty()) {
            String head = doc.isEmpty() || doc.endsWith("\n") ? doc : doc + nl;
            return merged(head + (head.isBlank() ? "" : nl) + "[" + table + "]" + nl + newKey);
        }
        Line header = headers.get(0);
        int rangeEnd = doc.length();
        for (Line line : lines) {
            if (line.start > header.start && line.normal && doc.substring(line.start, line.end).strip().startsWith("[")) {
                rangeEnd = line.start;
                break;
            }
        }
        List<Line> keys = new ArrayList<>();
        for (Line line : lines) {
            if (line.normal && line.start > header.start && line.start < rangeEnd
                    && KEY_LINE.matcher(doc.substring(line.start, line.end)).find()) {
                keys.add(line);
            }
        }
        if (keys.size() > 1) {
            return Outcome.refused("duplicate-key", "[" + table + "] sets extra_instructions twice");
        }
        if (keys.isEmpty()) {
            int insertAt = header.next;
            String before = doc.substring(0, insertAt);
            return merged((before.endsWith("\n") ? before : before + nl) + newKey + doc.substring(insertAt));
        }
        Line key = keys.get(0);
        int value = doc.indexOf('=', key.start) + 1;
        while (value < doc.length() && (doc.charAt(value) == ' ' || doc.charAt(value) == '\t')) {
            value++;
        }
        if (!doc.startsWith(QUOTES, value)) {
            return Outcome.refused("not-multiline-string",
                "extra_instructions in [" + table + "] is not a \"\"\" string VibeTags can add to");
        }
        int contentStart = value + QUOTES.length();
        int close = closingQuotes(doc, contentStart);
        if (close < 0) {
            return Outcome.refused("unclosed-string", "extra_instructions in [" + table + "] has no closing \"\"\"");
        }
        String content = doc.substring(contentStart, close);
        int start = GuardrailFileWriter.indexOfMarkerLine(content, GuardrailFileWriter.MARKER_START_MD, 0);
        String replaced;
        if (start >= 0) {
            int end = GuardrailFileWriter.indexOfMarkerLine(content, GuardrailFileWriter.MARKER_END_MD,
                start + GuardrailFileWriter.MARKER_START_MD.length());
            if (end < 0) {
                return Outcome.refused("unclosed-span",
                    "extra_instructions in [" + table + "] has a VIBETAGS-START line with no VIBETAGS-END after it");
            }
            replaced = content.substring(0, start) + span
                + content.substring(end + GuardrailFileWriter.MARKER_END_MD.length());
        } else if (content.isBlank()) {
            replaced = nl + span + nl;
        } else {
            replaced = content.stripTrailing() + nl + nl + span + nl;
        }
        return merged(doc.substring(0, contentStart) + replaced + doc.substring(close));
    }

    /**
     * Index of the {@code """} that closes a multi-line basic string whose content starts at
     * {@code from}, or {@code -1}. A backslash escapes the next character; up to two further quotes
     * directly before the delimiter belong to the content, as TOML has it.
     */
    private static int closingQuotes(String doc, int from) {
        int i = from;
        while (i < doc.length()) {
            char c = doc.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (doc.startsWith(QUOTES, i)) {
                int close = i;
                while (close - i < 2 && doc.startsWith(QUOTES, close + 1)) {
                    close++;
                }
                return close;
            }
            i++;
        }
        return -1;
    }

    /** One line of the document, and whether it starts outside any string or comment. */
    private record Line(int start, int end, int next, boolean normal) {
    }

    private enum State { NORMAL, COMMENT, BASIC, LITERAL, ML_BASIC, ML_LITERAL }

    /**
     * The document's lines, each marked by whether it starts in plain TOML. A {@code [} line inside a
     * multi-line string is text, not a table, and this is what tells the two apart.
     */
    private static List<Line> scan(String doc) {
        List<Line> lines = new ArrayList<>();
        State state = State.NORMAL;
        int lineStart = 0;
        boolean lineNormal = true;
        int i = 0;
        while (i <= doc.length()) {
            if (i == doc.length() || doc.charAt(i) == '\n') {
                int end = i > lineStart && doc.charAt(i - 1) == '\r' ? i - 1 : i;
                if (i > lineStart || i < doc.length()) {
                    lines.add(new Line(lineStart, end, Math.min(i + 1, doc.length()), lineNormal));
                }
                if (state == State.COMMENT || state == State.BASIC || state == State.LITERAL) {
                    state = State.NORMAL;
                }
                lineStart = i + 1;
                lineNormal = state == State.NORMAL;
                i++;
                continue;
            }
            char c = doc.charAt(i);
            switch (state) {
                case NORMAL -> {
                    if (c == '#') {
                        state = State.COMMENT;
                    } else if (doc.startsWith(QUOTES, i)) {
                        state = State.ML_BASIC;
                        i += 2;
                    } else if (doc.startsWith("'''", i)) {
                        state = State.ML_LITERAL;
                        i += 2;
                    } else if (c == '"') {
                        state = State.BASIC;
                    } else if (c == '\'') {
                        state = State.LITERAL;
                    }
                }
                case BASIC -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        state = State.NORMAL;
                    }
                }
                case LITERAL -> {
                    if (c == '\'') {
                        state = State.NORMAL;
                    }
                }
                case ML_BASIC -> {
                    if (c == '\\') {
                        i++;
                    } else if (doc.startsWith(QUOTES, i)) {
                        state = State.NORMAL;
                        i += 2;
                    }
                }
                case ML_LITERAL -> {
                    if (doc.startsWith("'''", i)) {
                        state = State.NORMAL;
                        i += 2;
                    }
                }
                default -> {
                    // COMMENT: everything to the end of the line
                }
            }
            i++;
        }
        return lines;
    }
}
