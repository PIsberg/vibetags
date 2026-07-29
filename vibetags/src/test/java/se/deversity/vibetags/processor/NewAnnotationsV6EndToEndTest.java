package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end generated content for the v1.0.0 evidence-based wave, across the aggregate files, the
 * granular rule files, and llms.txt.
 *
 * <p>Assertions lean on the <em>wording</em> each annotation exists to produce, not just on the
 * element name appearing somewhere: the whole point of `@AIGenerated` is that it redirects instead
 * of refusing, and of `@AIThreadAffinity` that it tells an agent NOT to reach for a lock. A test
 * that only checked the class name would pass on output that lost both.
 */
class NewAnnotationsV6EndToEndTest {

    private static final String SOURCE = """
        package com.example.v6;

        import se.deversity.vibetags.annotations.AIBannedApi;
        import se.deversity.vibetags.annotations.AIGenerated;
        import se.deversity.vibetags.annotations.AIKeepInSync;
        import se.deversity.vibetags.annotations.AILoadBearing;
        import se.deversity.vibetags.annotations.AIThreadAffinity;

        @AIGenerated(from = "src/main/resources/openapi/orders.yaml",
                     regenerateWith = "mvn generate-sources",
                     editInstead = "src/main/resources/openapi/orders.yaml")
        public class OrdersApiStub {

            @AILoadBearing(invariant = "The retained list is never cleared while the source is live",
                           breaksIf = "Clearing it reintroduces a use-after-free crash under load",
                           suppressAudit = true)
            private String retained = "";

            @AIBannedApi(forbidden = {"java.lang.System.out", "java.util.Date"},
                         useInstead = "the injected org.slf4j.Logger and java.time.Instant",
                         reason = "Console output bypasses structured logging")
            public void log() { }

            @AIThreadAffinity(value = AIThreadAffinity.Affinity.NAMED,
                              thread = "Swing EDT",
                              marshalVia = "SwingUtilities.invokeLater",
                              symptomIfViolated = "Silent repaint corruption; no exception on most JDKs")
            public void refreshTable() { }

            @AIKeepInSync(mirrors = {"pom.xml:<version>", "README.md badge"},
                          reason = "The release version is asserted in three places",
                          enforcedBy = "ProjectFactsConsistencyTest")
            public static final String VERSION = "1.0.0";
        }
        """;

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        // Default opt-ins only: the four granular dirs that have an aggregate sibling are
        // deliberately absent, so CLAUDE.md/.cursorrules render in full rather than collapsing to a
        // scoped-rules index. Granular output is asserted through .ai/rules, which has no aggregate.
        harness = new ProcessorTestHarness(tempDir);
        harness.addSource("com.example.v6.OrdersApiStub", SOURCE);
        harness.compile();
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    // ------------------------------------------------------------------
    // CLAUDE.md — bespoke XML blocks
    // ------------------------------------------------------------------

