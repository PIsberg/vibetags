package se.deversity.vibetags.ksp.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The line range of a Kotlin declaration, from its source text. KSP reports where a declaration
 * starts ({@code FileLocation.lineNumber}) but not where it ends, and {@code .vibetags-locks} needs
 * both: {@code action/locked-files} maps a pull request's changed lines onto locked elements by range.
 *
 * <p>This reads the text the way the Kotlin grammar nests it, not the way it parses it: brackets are
 * matched, and strings (templates included), character literals and nested block comments are
 * skipped so a brace inside them does not count. A declaration ends
 * <ul>
 *   <li>where the body it opened closes ({@code class ... { }}, {@code fun ... { }}, {@code by lazy { }});</li>
 *   <li>otherwise at the end of the first line that does not continue onto the next: a line that is
 *       annotations only, that ends in an operator, or whose next line starts with one, a
 *       {@code get}/{@code set} accessor, {@code where}, {@code by} or a {@code {}, continues;</li>
 *   <li>for a constructor property or an enum entry, also at a {@code ,} or {@code ;} at its own
 *       level (generic arguments included, so {@code Map<A, B>} does not end it);</li>
 *   <li>before a closing bracket that belongs to an enclosing scope.</li>
 * </ul>
 * The start is extended upward over annotation-only lines, since javac's ranges include a
 * declaration's annotations and modifiers.
 */
final class KotlinExtent {

    private static final String[] CONTINUES_AT_END = {
        // Not "?" or "!": a line ending in a nullable type (Int?) or in !! is complete.
        "=", "->", ".", "(", "[", ":", "&&", "||", "+", "-", "*", "/", "%", "?:",
    };
    private static final String[] CONTINUES_AT_START = {
        ".", "?.", "?:", "&&", "||", "=", ":", "{", "->", "+", "-", "*", "/", "where ", "by ",
        "get(", "get()", "set(", "private set", "internal set", "protected set", "public set",
    };

    private final String source;
    private final List<Integer> lineStarts = new ArrayList<>();

    KotlinExtent(String source) {
        this.source = source;
        lineStarts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }
    }

    int lineCount() {
        return lineStarts.size();
    }

    /** {@code line}, moved up over the annotation-only lines directly above it. */
    int start(int line) {
        int start = Math.max(1, Math.min(line, lineCount()));
        while (start > 1 && isAnnotationOnly(code(start - 1))) {
            start--;
        }
        return start;
    }

    /**
     * The last line of the declaration that starts on {@code line}.
     *
     * @param listItem whether the declaration is an item of a comma-separated list (a constructor
     *                 property, an enum entry), which a {@code ,} at its own level ends
     */
    int end(int line, boolean listItem) {
        if (line < 1 || line > lineCount()) {
            return line;
        }
        Cursor c = new Cursor(lineStarts.get(line - 1), line);
        int depth = 0;
        int angles = 0;
        boolean body = false;
        int lastCode = line;
        while (c.pos < source.length()) {
            char ch = source.charAt(c.pos);
            if (ch == '\n') {
                if (depth == 0 && !body && !continuesAfter(c.line)) {
                    return lastCode;
                }
                c.line++;
                c.pos++;
                continue;
            }
            if (skipNonCode(c)) {
                lastCode = c.line;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                c.pos++;
                continue;
            }
            int before = lastCode;
            lastCode = c.line;
            switch (ch) {
                case '(', '[' -> depth++;
                case '{' -> {
                    if (depth == 0) {
                        body = true;
                    }
                    depth++;
                }
                case ')', ']' -> {
                    depth--;
                    if (depth < 0) {
                        return before;
                    }
                }
                case '}' -> {
                    depth--;
                    if (depth < 0) {
                        return before;
                    }
                    if (body && depth == 0) {
                        return c.line;
                    }
                }
                case '<' -> {
                    if (listItem) {
                        angles++;
                    }
                }
                case '>' -> {
                    if (listItem && angles > 0) {
                        angles--;
                    }
                }
                case ',', ';' -> {
                    if (listItem && depth == 0 && angles == 0 && !body) {
                        return c.line;
                    }
                }
                default -> { }
            }
            c.pos++;
        }
        return lastCode;
    }

    /** A position in the text and the 1-based line it is on. */
    private static final class Cursor {
        int pos;
        int line;

        Cursor(int pos, int line) {
            this.pos = pos;
            this.line = line;
        }
    }

    /** Skips a comment, string or character literal at the cursor; false when there is none. */
    private boolean skipNonCode(Cursor c) {
        char ch = source.charAt(c.pos);
        char next = c.pos + 1 < source.length() ? source.charAt(c.pos + 1) : '\0';
        if (ch == '/' && next == '/') {
            while (c.pos < source.length() && source.charAt(c.pos) != '\n') {
                c.pos++;
            }
            return true;
        }
        if (ch == '/' && next == '*') {
            skipBlockComment(c);
            return true;
        }
        if (ch == '"') {
            skipString(c);
            return true;
        }
        if (ch == '\'') {
            c.pos++;
            while (c.pos < source.length() && source.charAt(c.pos) != '\'' && source.charAt(c.pos) != '\n') {
                c.pos += source.charAt(c.pos) == '\\' ? 2 : 1;
            }
            c.pos++;
            return true;
        }
        return false;
    }

    /** Kotlin block comments nest. */
    private void skipBlockComment(Cursor c) {
        int nesting = 0;
        while (c.pos < source.length()) {
            if (source.startsWith("/*", c.pos)) {
                nesting++;
                c.pos += 2;
            } else if (source.startsWith("*/", c.pos)) {
                nesting--;
                c.pos += 2;
                if (nesting == 0) {
                    return;
                }
            } else {
                advance(c);
            }
        }
    }

    /** A plain or raw string, with {@code ${...}} templates, whose braces may nest strings again. */
    private void skipString(Cursor c) {
        boolean raw = source.startsWith("\"\"\"", c.pos);
        c.pos += raw ? 3 : 1;
        while (c.pos < source.length()) {
            char ch = source.charAt(c.pos);
            if (raw && source.startsWith("\"\"\"", c.pos)) {
                c.pos += 3;
                while (c.pos < source.length() && source.charAt(c.pos) == '"') {
                    c.pos++; // a raw string may end in more quotes than three
                }
                return;
            }
            if (!raw && ch == '"') {
                c.pos++;
                return;
            }
            if (!raw && ch == '\\') {
                c.pos += 2;
            } else if (ch == '$' && c.pos + 1 < source.length() && source.charAt(c.pos + 1) == '{') {
                c.pos += 2;
                skipTemplate(c);
            } else {
                advance(c);
            }
        }
    }

    private void skipTemplate(Cursor c) {
        int depth = 1;
        while (c.pos < source.length() && depth > 0) {
            if (skipNonCode(c)) {
                continue;
            }
            char ch = source.charAt(c.pos);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            }
            advance(c);
        }
    }

    private void advance(Cursor c) {
        if (source.charAt(c.pos) == '\n') {
            c.line++;
        }
        c.pos++;
    }

    /** Whether the declaration carries on past the end of {@code line}. */
    private boolean continuesAfter(int line) {
        String code = code(line);
        if (code.isEmpty() || isAnnotationOnly(code)) {
            return true;
        }
        for (String end : CONTINUES_AT_END) {
            if (code.endsWith(end) && !code.endsWith("++") && !code.endsWith("--")) {
                return true;
            }
        }
        for (int next = line + 1; next <= lineCount(); next++) {
            String following = code(next);
            if (!following.isEmpty()) {
                for (String start : CONTINUES_AT_START) {
                    if (following.startsWith(start)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /** The trimmed text of {@code line} with a trailing {@code //} comment removed. */
    private String code(int line) {
        int start = lineStarts.get(line - 1);
        int end = line < lineCount() ? lineStarts.get(line) - 1 : source.length();
        String text = source.substring(start, Math.max(start, end));
        int quotes = 0;
        for (int i = 0; i + 1 < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                quotes++;
            } else if (ch == '/' && text.charAt(i + 1) == '/' && quotes % 2 == 0) {
                text = text.substring(0, i);
                break;
            }
        }
        return text.strip();
    }

    /**
     * Whether {@code code} is annotations and nothing else: {@code @Name}, {@code @target:Name},
     * {@code @a.b.Name}, each optionally with a balanced argument list.
     */
    static boolean isAnnotationOnly(String code) {
        int i = 0;
        boolean any = false;
        while (i < code.length()) {
            char ch = code.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch != '@') {
                return false;
            }
            i++;
            int nameStart = i;
            while (i < code.length() && (Character.isJavaIdentifierPart(code.charAt(i))
                    || code.charAt(i) == '.' || code.charAt(i) == ':')) {
                i++;
            }
            if (i == nameStart) {
                return false;
            }
            if (i < code.length() && code.charAt(i) == '(') {
                i = closingParen(code, i);
                if (i < 0) {
                    return false;
                }
            }
            any = true;
        }
        return any;
    }

    /** The index after the {@code )} matching the {@code (} at {@code open}, or -1. */
    private static int closingParen(String code, int open) {
        int depth = 0;
        boolean string = false;
        boolean escaped = false;
        for (int i = open; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (string) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    string = false;
                }
            } else if (ch == '"') {
                string = true;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }
}
