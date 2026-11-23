package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Uncommented} character for character: what is removed, what is blanked, and that the two
 * forms stay the same length, which is what lets an offset found in one be read in the other.
 */
class UncommentedTest {

    private static void scans(String text, String code, String outsideLiterals) {
        Uncommented scanned = Uncommented.of(text);
        assertEquals(code, scanned.code(), "code");
        assertEquals(outsideLiterals, scanned.codeOutsideLiterals(), "code outside literals");
        assertEquals(scanned.code().length(), scanned.codeOutsideLiterals().length(), "the two forms must align");
    }

    @Test
    @DisplayName("a line comment is dropped up to its newline, a block comment becomes one space")
    void commentsAreTakenOut() {
        scans("a /* b */ c // d", "a   c ", "a   c ");
        scans("a // c\r\nb", "a \nb", "a \nb");
        scans("a /* b\n c */ d", "a   d", "a   d");
        scans("a /* never closed\nb", "a  ", "a  ");
        scans("x = 1 / 2", "x = 1 / 2", "x = 1 / 2");
    }

    @Test
    @DisplayName("a literal keeps its text in one form and is blanked between its quotes in the other")
    void literalsAreKeptAndBlanked() {
        scans("x = \"a//b\" // c\ny", "x = \"a//b\" \ny", "x = \"    \" \ny");
        scans("s = 'it\\'s' + \"q\"", "s = 'it\\'s' + \"q\"", "s = '     ' + \" \"");
        scans("s = 'a\\\\' // c", "s = 'a\\\\' ", "s = '   ' ");
        scans("s = \"/* not a comment */\"", "s = \"/* not a comment */\"", "s = \"                   \"");
        scans("s = 'say \"hi\"'", "s = 'say \"hi\"'", "s = '        '");
    }

    @Test
    @DisplayName("a literal that never closes ends with its line, so the next line is code again")
    void anUnclosedLiteralEndsWithItsLine() {
        scans("s = \"open\nnext 'x'", "s = \"open\nnext 'x'", "s = \"    \nnext ' '");
        scans("s = \"ends with a backslash \\", "s = \"ends with a backslash \\", "s = \"                       ");
    }

    /**
     * Groovy and Kotlin write a multi-line string between triple quotes. Read as an empty literal
     * followed by an ordinary one, it ended at its first newline, and every later line of it was
     * scanned as code (#1167).
     */
    @Test
    @DisplayName("a triple-quoted literal runs to its closing triple quote, across lines")
    void aTripleQuotedLiteralSpansLines() {
        scans("s = \"\"\"a\n// b\n\"\"\" // c", "s = \"\"\"a\n// b\n\"\"\" ", "s = \"\"\" \n    \n\"\"\" ");
        scans("s = '''it's\n/* x */'''\nnext", "s = '''it's\n/* x */'''\nnext", "s = '''    \n       '''\nnext");
        scans("s = \"\"\"never closed\nnext", "s = \"\"\"never closed\nnext", "s = \"\"\"            \n    ");
        scans("e = \"\" + 'x'", "e = \"\" + 'x'", "e = \"\" + ' '");
    }

    @Test
    @DisplayName("text with nothing to take out comes back as it is")
    void plainTextIsUnchanged() {
        scans("", "", "");
        scans("/", "/", "/");
        scans("include 'a'\n", "include 'a'\n", "include ' '\n");
    }
}
