package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for v1.0.0 annotations: @AIIdempotent, @AIFeatureFlag.
 *
 * Verifies that the annotations are correctly processed and produce expected prompt outputs
 * across the major platforms.
 */
class NewAnnotationsV5EndToEndTest {

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = new ProcessorTestHarness(tempDir);

        harness.addSource("com.example.idempotent.PaymentHandler",
            "package com.example.idempotent;\n"
                + "import se.deversity.vibetags.annotations.AIIdempotent;\n"
                + "public class PaymentHandler {\n"
                + "  @AIIdempotent(reason = \"Deduplication key ensures re-sends are safe.\")\n"
                + "  public void processPayment(String deduplicationKey) {}\n"
                + "}\n");

        harness.addSource("com.example.feature.BetaCheckout",
            "package com.example.feature;\n"
                + "import se.deversity.vibetags.annotations.AIFeatureFlag;\n"
                + "@AIFeatureFlag(flag = \"checkout.beta.enabled\", defaultValue = false)\n"
                + "public class BetaCheckout {}\n");

        harness.compile();
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    // --- @AIIdempotent ---

    @Test
    void idempotent_cursorRulesContainsSection() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("IDEMPOTENCY"), "Cursorrules must contain idempotency section header");
        assertTrue(rules.contains("com.example.idempotent.PaymentHandler.processPayment"), "Cursorrules must list the annotated method");
    }

    @Test
    void idempotent_cursorRulesContainsReason() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("Deduplication key ensures re-sends are safe."), "Cursorrules must include the idempotency reason");
    }

    @Test
    void idempotent_claudeMdContainsIdempotentElements() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("idempotent_elements"), "CLAUDE.md must contain idempotent_elements section");
        assertTrue(claude.contains("com.example.idempotent.PaymentHandler.processPayment"), "CLAUDE.md must list the annotated method");
    }

    @Test
    void idempotent_agentsMdContainsSection() throws IOException {
        String agents = harness.readFile("AGENTS.md");
        assertTrue(agents.contains("IDEMPOTENCY"), "AGENTS.md must contain idempotency section");
        assertTrue(agents.contains("com.example.idempotent.PaymentHandler.processPayment"), "AGENTS.md must list the annotated method");
    }

    // --- @AIFeatureFlag ---

    @Test
    void featureFlag_cursorRulesContainsSection() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("FEATURE FLAG"), "Cursorrules must contain feature flag section header");
        assertTrue(rules.contains("com.example.feature.BetaCheckout"), "Cursorrules must list the feature-flag-gated class");
    }

    @Test
    void featureFlag_cursorRulesContainsFlagKey() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("checkout.beta.enabled"), "Cursorrules must include the flag key");
    }

    @Test
    void featureFlag_claudeMdContainsFeatureFlagElements() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("feature_flag_elements"), "CLAUDE.md must contain feature_flag_elements section");
        assertTrue(claude.contains("com.example.feature.BetaCheckout"), "CLAUDE.md must list the feature-flag-gated class");
    }
}
