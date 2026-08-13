package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, dependency-free JSON reader and string escaper, sized for the transitive manifest and
 * nothing else.
 *
 * <p>VibeTags ships onto the consumer's annotation-processor path, so every dependency it takes is
 * a dependency their build inherits. Reading one small, self-produced document does not justify
 * that, and the renderers already emit JSON by hand for the same reason.
 *
 * <p>Parsing is strict: anything malformed raises {@link JsonException} rather than guessing. A
 * manifest is written by VibeTags and read by VibeTags, so a document that does not parse means a
 * corrupted or hostile JAR entry, and the caller degrades to skipping it with a warning.
 *
 * <p>Numbers are returned as {@link Num}, which carries the lexeme unchanged. Keeping the text
 * avoids a double-rounding step that could turn a version into something that no longer matches
 * itself, and the wrapper rather than a bare {@link String} is what lets {@link #string} tell a
 * quoted value from a numeric one: without it, a manifest whose {@code origin} was written as a
 * number would silently be read as though the library had quoted it.
 */
public final class Json {

    private Json() {}

    /**
     * A JSON number, kept as the literal text the document carried.
     *
     * <p>{@link #toString()} returns that text, so a caller that only wants to parse it as an
     * integer can hand it straight to {@code Integer.parseInt} without unwrapping.
     */
    public record Num(String lexeme) {
        @Override
        public String toString() {
            return lexeme;
        }
    }

    /** Raised when input is not well-formed JSON, or is deeper than {@link #MAX_DEPTH}. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        JsonException(String message) {
            super(message);
        }

        JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Nesting limit. A manifest is two levels deep; the cap exists so a malformed or hostile JAR
     * entry consisting of thousands of open brackets cannot overflow the stack inside somebody
     * else's javac.
     */
    static final int MAX_DEPTH = 32;

    /** Input size limit, in characters. Same reasoning as {@link #MAX_DEPTH}. */
    static final int MAX_INPUT = 4 * 1024 * 1024;

    /**
     * Whether {@code s} is a JSON number per RFC 8259 §6: an optional minus, an integer part with
     * no leading zeros, an optional fraction, and an optional exponent.
     *
     * <p>Hand-written rather than a regex. The equivalent pattern is linear and has no nested
     * quantifier to backtrack over, but Find Security Bugs reports it as a ReDoS candidate, and a
     * suppression for a parser reading untrusted JAR entries is a worse trade than twenty lines
     * that cannot backtrack at all. It is also a single left-to-right pass with no allocation,
     * which suits a check that runs once per numeric token.
     */
    private static boolean isJsonNumber(String s) {
        int i = 0;
        int n = s.length();
        if (i < n && s.charAt(i) == '-') {
            i++;
        }
        if (i >= n) {
            return false;
        }
        // Integer part: a lone zero, or a non-zero digit followed by any digits. "01" is invalid.
        if (s.charAt(i) == '0') {
            i++;
        } else if (isDigit(s.charAt(i))) {
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
        } else {
            return false;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            int fractionStart = i;
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
            if (i == fractionStart) {
                return false;
            }
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int exponentStart = i;
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
            if (i == exponentStart) {
                return false;
            }
        }
        return i == n;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Parses one JSON document into {@link Map}, {@link List}, {@link String}, {@link Boolean} or
     * {@code null}. Objects keep their document order.
     *
     * @throws JsonException if the text is not a single well-formed JSON value
     */
    public static @Nullable Object parse(String text) {
        if (text == null) {
            throw new JsonException("null input");
        }
        if (text.length() > MAX_INPUT) {
            throw new JsonException("input exceeds " + MAX_INPUT + " characters");
        }
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("trailing content at offset " + parser.pos);
        }
        return value;
    }

    /**
     * Parses a document expected to be an object.
     *
     * @throws JsonException if the text is not a well-formed JSON object
     */
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object at the top level");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    /** {@code value} as a string, or {@code fallback} when it is absent or not a string. */
    public static String string(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value instanceof String s ? s : fallback;
    }

    /** The JSON string literal for {@code value}, quotes included, escaped per RFC 8259. */
    public static String quote(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // Control characters must be escaped; everything else (including non-ASCII) is
                    // emitted as-is, because the manifest is written and read as UTF-8.
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------------------------------

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        @Nullable Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("nesting deeper than " + MAX_DEPTH);
            }
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        Map<String, Object> readObject(int depth) {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                out.put(key, readValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' at offset " + (pos - 1));
                }
            }
        }

        List<Object> readArray(int depth) {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                out.add(readValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' at offset " + (pos - 1));
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder(32);
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("unterminated escape");
                }
                char esc = src.charAt(pos++);
                switch (esc) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'u'  -> {
                        if (pos + 4 > src.length()) {
                            throw new JsonException("truncated \\u escape");
                        }
                        String hex = src.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new JsonException("bad \\u escape: " + hex, e);
                        }
                        pos += 4;
                    }
                    default -> throw new JsonException("unknown escape \\" + esc);
                }
            }
        }

        /**
         * Reads a number, then checks the lexeme is actually one.
         *
         * <p>The scan accepts a character set rather than a grammar, which on its own would let
         * {@code -}, {@code .}, {@code --5} and {@code 1.2.3} through as numbers. That contradicts
         * this class's whole contract: a manifest arrives from a JAR the consuming build did not
         * write, and the promise is that anything malformed raises rather than being guessed at.
         * Validating the accumulated lexeme keeps the scan simple and the contract honest.
         */
        Num readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!atEnd()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw new JsonException("unexpected character '" + src.charAt(pos) + "' at offset " + pos);
            }
            String lexeme = src.substring(start, pos);
            if (!isJsonNumber(lexeme)) {
                throw new JsonException("malformed number '" + lexeme + "' at offset " + start);
            }
            return new Num(lexeme);
        }

        @Nullable Object readLiteral(String literal, @Nullable Object value) {
            if (!src.startsWith(literal, pos)) {
                throw new JsonException("expected '" + literal + "' at offset " + pos);
            }
            pos += literal.length();
            return value;
        }

        char peek() {
            return atEnd() ? '\0' : src.charAt(pos);
        }

        char next() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return src.charAt(pos++);
        }

        void expect(char c) {
            if (atEnd() || src.charAt(pos) != c) {
                throw new JsonException("expected '" + c + "' at offset " + pos);
            }
            pos++;
        }
    }
}
