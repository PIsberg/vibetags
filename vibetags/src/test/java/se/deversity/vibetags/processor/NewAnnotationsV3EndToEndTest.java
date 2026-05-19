package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the v0.9.5 annotations: @AIThreadSafe, @AIImmutable, @AIDeprecated,
 * @AIObservability, @AIRegulation. Verifies that each annotation produces sections in the
 * generated guardrail files for the major platforms.
 */
class NewAnnotationsV3EndToEndTest {

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = new ProcessorTestHarness(tempDir);

        harness.addSource("com.example.cache.SessionCache",
            "package com.example.cache;\n"
                + "import se.deversity.vibetags.annotations.AIThreadSafe;\n"
                + "@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = \"backed by ConcurrentHashMap\")\n"
                + "public class SessionCache {}\n");

        harness.addSource("com.example.config.AsyncTestConfig",
            "package com.example.config;\n"
                + "import se.deversity.vibetags.annotations.AIImmutable;\n"
                + "@AIImmutable(note = \"Used by every test runner; must remain unchangeable.\")\n"
                + "public final class AsyncTestConfig {\n"
                + "    private final int timeoutMs;\n"
                + "    public AsyncTestConfig(int timeoutMs) { this.timeoutMs = timeoutMs; }\n"
                + "    public int getTimeoutMs() { return timeoutMs; }\n"
                + "}\n");

        harness.addSource("com.example.legacy.OldPaymentApi",
            "package com.example.legacy;\n"
                + "import se.deversity.vibetags.annotations.AIDeprecated;\n"
                + "@AIDeprecated(replacedBy = \"com.example.payment.PaymentProcessor\","
                + " migrationGuide = \"Switch callers to PaymentProcessor.charge()\","
                + " deadline = \"2.0\")\n"
                + "public class OldPaymentApi {}\n");

        harness.addSource("com.example.metrics.OrderMetrics",
            "package com.example.metrics;\n"
                + "import se.deversity.vibetags.annotations.AIObservability;\n"
                + "public class OrderMetrics {\n"
                + "    @AIObservability(metrics = {\"orders.placed\", \"orders.failed\"},"
                + " traces = {\"order.place\"}, logs = {\"OrderPlaced\"},"
                + " note = \"Watched by the orders SLO dashboard.\")\n"
                + "    public void placeOrder() {}\n"
                + "}\n");

        harness.addSource("com.example.compliance.GdprService",
            "package com.example.compliance;\n"
                + "import se.deversity.vibetags.annotations.AIRegulation;\n"
                + "@AIRegulation(standard = \"GDPR\", clause = \"Art. 17\","
                + " description = \"Right to erasure — deletes all PII for a given user.\")\n"
                + "public class GdprService {\n"
                + "    public void deleteUser(String userId) {}\n"
                + "}\n");

        harness.compile();
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    // --- @AIThreadSafe ---

