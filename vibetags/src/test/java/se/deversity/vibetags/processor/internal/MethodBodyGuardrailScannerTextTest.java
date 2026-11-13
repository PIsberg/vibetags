package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two text checks the early exit relies on without the Tree API (#1062). A false match here
 * vouches for a value the sources do not determine, so each case below is the near miss that would.
 */
class MethodBodyGuardrailScannerTextTest {

    @Test
    void aNumberIsFoundOnlyAsAWholeToken() {
        assertTrue(MethodBodyGuardrailScanner.contains("coverageGoal = 90)", "90", true));
        assertFalse(MethodBodyGuardrailScanner.contains("coverageGoal = 90)", "9", true),
            "9 is part of 90, not a literal 9");
        assertFalse(MethodBodyGuardrailScanner.contains("boolean trueValue;", "true", true),
            "true inside an identifier is not the literal");
    }

    @Test
    void aStringIsMatchedAsItsJavaLiteral() {
        assertEquals("\"say \\\"hi\\\" C:\\\\tmp\\n\"", MethodBodyGuardrailScanner.javaLiteral("say \"hi\" C:\\tmp\n"));
        String source = "@AILocked(reason = \"ledger maths is \\\"audited\\\"\")";
        assertTrue(MethodBodyGuardrailScanner.contains(source,
            MethodBodyGuardrailScanner.javaLiteral("ledger maths is \"audited\""), false));
        assertFalse(MethodBodyGuardrailScanner.contains(source,
            MethodBodyGuardrailScanner.javaLiteral("ledger maths"), false),
            "a prefix of a literal is not that literal: the closing quote must match too");
    }
}
