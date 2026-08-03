package se.deversity.vibetags.processor.internal.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The unchecked-layering note must name a cause. Gradle's isolated compiler workers fail with
 * throwables whose {@code getMessage()} is null, and the note rendered as
 * "Trees API not available for AST architectural import scanning: null" — seen live in the
 * skill3 consumer build (JDK 25 toolchain worker) during the RC10 GA sweep. A diagnostic whose
 * reason is "null" is the log line people need and the one that was missing.
 */
class ArchitectureRuleReasonTest {

    @Test
    void reasonKeepsARealMessage() {
        assertEquals("boom", ArchitectureRule.unavailableReason(new IllegalStateException("boom")));
    }

    @Test
    void nullMessageFallsBackToTheTypeName() {
        assertEquals("IllegalStateException",
            ArchitectureRule.unavailableReason(new IllegalStateException((String) null)));
    }

    @Test
    void blankMessageFallsBackToTheTypeName() {
        assertEquals("NoClassDefFoundError",
            ArchitectureRule.unavailableReason(new NoClassDefFoundError(" ")));
    }
}