    @Test
    void claudeMd_carriesEveryNewBlockWithItsRule() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        for (String block : new String[]{
                "generated_elements", "load_bearing_elements", "banned_apis",
                "thread_affinity_elements", "mirrored_elements"}) {
            assertTrue(claude.contains("<" + block + ">") && claude.contains("</" + block + ">"),
                "CLAUDE.md must contain the <" + block + "> block:\n" + claude);
        }
    }

    @Test
    void claudeMd_generatedStatesTheRedirect_notJustAProhibition() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<from>src/main/resources/openapi/orders.yaml</from>"),
            "the true source must be named: " + claude);
        assertTrue(claude.contains("<regenerate-with>mvn generate-sources</regenerate-with>"),
            "the regeneration command must survive: " + claude);
        assertTrue(claude.contains("never edit them") && claude.contains("regenerate"),
            "the rule must redirect, not merely forbid: " + claude);
    }

    @Test
    void claudeMd_threadAffinityWarnsAgainstAddingLocks() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("Swing EDT"), "the pinned thread must be named: " + claude);
        assertTrue(claude.contains("Never add locks") || claude.contains("never add locks"),
            "the rule must say adding a lock is the wrong fix — that is the failure this tag prevents:\n" + claude);
    }

    @Test
    void claudeMd_keepInSyncListsEveryMirror() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("pom.xml:&lt;version&gt;") || claude.contains("pom.xml:<version>"),
            "the first mirror must be listed (XML-escaped): " + claude);
        assertTrue(claude.contains("README.md badge"), "every mirror must be listed: " + claude);
        assertTrue(claude.contains("ProjectFactsConsistencyTest"),
            "the enforcing check must be named: " + claude);
    }

    @Test
    void claudeMd_loadBearingCarriesInvariantAndFailureMode() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("<invariant>"), claude);
        assertTrue(claude.contains("use-after-free"), "the concrete failure must survive: " + claude);
        assertTrue(claude.contains("<suppress-audit>true</suppress-audit>"), claude);
    }

    @Test
    void claudeMd_bannedApiCarriesBothTheBanAndTheRoute() throws IOException {
        String claude = harness.readFile("CLAUDE.md");
        assertTrue(claude.contains("java.lang.System.out, java.util.Date"), claude);
        assertTrue(claude.contains("org.slf4j.Logger"), "the replacement must survive: " + claude);
    }

    // ------------------------------------------------------------------
    // Markdown aggregates
    // ------------------------------------------------------------------

    @Test
    void cursorRules_carriesAllFiveSections() throws IOException {
        String cursor = harness.readFile(".cursorrules");
        assertTrue(cursor.contains("GENERATED CODE"), cursor);
        assertTrue(cursor.contains("LOAD-BEARING"), cursor);
        assertTrue(cursor.contains("BANNED APIs"), cursor);
        assertTrue(cursor.contains("THREAD AFFINITY"), cursor);
        assertTrue(cursor.contains("MIRRORED"), cursor);
    }

    @Test
    void llmsFull_explainsWhyAffinityIsNotThreadSafety() throws IOException {
        String llms = harness.readFile("llms-full.txt");
        assertTrue(llms.contains("inverse of thread-safety"),
            "llms-full.txt must state the distinction explicitly: " + llms);
        assertTrue(llms.contains("do not lock it"),
            "llms-full.txt must name the wrong fix: " + llms);
    }

    @Test
    void llmsFull_flagsAnUnenforcedMirrorSetAsSuch() throws IOException {
        String llms = harness.readFile("llms-full.txt");
        assertTrue(llms.contains("ProjectFactsConsistencyTest"),
            "an enforced mirror set names its check: " + llms);
    }

    // ------------------------------------------------------------------
    // Granular rules
    // ------------------------------------------------------------------

    @Test
    void granularRules_carryEveryNewSection() throws IOException {
        String rules = Files.readString(tempDir.resolve(".ai/rules/com-example-v6-OrdersApiStub.md"));
        // The type-level annotation keeps its section title; member-level ones are filed under
        // "### Rules for <kind> <name>" instead, so assert on the rule bodies rather than headings.
        assertTrue(rules.contains("Generated — Edit The Source"), rules);
        assertTrue(rules.contains("### Rules for field retained"), rules);
        assertTrue(rules.contains("### Rules for method log"), rules);
        assertTrue(rules.contains("### Rules for method refreshTable"), rules);
        assertTrue(rules.contains("### Rules for field VERSION"), rules);

        assertTrue(rules.contains("**Generated from**: src/main/resources/openapi/orders.yaml"), rules);
        assertTrue(rules.contains("**Regenerate with**: mvn generate-sources"), rules);
        assertTrue(rules.contains("**Invariant**: The retained list is never cleared"), rules);
        assertTrue(rules.contains("**Audit**: Not a defect"), rules);
        assertTrue(rules.contains("**Forbidden**: java.lang.System.out, java.util.Date"), rules);
        assertTrue(rules.contains("**Marshal via**: SwingUtilities.invokeLater"), rules);
        assertTrue(rules.contains("never add locks") || rules.contains("never add locks to"),
            "the granular thread-affinity rule must name the wrong fix: " + rules);
        assertTrue(rules.contains("**Mirrors**: pom.xml:<version>, README.md badge"), rules);
        assertTrue(rules.contains("**Enforced by**: ProjectFactsConsistencyTest"), rules);
    }
}
