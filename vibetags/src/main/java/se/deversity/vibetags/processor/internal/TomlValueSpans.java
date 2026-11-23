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
 * marker with no end, the table reached through a dotted key or an array of tables, which the
 * table scan cannot see, or a table that would have to be added to a file that ends inside a
 * string or an array nobody closed.
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

    /** The key, bare or quoted: {@code "extra_instructions"} and {@code 'extra_instructions'} are the same key. */
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*([\"']?)extra_instructions\\1\\s*=");

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
        try {
            for (String table : TABLES) {
                doc = new TableMerge(doc, table, span, nl).merged();
            }
        } catch (Refused refused) {
            return Outcome.refused(refused.reason, refused.detail);
        }
        return new Outcome(doc, null, null);
    }

    /**
     * A document that cannot be merged without guessing: the reason for the log and the detail for
     * the build warning. It carries a verdict, not a failure, so it records no stack trace.
     */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String reason;
        private final String detail;

        Refused(String reason, String detail) {
            super(reason + ": " + detail, null, false, false);
            this.reason = reason;
            this.detail = detail;
        }
    }

    /**
     * One table's share of a merge: find the table, find its {@code extra_instructions}, and put the
     * span where each of those turns out to be, or was not.
     */
    private static final class TableMerge {

        private final String doc;
        private final String table;
        private final String span;
        private final String nl;
        private final List<Line> lines;
        private final boolean endsInsideAValue;
        /** An {@code extra_instructions} key holding the span and nothing else, on lines of its own. */
        private final String newKey;

        TableMerge(String doc, String table, String span, String nl) {
            this.doc = doc;
            this.table = table;
            this.span = span;
            this.nl = nl;
            LineScan scan = new LineScan(doc);
            this.lines = scan.lines();
            this.endsInsideAValue = scan.endsInsideAValue();
            this.newKey = OPEN + nl + span + nl + QUOTES + nl;
        }

        /** The document with this table's span in place. */
        String merged() {
            Line header = header();
            if (header == null) {
                return withTableAppended();
            }
            Line key = keyUnder(header);
            return key == null ? withKeyAdded(header) : withSpanIn(key);
        }

        /** The line that opens the table, or {@code null} when the document has no such table. */
        private @Nullable Line header() {
            List<Line> headers = new ArrayList<>();
            for (Line line : lines) {
                if (!line.normal()) {
                    continue;
                }
                // A quoted name is the same name, ["pr_reviewer"] the table [pr_reviewer] (TOML 1.0, Keys).
                // A byte order mark in front of the first header is encoding, not part of the name.
                String compact = textOf(line).strip().replaceAll("[\\s\"'\\uFEFF]", "");
                if (compact.startsWith("[[" + table + "]]")) {
                    throw new Refused("array-of-tables", "[[" + table + "]] is an array of tables");
                }
                if (compact.equals("[" + table + "]") || compact.startsWith("[" + table + "]#")) {
                    headers.add(line);
                } else if (compact.startsWith(table + ".") || compact.startsWith(table + "=")) {
                    throw new Refused("dotted-key", table + " is set with a dotted key or an inline table");
                }
            }
            if (headers.size() > 1) {
                throw new Refused("duplicate-table", "[" + table + "] is defined twice");
            }
            return headers.isEmpty() ? null : headers.get(0);
        }

        private String withTableAppended() {
            // An open string or array swallowed every line after it, this table's header perhaps
            // among them, and whatever is appended lands inside it too: the next merge would not
            // find the table either and would append it again (#1171).
            if (endsInsideAValue) {
                throw new Refused("unclosed-value", "the file ends inside a string or an array that is never closed");
            }
            String head = doc.isEmpty() || doc.endsWith("\n") ? doc : doc + nl;
            return head + (head.isBlank() ? "" : nl) + "[" + table + "]" + nl + newKey;
        }

        /** The table's {@code extra_instructions} key line, or {@code null} when it sets none. */
        private @Nullable Line keyUnder(Line header) {
            int tableEnd = endOfTable(header);
            List<Line> keys = new ArrayList<>();
            for (Line line : lines) {
                if (line.normal() && line.start() > header.start() && line.start() < tableEnd
                        && KEY_LINE.matcher(textOf(line)).find()) {
                    keys.add(line);
                }
            }
            if (keys.size() > 1) {
                throw new Refused("duplicate-key", "[" + table + "] sets extra_instructions twice");
            }
            return keys.isEmpty() ? null : keys.get(0);
        }

        /** Where the table stops: at the next header line below its own, or at the end of the document. */
        private int endOfTable(Line header) {
            for (Line line : lines) {
                if (line.start() > header.start() && line.normal() && textOf(line).strip().startsWith("[")) {
                    return line.start();
                }
            }
            return doc.length();
        }

        private String withKeyAdded(Line header) {
            String before = doc.substring(0, header.next());
            return (before.endsWith("\n") ? before : before + nl) + newKey + doc.substring(header.next());
        }

        private String withSpanIn(Line key) {
            int value = doc.indexOf('=', key.start()) + 1;
            while (value < doc.length() && (doc.charAt(value) == ' ' || doc.charAt(value) == '\t')) {
                value++;
            }
            if (!doc.startsWith(QUOTES, value)) {
                throw new Refused("not-multiline-string",
                    "extra_instructions in [" + table + "] is not a \"\"\" string VibeTags can add to");
            }
            int contentStart = value + QUOTES.length();
            int close = closingQuotes(doc, contentStart);
            if (close < 0) {
                throw new Refused("unclosed-string", "extra_instructions in [" + table + "] has no closing \"\"\"");
            }
            return doc.substring(0, contentStart) + withSpan(doc.substring(contentStart, close)) + doc.substring(close);
        }

        /**
         * The string's content with the span in it: in place of the span already there, or after
         * whatever the developer wrote, or alone when they wrote nothing.
         */
        private String withSpan(String content) {
            int start = GuardrailFileWriter.indexOfMarkerLine(content, GuardrailFileWriter.MARKER_START_MD, 0);
            if (start < 0) {
                return content.isBlank() ? nl + span + nl : content.stripTrailing() + nl + nl + span + nl;
            }
            int end = GuardrailFileWriter.indexOfMarkerLine(content, GuardrailFileWriter.MARKER_END_MD,
                start + GuardrailFileWriter.MARKER_START_MD.length());
            if (end < 0) {
                throw new Refused("unclosed-span",
                    "extra_instructions in [" + table + "] has a VIBETAGS-START line with no VIBETAGS-END after it");
            }
            return content.substring(0, start) + span
                + content.substring(end + GuardrailFileWriter.MARKER_END_MD.length());
        }

        private String textOf(Line line) {
            return doc.substring(line.start(), line.end());
        }
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

    /** One line of the document, and whether it starts outside any string, comment or array value. */
    private record Line(int start, int end, int next, boolean normal) {
    }

    private enum State { NORMAL, COMMENT, BASIC, LITERAL, ML_BASIC, ML_LITERAL }

    /**
     * The document's lines, each marked by whether it starts in plain TOML. A {@code [} line inside a
     * multi-line string is text, not a table, and one inside a multi-line array opens a nested array;
     * this is what tells both from a table.
     */
    private static final class LineScan {

        private final String doc;
        private final List<Line> lines = new ArrayList<>();
        private State state = State.NORMAL;
        private int arrayDepth;
        private int lineStart;
        private boolean lineNormal = true;
        private int i;

        LineScan(String doc) {
            this.doc = doc;
        }

        List<Line> lines() {
            while (i <= doc.length()) {
                if (i == doc.length() || doc.charAt(i) == '\n') {
                    endLine();
                } else {
                    character(doc.charAt(i));
                }
                i++;
            }
            return lines;
        }

        /** Whether the document stops with a multi-line string or an array still open. Asked after {@link #lines}. */
        boolean endsInsideAValue() {
            return state != State.NORMAL || arrayDepth > 0;
        }

        private void endLine() {
            int end = i > lineStart && doc.charAt(i - 1) == '\r' ? i - 1 : i;
            if (i > lineStart || i < doc.length()) {
                lines.add(new Line(lineStart, end, Math.min(i + 1, doc.length()), lineNormal));
            }
            // A comment and a single-line string end with their line, closed or not.
            if (state == State.COMMENT || state == State.BASIC || state == State.LITERAL) {
                state = State.NORMAL;
            }
            lineStart = i + 1;
            lineNormal = state == State.NORMAL && arrayDepth == 0;
        }

        private void character(char c) {
            switch (state) {
                case NORMAL -> plainToml(c);
                case BASIC -> basicString(c, "\"");
                case ML_BASIC -> basicString(c, QUOTES);
                case LITERAL -> literalString("'");
                case ML_LITERAL -> literalString("'''");
                case COMMENT -> {
                    // everything to the end of the line
                }
            }
        }

        private void plainToml(char c) {
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
            } else if (c == '[') {
                arrayDepth++;
            } else if (c == ']' && arrayDepth > 0) {
                arrayDepth--;
            }
        }

        /** Inside a basic string, where a backslash takes the next character with it. */
        private void basicString(char c, String delimiter) {
            if (c == '\\') {
                i++;
            } else if (doc.startsWith(delimiter, i)) {
                closeString(delimiter);
            }
        }

        /** Inside a literal string, which has no escapes. */
        private void literalString(String delimiter) {
            if (doc.startsWith(delimiter, i)) {
                closeString(delimiter);
            }
        }

        private void closeString(String delimiter) {
            state = State.NORMAL;
            i += delimiter.length() - 1;
        }
    }
}
