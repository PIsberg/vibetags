package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-written JSON reader, which exists so the processor takes no third-party dependency onto
 * every consumer's annotation-processor path.
 *
 * <p>Weighted towards malformed input. A manifest arrives from a JAR the consuming build did not
 * write, so "what does this do with input it did not expect" is the question that matters, and the
 * required answer is a clean exception rather than a half-read document or a hang.
 */
class JsonTest {

    @Test
    void readsAnObjectKeepingDocumentOrder() {
        Map<String, Object> parsed = Json.parseObject("{\"b\":\"1\",\"a\":\"2\",\"c\":\"3\"}");
        assertEquals(List.of("b", "a", "c"), List.copyOf(parsed.keySet()),
            "object order must survive the parse: the manifest's determinism depends on it");
    }

    @Test
    void readsNestedStructures() {
        Map<String, Object> parsed = Json.parseObject(
            "{\"rules\":[{\"annotation\":\"@AISecure\",\"members\":{\"note\":\"x\"}}]}");
        Object rules = parsed.get("rules");
        assertTrue(rules instanceof List, "rules should parse as a list");
        Object first = ((List<?>) rules).get(0);
        assertTrue(first instanceof Map, "a rule should parse as an object");
        assertEquals("@AISecure", ((Map<?, ?>) first).get("annotation"));
    }

    @Test
    void readsEveryEscapeSequence() {
        Map<String, Object> parsed = Json.parseObject(
            "{\"s\":\"a\\\"b\\\\c\\/d\\ne\\rf\\tg\\bh\\fi\\u00e5\"}");
        assertEquals("a\"b\\c/d\ne\rf\tg\bh\fiå", parsed.get("s"));
    }

    @Test
    void roundTripsThroughQuote() {
        String awkward = "line1\nline2\t\"quoted\" \\ back \u0001 control \b backspace \f feed å";
        Map<String, Object> parsed = Json.parseObject("{\"v\":" + Json.quote(awkward) + "}");
        assertEquals(awkward, parsed.get("v"),
            "anything quote() emits must read back identically, or a library's own words change "
                + "meaning between publishing and consumption");
    }

    @Test
    void quoteEscapesControlCharactersAsUnicode() {
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
    }

    @Test
    void quoteRendersNullAsTheNullLiteral() {
        assertEquals("null", Json.quote(null));
    }

    @Test
    void readsLiteralsAndNumbers() {
        Map<String, Object> parsed = Json.parseObject(
            "{\"t\":true,\"f\":false,\"n\":null,\"i\":42,\"d\":-1.5e3}");
        assertEquals(Boolean.TRUE, parsed.get("t"));
        assertEquals(Boolean.FALSE, parsed.get("f"));
        assertNull(parsed.get("n"));
        assertEquals(new Json.Num("42"), parsed.get("i"),
            "numbers keep their lexeme, so a version cannot be re-rounded");
        assertEquals(new Json.Num("-1.5e3"), parsed.get("d"));
        assertEquals("42", String.valueOf(parsed.get("i")),
            "a number must print as itself, so callers can parse it without unwrapping");
    }

    @Test
    void readsEmptyContainers() {
        Map<String, Object> parsed = Json.parseObject("{\"o\":{},\"a\":[]}");
        assertEquals(Map.of(), parsed.get("o"));
        assertEquals(List.of(), parsed.get("a"));
    }

    @Test
    void skipsWhitespaceEverywhereItIsLegal() {
        Map<String, Object> parsed = Json.parseObject("  {\n\t\"a\" :\r\n [ 1 , 2 ]\n}  ");
        assertEquals(List.of(new Json.Num("1"), new Json.Num("2")), parsed.get("a"));
    }

