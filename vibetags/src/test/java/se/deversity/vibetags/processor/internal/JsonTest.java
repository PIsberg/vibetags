package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

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
        String awkward = "line1\nline2\t\"quoted\" \\ back \u0001 control å";
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

    @Test
    void rejectsTrailingContent() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{} {}"),
            "two documents in one file is corruption, not a document to read the first half of");
    }

    @Test
    void rejectsUnterminatedString() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":\"unclosed"));
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
