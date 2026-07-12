package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.Escape;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ellipsis.yaml} writes each guardrail as a YAML double-quoted scalar — the same shape
 * {@code sweep.yaml} and {@code .plandex.yaml} use. Those two escape with {@link Escape#json},
 * which is correct for the job (YAML 1.2 is a superset of JSON, so its double-quoted scalar
 * accepts the JSON escapes), but Ellipsis carried a private copy that only handled {@code \} and
 * {@code "}.
 *
 * <p>A control character in an annotation value therefore survived into the file raw, and a raw
 * control character is not legal in a YAML double-quoted scalar — the config fails to parse, and
 * Ellipsis silently stops reviewing against the guardrails.
 */
class EllipsisEscapingTest {

    @Test
    void jsonEscapeCoversWhatAYamlDoubleQuotedScalarNeeds() {
        String hostile = "reasonwith\tcontrol \"quote\" and \\ backslash";
        String escaped = Escape.json(hostile);

        assertFalse(escaped.contains(""), "raw control characters are illegal in a YAML scalar: " + escaped);
        assertTrue(escaped.contains("\\u0001"), "control characters must be escaped: " + escaped);
        assertTrue(escaped.contains("\\t"), "tabs must be escaped: " + escaped);
        assertTrue(escaped.contains("\\\""), "quotes must be escaped or the scalar closes early: " + escaped);
        assertTrue(escaped.contains("\\\\"), "backslashes must be escaped: " + escaped);
    }

    /** The old private escaper's exact behaviour, kept here to document what regressed. */
    @Test
    void theOldPrivateEscaperLeftControlCharactersRaw() {
        String hostile = "ab";
        String legacy = hostile.replace("\\", "\\\\").replace("\"", "\\\"");

        assertTrue(legacy.contains(""),
            "documents the defect: the private escaper passed control characters through untouched");
        assertFalse(Escape.json(hostile).contains(""),
            "the shared escaper does not");
    }
}
