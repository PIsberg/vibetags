package se.deversity.vibetags.processor.internal;

/**
 * Script or source text with its comments taken out, in two forms of the same length.
 *
 * <p>For the places that find a declaration by pattern rather than by parsing: a Gradle settings
 * script's {@code include}s, a Groovy file's type declarations. A pattern run over the raw text
 * reads a commented-out declaration as a real one, and one run over text with only the comments
 * removed still reads the word inside a string literal. So the scan knows both: where a comment
 * is, which needs knowing where a literal is ({@code "http://host"} holds no comment), and what is
 * inside a literal.
 *
 * <p>A scan, not a parse. A {@code //} comment runs to the end of its line and is dropped, the
 * newline kept. A block comment becomes one space, and one that never closes runs to the end of
 * the text. A literal opens at {@code "} or {@code '} and closes at the same character or at the
 * end of its line, whichever comes first, with a backslash taking the character after it along.
 * Opened by three of the same quote it is Groovy's or Kotlin's multi-line string instead: it runs
 * to the next three, across lines, and its line breaks stay line breaks in both forms (#1167).
 *
 * @param code                the text without its comments, literals as written
 * @param codeOutsideLiterals {@code code} with every character between a literal's quotes blanked
 *                            to a space. The same length as {@code code}, so an offset found in
 *                            one reads the other
 */
record Uncommented(String code, String codeOutsideLiterals) {

    static Uncommented of(String text) {
        return new Scan(text).result();
    }

    /** One pass over the text, writing both forms as it goes. */
    private static final class Scan {

        private final String text;
        private final StringBuilder code;
        private final StringBuilder outsideLiterals;
        /** The quote character of the literal being read; 0 outside one. */
        private char quote;
        /** Whether that literal was opened by three quotes, and so ends only at three. */
        private boolean multiLine;
        private int at;

        Scan(String text) {
            this.text = text;
            this.code = new StringBuilder(text.length());
            this.outsideLiterals = new StringBuilder(text.length());
        }

        Uncommented result() {
            while (at < text.length()) {
                if (insideLiteral()) {
                    literalCharacter();
                } else if (text.startsWith("//", at)) {
                    skipLineComment();
                } else if (text.startsWith("/*", at)) {
                    skipBlockComment();
                } else {
                    codeCharacter();
                }
            }
            return new Uncommented(code.toString(), outsideLiterals.toString());
        }

        private boolean insideLiteral() {
            return quote != 0;
        }

        private void skipLineComment() {
            int newline = text.indexOf('\n', at);
            at = newline < 0 ? text.length() : newline;
        }

        private void skipBlockComment() {
            int end = text.indexOf("*/", at + 2);
            emit(' ', ' ');
            at = end < 0 ? text.length() : end + 2;
        }

        private void codeCharacter() {
            char c = text.charAt(at);
            if (c == '"' || c == '\'') {
                quote = c;
                multiLine = text.startsWith(tripleQuote(), at);
            }
            if (multiLine) {
                emitTripleQuote();
            } else {
                emit(c, c);
                at++;
            }
        }

        private void literalCharacter() {
            char c = text.charAt(at);
            if (c == '\\' && at + 1 < text.length()) {
                // An escaped character is content whatever it is, the closing quote included.
                emit(c, ' ');
                emit(text.charAt(at + 1), ' ');
                at += 2;
                return;
            }
            if (multiLine) {
                multiLineCharacter(c);
                return;
            }
            boolean closes = c == quote || c == '\n';
            emit(c, closes ? c : ' ');
            if (closes) {
                quote = 0;
            }
            at++;
        }

        private void multiLineCharacter(char c) {
            if (text.startsWith(tripleQuote(), at)) {
                emitTripleQuote();
                quote = 0;
                multiLine = false;
            } else {
                emit(c, c == '\n' ? '\n' : ' ');
                at++;
            }
        }

        private String tripleQuote() {
            return quote == '"' ? "\"\"\"" : "'''";
        }

        private void emitTripleQuote() {
            for (int i = 0; i < 3; i++) {
                emit(quote, quote);
            }
            at += 3;
        }

        private void emit(char asWritten, char outsideLiteral) {
            code.append(asWritten);
            outsideLiterals.append(outsideLiteral);
        }
    }
}