    /**
     * RFC 8259 defines four whitespace characters and no others. {@link Character#isWhitespace}
     * also accepts form feed, vertical tab and the Unicode spaces, so swapping it in would quietly
     * widen what a third-party manifest may contain: 0x0C form feed, 0x0B vertical tab, 0x2003 em
     * space and 0x3000 ideographic space are all whitespace to it. 0x00A0 is here for the next
     * candidate, {@link Character#isSpaceChar}, which accepts the no-break space too.
     */
    @ParameterizedTest(name = "0x{0}")
    @ValueSource(ints = {0x0C, 0x0B, 0xA0, 0x2003, 0x3000})
    void rejectsWhitespaceThatJsonDoesNotDefine(int codePoint) {
        String c = String.valueOf((char) codePoint);
        assertThrows(Json.JsonException.class, () -> Json.parse(c + "1"), "before a value");
        assertThrows(Json.JsonException.class, () -> Json.parse("[1," + c + "2]"), "between values");
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1}" + c), "after the document");
    }

    /**
     * A JSON number is ASCII digits only. {@link Character#isDigit} accepts every Unicode decimal
     * digit, so using it to scan or check a number would read 0x0661 (Arabic-Indic one), 0x0967
     * (Devanagari one) or 0xFF11 (fullwidth one) as a number nobody else's JSON parser would accept.
     */
    @ParameterizedTest(name = "0x{0}")
    @ValueSource(ints = {0x0661, 0x0967, 0xFF11})
    void rejectsDigitsOutsideAscii(int codePoint) {
        String digit = String.valueOf((char) codePoint);
        assertThrows(Json.JsonException.class, () -> Json.parse(digit), "on its own");
        assertThrows(Json.JsonException.class, () -> Json.parse("1" + digit), "after an ASCII digit");
        assertThrows(Json.JsonException.class, () -> Json.parse("1e" + digit), "as an exponent");
        assertThrows(Json.JsonException.class, () -> Json.parse("[" + digit + "]"), "inside an array");
    }

    @Test
    void rejectsTrailingContent() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{} {}"),
            "two documents in one file is corruption, not a document to read the first half of");
    }

    @Test
    void rejectsMalformedNumbers() {
        // The scan accepts a character set, not a grammar, so without a check on the accumulated
        // lexeme all of these parse as numbers — in a class whose contract is that anything
        // malformed raises rather than being guessed at, reading documents from JARs the build
        // did not write.
        for (String bad : List.of("-", ".", "--5", "1.2.3", "1e+e-.3", "01", "1.", ".5", "1e", "+1")) {
            assertThrows(Json.JsonException.class, () -> Json.parse(bad),
                "'" + bad + "' is not a JSON number");
        }
    }

    @Test
    void acceptsEveryShapeTheGrammarAllows() {
        for (String good : List.of("0", "-0", "42", "-42", "1.5", "-1.5", "1e3", "1E3",
                "1e+3", "1e-3", "-1.5e-3", "0.0")) {
            assertEquals(new Json.Num(good), Json.parse(good), good);
        }
    }

    @Test
    void aMalformedNumberIsRejectedInsideADocumentToo() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"manifestVersion\": 1.2.3}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1, --2]"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":\"unclosed"));
    }

    /**
     * RFC 8259 section 7: the characters U+0000 to U+001F must be escaped inside a string. The
     * reader appended them as they came, so a manifest line with a raw newline or NUL in a value was
     * read where every other JSON parser would refuse it. The escaped form reads the same text and
     * is still accepted (issue #1186).
     */
    @ParameterizedTest(name = "0x{0}")
    @ValueSource(ints = {0x00, 0x09, 0x0A, 0x0D, 0x1F})
    void rejectsAControlCharacterLeftUnescapedInAString(int codePoint) {
        String c = String.valueOf((char) codePoint);
        assertThrows(Json.JsonException.class, () -> Json.parse("\"a" + c + "b\""), "in a value");
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"k" + c + "\":1}"), "in a key");
    }

    /**
     * A unicode escape is exactly four hex digits. The four were handed to Integer.parseInt, which
     * also takes a leading sign, so a plus sign read as 'A' for +041 and a minus as U+FFFF for -001
     * (issue #1186).
     */
    @Test
    void rejectsASignInAUnicodeEscape() {
        for (String bad : List.of("+041", "-001", "+FFF")) {
            assertThrows(Json.JsonException.class, () -> Json.parse("\"\\u" + bad + "\""), bad);
        }
    }

    @Test
    void stillReadsTheEscapedFormOfEveryControlCharacter() {
        assertEquals("a" + (char) 10 + "b" + (char) 0 + (char) 31, Json.parse("\"a\\nb\\u0000\\u001F\""));
    }

    @Test
    void rejectsTruncatedUnicodeEscape() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":\"\\u00\"}"));
    }

    @Test
    void rejectsUnknownEscape() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":\"\\q\"}"));
    }

    @Test
    void rejectsMissingSeparator() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1 \"b\":2}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1 2]"));
    }

    @Test
    void rejectsANonObjectWhereAnObjectIsRequired() {
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1,2]"));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(Json.JsonException.class, () -> Json.parse(null));
    }

    @Test
    void rejectsInputDeeperThanTheNestingCap() {
        // A hostile or corrupt JAR entry must not overflow the stack inside somebody else's javac,
        // so depth is bounded rather than left to the recursion limit.
        String deep = "[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5);
        assertThrows(Json.JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void acceptsInputAtTheNestingCap() {
        String atLimit = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        Object parsed = Json.parse(atLimit);
        assertTrue(parsed instanceof List, "the cap is a ceiling, not an off-by-one exclusion");
    }

    @Test
    void rejectsInputLongerThanTheSizeCap() {
        String huge = "\"" + "a".repeat(Json.MAX_INPUT) + "\"";
        assertThrows(Json.JsonException.class, () -> Json.parse(huge));
    }

    @Test
    void stringHelperFallsBackWhenTheValueIsAbsentOrNotAString() {
        Map<String, Object> parsed = Json.parseObject("{\"a\":\"x\",\"b\":5}");
        assertEquals("x", Json.string(parsed, "a", "fallback"));
        assertEquals("fallback", Json.string(parsed, "missing", "fallback"));
        assertEquals("fallback", Json.string(parsed, "b", "fallback"),
            "a number where a string belongs is not a string with a different shape");
    }
}
