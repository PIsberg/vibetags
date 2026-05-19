package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for the 9 new V4 annotations:
 * @AIParallelTests, @AILegacyBridge, @AIArchitecture, @AIPublicAPI, @AIStrictExceptions,
 * @AIStrictTypes, @AIInternationalized, @AIStrictClasspath, @AISchemaSafe.
 *
 * Verifies that each new annotation is correctly processed and produces expected prompt
 * outputs across the major platforms.
 */
class NewAnnotationsV4EndToEndTest {

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = new ProcessorTestHarness(tempDir);

        harness.addSource("com.example.parallel.ConcurrentRunner",
            "package com.example.parallel;\n"
                + "import se.deversity.vibetags.annotations.AIParallelTests;\n"
                + "@AIParallelTests\n"
                + "public class ConcurrentRunner {}\n");

        harness.addSource("com.example.legacy.BizarreBugFixBridge",
            "package com.example.legacy;\n"
                + "import se.deversity.vibetags.annotations.AILegacyBridge;\n"
                + "@AILegacyBridge\n"
                + "public class BizarreBugFixBridge {}\n");

        harness.addSource("com.example.domain.InvoiceEntity",
            "package com.example.domain;\n"
                + "import se.deversity.vibetags.annotations.AIArchitecture;\n"
                + "@AIArchitecture(belongsTo = \"domain\", cannotReference = {\"web\", \"database\"})\n"
                + "public class InvoiceEntity {}\n");

        harness.addSource("com.example.api.PublicBillingApi",
            "package com.example.api;\n"
                + "import se.deversity.vibetags.annotations.AIPublicAPI;\n"
                + "@AIPublicAPI\n"
                + "public class PublicBillingApi {}\n");

        harness.addSource("com.example.exception.StrictDatabaseException",
            "package com.example.exception;\n"
                + "import se.deversity.vibetags.annotations.AIStrictExceptions;\n"
                + "@AIStrictExceptions\n"
                + "public class StrictDatabaseException {}\n");

        harness.addSource("com.example.types.StrongBillingRecord",
            "package com.example.types;\n"
                + "import se.deversity.vibetags.annotations.AIStrictTypes;\n"
                + "@AIStrictTypes\n"
                + "public class StrongBillingRecord {}\n");

        harness.addSource("com.example.i18n.LocalizedMessageProvider",
            "package com.example.i18n;\n"
                + "import se.deversity.vibetags.annotations.AIInternationalized;\n"
                + "@AIInternationalized\n"
                + "public class LocalizedMessageProvider {}\n");

        harness.addSource("com.example.classpath.IsolatedPluginLoader",
            "package com.example.classpath;\n"
                + "import se.deversity.vibetags.annotations.AIStrictClasspath;\n"
                + "@AIStrictClasspath\n"
                + "public class IsolatedPluginLoader {}\n");

        harness.addSource("com.example.schema.SerializedOrderState",
            "package com.example.schema;\n"
                + "import se.deversity.vibetags.annotations.AISchemaSafe;\n"
                + "@AISchemaSafe\n"
                + "public class SerializedOrderState {}\n");

        harness.compile();
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    // --- @AIParallelTests ---
    @Test
    void parallelTests_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("STRICT TEST ISOLATION"), "Cursorrules must contain parallel tests section");
        assertTrue(rules.contains("com.example.parallel.ConcurrentRunner"), "Cursorrules must list the ConcurrentRunner class");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<test_isolation_elements>"), "Claude.md must contain test isolation elements tag");
        assertTrue(claude.contains("com.example.parallel.ConcurrentRunner"), "Claude.md must list ConcurrentRunner");

        String llms = harness.readFile("llms.txt");
        assertTrue(llms.contains("Strict Test Isolation"), "llms.txt must contain test isolation section");
    }

    // --- @AILegacyBridge ---
    @Test
    void legacyBridge_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("LEGACY COMPATIBILITY BRIDGE"), "Cursorrules must contain legacy bridge section");
        assertTrue(rules.contains("com.example.legacy.BizarreBugFixBridge"), "Cursorrules must list BizarreBugFixBridge");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<legacy_bridge_elements>"), "Claude.md must contain legacy bridge tag");

        String llms = harness.readFile("llms.txt");
        assertTrue(llms.contains("Legacy Compatibility Bridge"), "llms.txt must contain legacy bridge section");
    }

    // --- @AIArchitecture ---
    @Test
    void architecture_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("ARCHITECTURAL BOUNDARY CONSTRAINTS"), "Cursorrules must contain architecture section");
        assertTrue(rules.contains("com.example.domain.InvoiceEntity"), "Cursorrules must list InvoiceEntity");
        assertTrue(rules.contains("Belongs to layer: `domain`"), "Cursorrules must mention domain layer");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<architecture_elements>"), "Claude.md must contain architectural layers tag");
        assertTrue(claude.contains("<belongs_to>domain</belongs_to>"), "Claude.md must list belongs_to");

        String conventions = harness.readFile("CONVENTIONS.md");
        assertTrue(conventions.contains("ARCHITECTURE LAYER: com.example.domain.InvoiceEntity"), "CONVENTIONS.md must list domain layer info");
    }

    // --- @AIPublicAPI ---
    @Test
    void publicApi_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("PUBLIC API SURFACE PROTECTION"), "Cursorrules must contain public API section");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<public_api_elements>"), "Claude.md must contain public API tag");
    }

    // --- @AIStrictExceptions ---
    @Test
    void strictExceptions_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("STRICT EXCEPTION HANDLING"), "Cursorrules must contain strict exception section");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<strict_exceptions_elements>"), "Claude.md must contain strict exception tag");
    }

    // --- @AIStrictTypes ---
    @Test
    void strictTypes_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("STRICT TYPE SAFETY"), "Cursorrules must contain strict types section");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<strict_types_elements>"), "Claude.md must contain strict types tag");
    }

    // --- @AIInternationalized ---
    @Test
    void internationalized_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("INTERNATIONALIZATION"), "Cursorrules must contain i18n section");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<internationalized_elements>"), "Claude.md must contain i18n tag");
    }

    // --- @AIStrictClasspath ---
    @Test
    void strictClasspath_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("STRICT CLASSPATH INTEGRITY"), "Cursorrules must contain classpath section");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<strict_classpath_elements>"), "Claude.md must contain classpath tag");
    }

    // --- @AISchemaSafe ---
    @Test
    void schemaSafe_isGenerated() throws IOException {
        String rules = harness.readFile(".cursorrules");
        assertTrue(rules.contains("SCHEMA \u0026 SERIALIZATION SAFETY"), "Cursorrules must contain schema safe section");

        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<schema_safe_elements>"), "Claude.md must contain schema safe tag");
    }
}
