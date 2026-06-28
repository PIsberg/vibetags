package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Branch-complete unit tests for {@link Escape}: every metacharacter case, the {@code \\uXXXX}
 * control-char path, and the null/empty guards for both {@code xml} and {@code json}.
 */
class EscapeTest {

    private static String ch(int c) {
        return String.valueOf((char) c);
    }

    // ---------------------------------------------------------------- xml()

    @Test
    void xml_nullAndEmptyReturnedUnchanged() {
        assertNull(Escape.xml(null));
        assertEquals("", Escape.xml(""));
    }

    @Test
    void xml_escapesEveryMetacharacter() {
        assertEquals("&amp;", Escape.xml("&"));
        assertEquals("&lt;", Escape.xml("<"));
        assertEquals("&gt;", Escape.xml(">"));
        assertEquals("&quot;", Escape.xml("\""));
        assertEquals("&#39;", Escape.xml("'"));
    }

    @Test
    void xml_passesOrdinaryTextThroughAndEscapesInline() {
        assertEquals("plain text 123", Escape.xml("plain text 123"));
        // a generic method signature — the real-world source of stray '<' and '>'
        assertEquals("Map&lt;String, Object&gt;", Escape.xml("Map<String, Object>"));
        // '&' is handled first, so an already-entity-looking input is escaped, not passed through
        assertEquals("&amp;lt;", Escape.xml("&lt;"));
        assertEquals("a &amp; b &lt; c &gt; d &quot;e&quot; &#39;f&#39;",
            Escape.xml("a & b < c > d \"e\" 'f'"));
    }

    // --------------------------------------------------------------- json()

    @Test
    void json_nullAndEmptyReturnedUnchanged() {
        assertNull(Escape.json(null));
        assertEquals("", Escape.json(""));
    }

    @Test
    void json_escapesQuoteBackslashAndControlShorthands() {
        assertEquals("\\\"", Escape.json("\""));
        assertEquals("\\\\", Escape.json("\\"));
        assertEquals("\\n", Escape.json("\n"));
        assertEquals("\\r", Escape.json("\r"));
        assertEquals("\\t", Escape.json("\t"));
    }

    @Test
    void json_escapesOtherControlCharsAsLowercaseUnicode() {
        assertEquals("\\u0000", Escape.json(ch(0x00)));
        assertEquals("\\u0008", Escape.json(ch(0x08)));  // backspace — not a switch shorthand
        assertEquals("\\u000b", Escape.json(ch(0x0b)));  // vertical tab — not a switch shorthand
        assertEquals("\\u000c", Escape.json(ch(0x0c)));  // form feed — not a switch shorthand
        assertEquals("\\u001f", Escape.json(ch(0x1f)));  // last control char below 0x20
        // 0x20 (space) and above are ordinary and pass through unchanged
        assertEquals(" ", Escape.json(ch(0x20)));
    }

    @Test
    void json_passesOrdinaryTextThrough() {
        assertEquals("hello world", Escape.json("hello world"));
        assertEquals("say \\\"hi\\\" then", Escape.json("say \"hi\" then"));
        assertEquals("path\\\\to\\\\file", Escape.json("path\\to\\file"));
    }
}
