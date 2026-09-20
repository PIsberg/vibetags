package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A declared file prologue is hoisted only when every region actually opens with it.
 *
 * <p>{@code TESTING.md}'s two-line preamble describes the file, not any one module, so a reactor
 * writes it once above the regions instead of once inside each (#783). The hoist works by cutting
 * that prefix off every body, which is safe exactly as long as every body has it.
 *
 * <p>The case that makes the guard necessary is a mixed-version reactor: a module whose sidecar was
 * written by an older processor, before the preamble existed or with different wording. Cutting a
 * fixed number of characters off a body that never carried them would eat the first line of that
 * module's actual guardrails, and the merge has no way to notice afterwards.
 */
class SharedPrologueTest {

    private static final String TESTING = "testing";
    private static final String PREAMBLE =
        "These guardrails apply to test code. Read them before changing a test, a fixture or a test helper.\n"
            + "Safety guardrails on test code (locked, core, privacy, ignore, audit, secure) are not repeated"
            + " here: they stay in the always-loaded instruction files.";

    @Test
    @DisplayName("hoisted when every body opens with it")
    void hoistedWhenSharedByAll() {
        String shared = ModuleSidecar.sharedPrologue(TESTING,
            List.of(PREAMBLE + "\n\n## CONTEXTUAL RULES\n- a", PREAMBLE + "\n\n## CONTEXTUAL RULES\n- b"));

        assertEquals(PREAMBLE, shared, "both regions carry it, so it belongs at the top of the file");
    }

    @Test
    @DisplayName("not hoisted when one body does not carry it")
    void notHoistedWhenOneBodyLacksIt() {
        String shared = ModuleSidecar.sharedPrologue(TESTING,
            List.of(PREAMBLE + "\n\n## CONTEXTUAL RULES\n- a", "## CONTEXTUAL RULES\n- written by an older build"));

        assertEquals("", shared,
            "cutting the prefix off a body that never had it would eat that module's first rule");
    }

    @Test
    @DisplayName("a service whose renderer declares no prologue hoists nothing")
    void noPrologueDeclared() {
        assertEquals("", ModuleSidecar.sharedPrologue("claude", List.of("anything", "at all")),
            "only TESTING.md declares one; every other aggregate keeps the stacking it had");
    }

    @Test
    @DisplayName("no bodies, nothing to hoist")
    void noBodies() {
        assertEquals("", ModuleSidecar.sharedPrologue(TESTING, List.of()));
    }
}