    @Test
    void cursorRules_containsThreadSafeSection() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("THREAD-SAFE BY DESIGN"),
            ".cursorrules must include the THREAD-SAFE section header");
        assertTrue(content.contains("com.example.cache.SessionCache"),
            ".cursorrules must list the SessionCache element");
        assertTrue(content.contains("LOCK_FREE"),
            ".cursorrules must include the strategy name");
    }

    @Test
    void claudeMd_containsThreadSafeSection() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("<thread_safe_elements>"),
            "CLAUDE.md must contain <thread_safe_elements>");
        assertTrue(content.contains("</thread_safe_elements>"),
            "CLAUDE.md must close <thread_safe_elements>");
        assertTrue(content.contains("<strategy>LOCK_FREE</strategy>"),
            "CLAUDE.md must include the strategy");
    }

    @Test
    void llmsTxt_containsThreadSafeSection() throws IOException {
        String content = harness.readFile("llms.txt");
        assertTrue(content.contains("Thread-Safe by Design"),
            "llms.txt must include the Thread-Safe by Design section");
        assertTrue(content.contains("com.example.cache.SessionCache"),
            "llms.txt must list the SessionCache element");
    }

    // --- @AIImmutable ---

    @Test
    void cursorRules_containsImmutableSection() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("IMMUTABLE TYPES"),
            ".cursorrules must include the IMMUTABLE section header");
        assertTrue(content.contains("com.example.config.AsyncTestConfig"),
            ".cursorrules must list the AsyncTestConfig type");
    }

    @Test
    void claudeMd_containsImmutableSection() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("<immutable_types>"),
            "CLAUDE.md must contain <immutable_types>");
        assertTrue(content.contains("</immutable_types>"),
            "CLAUDE.md must close <immutable_types>");
    }

    // --- @AIDeprecated ---

    @Test
    void cursorRules_containsDeprecatedSection() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("DEPRECATED"),
            ".cursorrules must include the DEPRECATED section header");
        assertTrue(content.contains("com.example.legacy.OldPaymentApi"),
            ".cursorrules must list the OldPaymentApi element");
        assertTrue(content.contains("com.example.payment.PaymentProcessor"),
            ".cursorrules must include the replacement target");
        assertTrue(content.contains("2.0"),
            ".cursorrules must include the removal deadline");
    }

    @Test
    void claudeMd_containsDeprecatedSection() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("<deprecated_elements>"),
            "CLAUDE.md must contain <deprecated_elements>");
        assertTrue(content.contains("<replaced_by>com.example.payment.PaymentProcessor</replaced_by>"),
            "CLAUDE.md must include the replaced_by element");
        assertTrue(content.contains("<deadline>2.0</deadline>"),
            "CLAUDE.md must include the deadline element");
    }

    @Test
    void llmsFullTxt_containsDeprecatedDetails() throws IOException {
        String content = harness.readFile("llms-full.txt");
        assertTrue(content.contains("Replaced by"),
            "llms-full.txt must include the replacement label");
        assertTrue(content.contains("Migration"),
            "llms-full.txt must include the migration guidance");
        assertTrue(content.contains("Deadline"),
            "llms-full.txt must include the deadline label");
    }

    // --- @AIObservability ---

    @Test
    void cursorRules_containsObservabilitySection() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("OBSERVABILITY"),
            ".cursorrules must include the OBSERVABILITY section header");
        assertTrue(content.contains("orders.placed"),
            ".cursorrules must include metric names");
        assertTrue(content.contains("order.place"),
            ".cursorrules must include trace span names");
    }

    @Test
    void claudeMd_containsObservabilitySection() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("<observability_instrumentation>"),
            "CLAUDE.md must contain <observability_instrumentation>");
        assertTrue(content.contains("<metric>orders.placed</metric>"),
            "CLAUDE.md must include each metric tag");
        assertTrue(content.contains("<trace>order.place</trace>"),
            "CLAUDE.md must include each trace tag");
        assertTrue(content.contains("<log>OrderPlaced</log>"),
            "CLAUDE.md must include each log tag");
    }

    // --- @AIRegulation ---

    @Test
    void cursorRules_containsRegulationSection() throws IOException {
        String content = harness.readFile(".cursorrules");
        assertTrue(content.contains("REGULATORY COMPLIANCE"),
            ".cursorrules must include the REGULATORY COMPLIANCE section header");
        assertTrue(content.contains("GDPR"),
            ".cursorrules must include the standard name");
        assertTrue(content.contains("Art. 17"),
            ".cursorrules must include the clause");
    }

    @Test
    void claudeMd_containsRegulationSection() throws IOException {
        String content = harness.readFile("CLAUDE.md");
        assertTrue(content.contains("<regulatory_elements>"),
            "CLAUDE.md must contain <regulatory_elements>");
        assertTrue(content.contains("<standard>GDPR</standard>"),
            "CLAUDE.md must include the standard tag");
        assertTrue(content.contains("<clause>Art. 17</clause>"),
            "CLAUDE.md must include the clause tag");
    }

    @Test
    void llmsFullTxt_containsRegulationDetails() throws IOException {
        String content = harness.readFile("llms-full.txt");
        assertTrue(content.contains("Standard"),
            "llms-full.txt must include the Standard label");
        assertTrue(content.contains("Clause"),
            "llms-full.txt must include the Clause label");
        assertTrue(content.contains("GDPR"),
            "llms-full.txt must include the standard name");
    }

    // --- Cross-platform sanity ---

    @Test
    void copilot_containsAllNewSections() throws IOException {
        String content = harness.readFile(".github/copilot-instructions.md");
        assertTrue(content.contains("Thread-Safe by Design"));
        assertTrue(content.contains("Immutable Types"));
        assertTrue(content.contains("Deprecated Elements"));
        assertTrue(content.contains("Observability Instrumentation"));
        assertTrue(content.contains("Regulatory Compliance"));
    }

    @Test
    void qwenMd_containsAllNewSections() throws IOException {
        String content = harness.readFile("QWEN.md");
        assertTrue(content.contains("THREAD-SAFE"));
        assertTrue(content.contains("IMMUTABLE"));
        assertTrue(content.contains("DEPRECATED"));
        assertTrue(content.contains("OBSERVABILITY"));
        assertTrue(content.contains("REGULATORY"));
    }

    @Test
    void aiderConventions_containsAllNewSections() throws IOException {
        String content = harness.readFile("CONVENTIONS.md");
        assertTrue(content.contains("THREAD-SAFE"));
        assertTrue(content.contains("IMMUTABLE"));
        assertTrue(content.contains("DEPRECATED"));
        assertTrue(content.contains("OBSERVABILITY"));
        assertTrue(content.contains("REGULATORY"));
    }
}
