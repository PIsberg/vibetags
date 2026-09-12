package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.processor.internal.content.Escape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes VibeTags' guardrails into a JSON document the user owns, by splicing a delimited span into
 * named top-level string values and leaving every other byte of the document where it was.
 *
 * <p>This exists for {@code greptile.json} (#639). Every other output format either has a comment
 * syntax to hang {@code VIBETAGS-START} / {@code VIBETAGS-END} markers on, or is a file VibeTags owns
 * outright. {@code greptile.json} is neither: it has no comments, and in practice it carries thirty-odd
 * hand-set review settings, so the whole-file overwrite every other {@code .json} output gets would
 * erase them on the first compile after opt-in. The guardrails belong in two of its values,
 * {@code instructions} (prose) and {@code ignorePatterns} ({@code .gitignore} syntax), which the
 * user may already have written text in. So the markers go <em>inside</em> those strings, as lines of
 * the decoded value, and only the text between them is VibeTags'.
 *
 * <p>Four properties, each tested in {@code JsonValueSpansTest}:
 *
 * <ul>
 *   <li><b>Nothing outside the span changes, byte for byte.</b> The document is never re-serialised.
 *       Edits are computed as offsets into the original text and spliced in, so key order,
 *       whitespace, number spelling and the user's own escape sequences inside a shared value
 *       (unicode escapes, {@code \/}) all survive.</li>
 *   <li><b>Never guess.</b> A document that is not strict JSON, a shared key that holds something
 *       other than a string, a key that appears twice, or a start marker with no end marker is
 *       declined with a reason. The caller writes nothing and says so; a stale span is recoverable,
 *       a destroyed configuration is not.</li>
 *   <li><b>The span cannot be forged.</b> Its body is encoded with {@link Escape#json}, so annotation
 *       text cannot close the string or add a key, and any line in it equal to a marker is defused
 *       before it is written, so it cannot end the span early and leave the rest of the body to be
 *       mistaken for hand-written text on the next build.</li>
 *   <li><b>Idempotent.</b> Merging the result again with the same bodies returns it unchanged.</li>
 * </ul>
 */
@AISecure(aspect = "Splices annotation text, including attributes copied out of third-party dependency JARs, "
    + "into greptile.json, a review configuration the user owns. The span body must stay Escape.json-encoded "
    + "and marker-defused: without the first a dependency can close the string and add settings such as "
    + "skipReview, and without the second it can end the span early so the value grows a copy of itself on "
    + "every build.")
@AILoadBearing(
    invariant = "Edits are offsets spliced into the original text; the document is validated with Json but "
        + "never parsed and re-serialised.",
    breaksIf = "Re-serialising rewrites key order, whitespace, number spelling and the user's own escape "
        + "sequences in a file VibeTags does not own, on every build, with nothing failing except the "
        + "byte-preservation cases in JsonValueSpansTest and GreptileEndToEndTest.")
public final class JsonValueSpans {

    /**
     * One top-level key VibeTags shares with the user, and the delimiter lines that bound its span
     * inside the decoded value.
     */
    public record SharedKey(String key, String markerStart, String markerEnd) {}

    /** The shared keys of Greptile's root {@code greptile.json}. */
    public static final List<SharedKey> GREPTILE = List.of(
        new SharedKey("instructions",
            GuardrailFileWriter.MARKER_START_MD, GuardrailFileWriter.MARKER_END_MD),
        // ignorePatterns is .gitignore syntax, where a # line is a comment and matches no file.
        new SharedKey("ignorePatterns",
            GuardrailFileWriter.MARKER_START_HASH, GuardrailFileWriter.MARKER_END_HASH));

    /**
     * The result of a merge: either the merged document, or the reason nothing may be written.
     *
     * @param document   the merged document, or {@code null} when declined
     * @param skipReason the {@code reason=} token for the log when declined, else {@code null}
     * @param detail     a human-readable explanation of the decline, or {@code ""}
     */
    public record Outcome(@Nullable String document, @Nullable String skipReason, String detail) {

        static Outcome merged(String document) {
            return new Outcome(document, null, "");
        }

        static Outcome declined(String reason, String detail) {
            return new Outcome(null, reason, detail);
        }
    }

    /** U+FEFF. Not JSON, but a byte some editors put first; it belongs to the user and is kept. */
    private static final char BYTE_ORDER_MARK = 0xFEFF;

    private JsonValueSpans() {}

    /**
     * The shared keys for the output file called {@code fileName}, or {@code null} when that file is
     * not merged this way.
     */
    public static @Nullable List<SharedKey> sharedKeysFor(String fileName) {
        return "greptile.json".equals(fileName) ? GREPTILE : null;
    }

    /**
     * Reads the owned-values document a renderer produces, {@code {"key": ["line", ...], ...}}, into
     * key → the lines joined with {@code \n}.
     *
     * @return {@code null} when {@code rendered} is not that shape
     */
    public static @Nullable Map<String, String> bodiesFrom(String rendered) {
        Map<String, Object> object;
        try {
            object = Json.parseObject(rendered);
        } catch (Json.JsonException e) {
            return null;
        }
        Map<String, String> bodies = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (!(entry.getValue() instanceof List<?> lines)) {
                return null;
            }
            List<String> text = new ArrayList<>(lines.size());
            for (Object line : lines) {
                if (!(line instanceof String s)) {
                    return null;
                }
                text.add(s);
            }
            bodies.put(entry.getKey(), String.join("\n", text));
        }
        return bodies;
    }

    /**
     * Merges {@code bodies} into {@code existing}.
     *
     * @param existing the document on disk; blank means the opt-in file was just created
     * @param keys     the keys VibeTags shares with the user, in the order a new document lists them
     * @param bodies   shared key → the text VibeTags owns in it; absent or {@code ""} means none
     */
    public static Outcome merge(String existing, List<SharedKey> keys, Map<String, String> bodies) {
        if (existing.isBlank()) {
            return Outcome.merged(freshDocument(keys, bodies));
        }
        // A byte-order mark is not JSON, and Json.parse rightly refuses it; it is also the user's
        // byte, so it is set aside for the scan and put back in front of the result.
        String bom = existing.charAt(0) == BYTE_ORDER_MARK ? String.valueOf(BYTE_ORDER_MARK) : "";
        String text = existing.substring(bom.length());
        try {
            Json.parseObject(text);
        } catch (Json.JsonException e) {
            return Outcome.declined("malformed-json", "it is not a well-formed JSON object (" + e.getMessage() + ")");
        }

        List<Member> members = new Scanner(text).topLevelMembers();
        List<Edit> edits = new ArrayList<>();
        List<String> additions = new ArrayList<>();
        for (SharedKey shared : keys) {
            String body = bodies.getOrDefault(shared.key(), "");
            List<Member> matches = members.stream().filter(m -> m.key().equals(shared.key())).toList();
            if (matches.size() > 1) {
                return Outcome.declined("duplicate-key", "\"" + shared.key() + "\" appears " + matches.size()
                    + " times, and a JSON reader keeps only one of them");
            }
            if (matches.isEmpty()) {
                if (!body.isEmpty()) {
                    additions.add("\"" + Escape.json(shared.key()) + "\": \"" + Escape.json(span(shared, body)) + "\"");
                }
                continue;
            }
            Member member = matches.get(0);
            if (text.charAt(member.valueStart()) != '"') {
                return Outcome.declined("non-string-value", "\"" + shared.key() + "\" is not a string");
            }
            Edit edit = spliceSpan(text, member, shared, body);
            if (edit == null) {
                return Outcome.declined("unterminated-span", "\"" + shared.key() + "\" has a "
                    + shared.markerStart() + " line with no " + shared.markerEnd() + " line after it");
            }
            edits.add(edit);
        }
        if (!additions.isEmpty()) {
            edits.add(insertion(text, members, additions));
        }

        StringBuilder out = new StringBuilder(text);
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit edit : edits) {
            out.replace(edit.start(), edit.end(), edit.replacement());
        }
        String merged = bom + out;
        try {
            // Unreachable while the edits above are correct; the check turns a bug in them into a
            // skipped write rather than a corrupted file.
            Json.parseObject(out.toString());
        } catch (Json.JsonException e) {
            return Outcome.declined("merge-invalid", "the merged document did not parse (" + e.getMessage() + ")");
        }
        return Outcome.merged(merged);
    }

    // ---------------------------------------------------------------------------------------

    /** A top-level member: its decoded key, the offset of its opening quote, and its value's offsets. */
    private record Member(String key, int keyStart, int valueStart, int valueEnd) {}

    /** Replace {@code [start, end)} of the original text with {@code replacement}. */
    private record Edit(int start, int end, String replacement) {}

    private static String freshDocument(List<SharedKey> keys, Map<String, String> bodies) {
        List<String> entries = new ArrayList<>();
        for (SharedKey shared : keys) {
            String body = bodies.getOrDefault(shared.key(), "");
            if (!body.isEmpty()) {
                entries.add("  \"" + Escape.json(shared.key()) + "\": \"" + Escape.json(span(shared, body)) + "\"");
            }
        }
        return entries.isEmpty() ? "{}\n" : "{\n" + String.join(",\n", entries) + "\n}\n";
    }

    /** The span as it reads in the decoded value: delimiters on their own lines around the body. */
    private static String span(SharedKey shared, String body) {
        String safe = GuardrailFileWriter.neutraliseMarkers(body,
            new String[]{shared.markerStart(), shared.markerEnd()});
        return safe.isEmpty()
            ? shared.markerStart() + "\n" + shared.markerEnd()
            : shared.markerStart() + "\n" + safe + "\n" + shared.markerEnd();
    }

    /**
     * The edit that puts {@code body}'s span into {@code member}'s string value: over the existing
     * span when there is one, after the user's text when there is not.
     *
     * @return {@code null} when the value holds a start marker with no end marker after it
     */
    private static @Nullable Edit spliceSpan(String text, Member member, SharedKey shared, String body) {
        int contentStart = member.valueStart() + 1;
        int contentEnd = member.valueEnd() - 1;
        Decoded decoded = Decoded.of(text, contentStart, contentEnd);
        String value = decoded.value();
        String replacement = Escape.json(span(shared, body));

        int start = GuardrailFileWriter.indexOfMarkerLine(value, shared.markerStart(), 0);
        if (start >= 0) {
            int end = GuardrailFileWriter.indexOfMarkerLine(value, shared.markerEnd(),
                start + shared.markerStart().length());
            if (end < 0) {
                return null;
            }
            end += shared.markerEnd().length();
            return new Edit(decoded.rawOffset(start), decoded.rawOffset(end), replacement);
        }
        if (value.isEmpty()) {
            return new Edit(contentStart, contentEnd, replacement);
        }
        String separator = value.endsWith("\n\n") ? "" : value.endsWith("\n") ? "\n" : "\n\n";
        return new Edit(contentEnd, contentEnd, Escape.json(separator) + replacement);
    }

    /**
     * The edit that adds {@code additions} as new members at the end of the top-level object,
     * indented like the members already there.
     */
    private static Edit insertion(String text, List<Member> members, List<String> additions) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        int close = text.lastIndexOf('}');
        if (members.isEmpty()) {
            int open = text.indexOf('{');
            String body = newline + "  " + String.join("," + newline + "  ", additions) + newline;
            return new Edit(open + 1, close, body);
        }
        int keyStart = members.get(0).keyStart();
        int lineStart = text.lastIndexOf('\n', keyStart) + 1;
        String lead = text.substring(lineStart, keyStart);
        String separator = lead.isBlank() && lineStart > 0 ? "," + newline + lead : ", ";
        int at = members.get(members.size() - 1).valueEnd();
        return new Edit(at, at, separator + String.join(separator, additions));
    }

    /**
     * A JSON string's decoded value, with the raw offset each decoded character came from, so a
     * range found in the value can be spliced back into the original text without re-encoding the
     * characters around it.
     */
    private record Decoded(String value, int[] offsets) {

        /** Raw offset in the document of decoded index {@code i}; {@code value.length()} maps to the closing quote. */
        int rawOffset(int i) {
            return offsets[i];
        }

        static Decoded of(String text, int from, int to) {
            StringBuilder value = new StringBuilder(to - from);
            int[] offsets = new int[to - from + 1];
            int n = 0;
            int i = from;
            while (i < to) {
                offsets[n++] = i;
                char c = text.charAt(i);
                if (c != '\\') {
                    value.append(c);
                    i++;
                    continue;
                }
                char esc = text.charAt(i + 1);
                switch (esc) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                        i += 4;
                    }
                    default -> value.append(esc); // \" \\ \/
                }
                i += 2;
            }
            offsets[n] = to;
            return new Decoded(value.toString(), java.util.Arrays.copyOf(offsets, n + 1));
        }
    }

    /**
     * Walks the top level of a document already known to be a well-formed JSON object, recording
     * each member's key and value offsets. It does not validate: {@link Json#parseObject} has.
     */
    private static final class Scanner {
        private final String text;
        private int pos;

        Scanner(String text) {
            this.text = text;
        }

        List<Member> topLevelMembers() {
            List<Member> members = new ArrayList<>();
            skipWhitespace();
            pos++; // {
            while (true) {
                skipWhitespace();
                if (text.charAt(pos) == '}') {
                    return members;
                }
                int keyStart = pos;
                skipString();
                String key = Decoded.of(text, keyStart + 1, pos - 1).value();
                skipWhitespace();
                pos++; // :
                skipWhitespace();
                int valueStart = pos;
                skipValue();
                members.add(new Member(key, keyStart, valueStart, pos));
                skipWhitespace();
                if (text.charAt(pos++) == '}') {
                    return members;
                }
            }
        }

        private void skipWhitespace() {
            while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
        }

        private void skipString() {
            pos++; // opening quote
            while (text.charAt(pos) != '"') {
                pos += text.charAt(pos) == '\\' ? 2 : 1;
            }
            pos++;
        }

        private void skipValue() {
            char c = text.charAt(pos);
            if (c == '"') {
                skipString();
                return;
            }
            if (c == '{' || c == '[') {
                int depth = 0;
                do {
                    char d = text.charAt(pos);
                    if (d == '"') {
                        skipString();
                        continue;
                    }
                    if (d == '{' || d == '[') {
                        depth++;
                    } else if (d == '}' || d == ']') {
                        depth--;
                    }
                    pos++;
                } while (depth > 0);
                return;
            }
            while (pos < text.length() && ",}] \t\r\n".indexOf(text.charAt(pos)) < 0) {
                pos++;
            }
        }
    }
}
