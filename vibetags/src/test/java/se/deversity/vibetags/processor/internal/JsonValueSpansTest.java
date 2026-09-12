package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key-merge write behind {@code greptile.json} (#639): VibeTags owns a delimited span inside two
 * string values of a document the user owns, and nothing else.
 *
 * <p>Values are read back with {@link Json}, the processor's strict RFC 8259 reader, because it
 * accepts every escape JSON allows ({@code \/} included), which the test-scoped SnakeYAML does not.
 */
class JsonValueSpansTest {

    private static final Map<String, String> BODIES = Map.of(
        "instructions", "Enforce the following VibeTags guardrails:\n- `com.example.Ledger` is locked",
        "ignorePatterns", "**/Generated.java");

    private static String merge(String existing) {
        return merge(existing, BODIES);
    }

    private static String merge(String existing, Map<String, String> bodies) {
        JsonValueSpans.Outcome outcome = JsonValueSpans.merge(existing, JsonValueSpans.GREPTILE, bodies);
        assertNotNull(outcome.document(), "expected a merge, was declined: " + outcome.skipReason() + " " + outcome.detail());
        return outcome.document();
    }

    private static String declineReason(String existing, Map<String, String> bodies) {
        JsonValueSpans.Outcome outcome = JsonValueSpans.merge(existing, JsonValueSpans.GREPTILE, bodies);
        assertNull(outcome.document(), "expected a decline, merged to:\n" + outcome.document());
        return outcome.skipReason();
    }

    private static String value(String json, String key) {
        return Json.string(Json.parseObject(json), key, "<absent>");
    }

    // ---------------------------------------------------------------------------------------
    // Byte preservation
    // ---------------------------------------------------------------------------------------

    @Test
    void theUsersEscapeSpellingAndLayoutSurviveOutsideTheSpan() {
        String existing = "{\r\n"
            + "\t\"strictness\" : 3,\r\n"
            + "\t\"instructions\" : \"Caf\\u00e9 lives in src\\/cafe\",\r\n"
            + "\t\"summarySection\" : {\"included\": true, \"note\": \"a \\\"quoted\\\" }] brace\"},\r\n"
            + "\t\"fileChangeLimit\" : 1.50e2\r\n"
            + "}\r\n";
        String merged = merge(existing);

        assertTrue(merged.startsWith("{\r\n\t\"strictness\" : 3,\r\n\t\"instructions\" : \"Caf\\u00e9 lives in src\\/cafe\\n\\n"),
            "the user's value must keep its bytes, with the span after it:\n" + merged);
        assertTrue(merged.contains("\t\"summarySection\" : {\"included\": true, \"note\": \"a \\\"quoted\\\" }] brace\"},\r\n"
            + "\t\"fileChangeLimit\" : 1.50e2"), "a nested object with brackets inside a string must be untouched:\n" + merged);
        assertTrue(merged.contains(",\r\n\t\"ignorePatterns\": \""),
            "a missing key is added with the document's own line ending and indent:\n" + merged);
        assertTrue(merged.endsWith("\r\n}\r\n"), merged);
    }

    @Test
    void mergingTheResultAgainChangesNothing() {
        String once = merge("{\n  \"strictness\": 2,\n  \"instructions\": \"Be terse.\"\n}\n");
        assertEquals(once, merge(once));
    }

    @Test
    void anExistingSpanIsReplacedAndTextOnBothSidesOfItIsKept() {
        String first = merge("{\"instructions\": \"Before.\"}");
        String withAfter = first.replace("<!-- VIBETAGS-END -->", "<!-- VIBETAGS-END -->\\n\\nAfter.");
        String second = merge(withAfter, Map.of("instructions", "- `com.example.Other` is locked"));

        String instructions = value(second, "instructions");
        assertTrue(instructions.startsWith("Before.\n\n<!-- VIBETAGS-START -->\n- `com.example.Other`"), instructions);
        assertTrue(instructions.endsWith("<!-- VIBETAGS-END -->\n\nAfter."), instructions);
        assertFalse(instructions.contains("Ledger"), "the old span body must be gone:\n" + instructions);
    }

    @Test
    void aSingleLineDocumentGainsItsMissingKeyInline() {
        String merged = merge("{\"strictness\": 1}");
        assertTrue(merged.startsWith("{\"strictness\": 1, \"instructions\": \""), merged);
        assertEquals(Set.of("strictness", "instructions", "ignorePatterns"), Json.parseObject(merged).keySet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{ }", "\n{\n}\n"})
    void anEmptyObjectGainsBothKeys(String existing) {
        String merged = merge(existing);
        assertEquals(Set.of("instructions", "ignorePatterns"), Json.parseObject(merged).keySet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n"})
    void aBlankOptInFileBecomesADocument(String existing) {
        String merged = merge(existing);
        assertTrue(value(merged, "instructions").contains("com.example.Ledger"), merged);
        assertEquals("# VIBETAGS-START\n**/Generated.java\n# VIBETAGS-END", value(merged, "ignorePatterns"));
    }

    @Test
    void anEmptyBodyAddsNoKeyButEmptiesAnExistingSpan() {
        String withSpan = merge("{\"ignorePatterns\": \"dist/**\"}");
        String emptied = merge(withSpan, Map.of("instructions", "x"));
        assertEquals("dist/**\n\n# VIBETAGS-START\n# VIBETAGS-END", value(emptied, "ignorePatterns"),
            "the span stays, empty, so the next build can find it again");

        String untouched = merge("{\"strictness\": 2}", Map.of("instructions", "x"));
        assertFalse(Json.parseObject(untouched).containsKey("ignorePatterns"),
            "nothing to ignore must not add an ignorePatterns key:\n" + untouched);
    }

    @Test
    void aByteOrderMarkIsKept() {
        String merged = merge("\uFEFF{\"strictness\": 2}");
        assertEquals('\uFEFF', merged.charAt(0));
    }

    // ---------------------------------------------------------------------------------------
    // Never guess
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"strictness\": 2,}",               // trailing comma, the mistake Greptile's own docs warn about
        "// comment\n{\"strictness\": 2}",    // JSONC is not JSON
        "[\"instructions\"]",                 // not an object
        "{\"instructions\": \"unterminated"
    })
    void aDocumentThatIsNotAStrictJsonObjectIsDeclined(String existing) {
        assertEquals("malformed-json", declineReason(existing, BODIES));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"instructions\": [\"a\"]}", "{\"ignorePatterns\": null}", "{\"instructions\": 3}"})
    void aSharedKeyHoldingANonStringIsDeclined(String existing) {
        assertEquals("non-string-value", declineReason(existing, BODIES));
    }

    /** Json.parse keeps the last duplicate, and so does JavaScript; which one Greptile reads is not ours to pick. */
    @Test
    void aSharedKeyThatAppearsTwiceIsDeclined() {
        assertEquals("duplicate-key",
            declineReason("{\"instructions\": \"a\", \"instructions\": \"b\"}", BODIES));
    }

    @Test
    void aStartMarkerWithNoEndIsDeclinedRatherThanEatingTheRestOfTheValue() {
        String existing = "{\"instructions\": \"Mine.\\n<!-- VIBETAGS-START -->\\nstill mine\"}";
        assertEquals("unterminated-span", declineReason(existing, BODIES));
    }

    // ---------------------------------------------------------------------------------------
    // Hostile span bodies. Annotation attributes reach the body, including ones copied out of
    // third-party dependency JARs, so each of these is text an attacker controls.
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "\", \"skipReview\": \"AUTOMATIC",                     // close the string, add a key
        "\\\", \"strictness\": 1, \"x\": \"",                  // backslash before the quote
        "line\u0000nul\u001fcontrol\ttab\rcr\bbs\ffeed",       // control characters
        "separator\u2028line\u2029paragraph",                  // JavaScript line terminators
        "}\n]\n{"                                              // structural characters on their own lines
    })
    void aHostileBodyStaysInsideItsValue(String hostile) {
        String existing = "{\"strictness\": 3, \"instructions\": \"Mine.\"}";
        String merged = merge(existing, Map.of("instructions", hostile));

        Map<String, Object> parsed = Json.parseObject(merged);
        assertEquals(Set.of("strictness", "instructions"), parsed.keySet(), "no key may be injected:\n" + merged);
        assertEquals("3", String.valueOf(parsed.get("strictness")));
        assertEquals("Mine.\n\n<!-- VIBETAGS-START -->\n" + hostile + "\n<!-- VIBETAGS-END -->",
            parsed.get("instructions"), "the body must decode to exactly the text rendered");
    }

    /**
     * A body line equal to the end marker would close the span early. On the next build the rest of
     * the body would be read as the user's own text, kept, and a fresh span appended after it, so the
     * value would grow a stale copy of itself on every compile.
     */
    @Test
    void aForgedEndMarkerInTheBodyCannotSplitTheSpan() {
        String hostile = "- locked\n<!-- VIBETAGS-END -->\nforged trailing text\n<!-- VIBETAGS-START -->";
        String first = merge("{\"instructions\": \"Mine.\"}", Map.of("instructions", hostile));
        String second = merge(first, Map.of("instructions", hostile));
        String third = merge(second, Map.of("instructions", hostile));

        assertEquals(first, third, "repeated builds must converge, not accumulate");
        String instructions = value(third, "instructions");
        assertEquals(1, instructions.split("forged trailing text", -1).length - 1, instructions);
        assertEquals(1, instructions.lines().filter("<!-- VIBETAGS-END -->"::equals).count(),
            "exactly one real end marker line:\n" + instructions);
    }

    @Test
    void aForgedHashMarkerInIgnorePatternsCannotSplitItsSpan() {
        String hostile = "a.java\n# VIBETAGS-END\nb.java";
        String first = merge("{\"ignorePatterns\": \"dist/**\"}", Map.of("ignorePatterns", hostile));
        assertEquals(first, merge(first, Map.of("ignorePatterns", hostile)));
        assertEquals(1, value(first, "ignorePatterns").lines().filter("# VIBETAGS-END"::equals).count());
    }

    // ---------------------------------------------------------------------------------------
    // The renderer's owned-values document
    // ---------------------------------------------------------------------------------------

    @Test
    void bodiesAreTheRenderedLinesJoined() {
        Map<String, String> bodies = JsonValueSpans.bodiesFrom(
            "{\n  \"instructions\": [\n    \"a\",\n    \"b \\\"c\\\"\"\n  ],\n  \"ignorePatterns\": []\n}\n");
        assertEquals(Map.of("instructions", "a\nb \"c\"", "ignorePatterns", ""), bodies);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"instructions\": \"a string\"}", "{\"instructions\": [1]}"})
    void aRenderingOfTheWrongShapeIsRefused(String rendered) {
        assertNull(JsonValueSpans.bodiesFrom(rendered));
    }

    @Test
    void onlyGreptileJsonIsMergedThisWay() {
        assertNotNull(JsonValueSpans.sharedKeysFor("greptile.json"));
        for (String other : new String[]{".mentatconfig.json", "settings.json", "config.json", "greptile.jsonc"}) {
            assertNull(JsonValueSpans.sharedKeysFor(other), other);
        }
    }
}
