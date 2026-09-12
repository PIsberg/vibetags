package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the cross-client Agent Skills location, Zencoder's scoped rules, and
 * Replit Agent's {@code replit.md}.
 *
 * <p>The Zencoder front matter here is not inferred from prose. Zencoder's own Repo-Info Agent
 * writes {@code .zencoder/rules/repo.md} carrying {@code description} and {@code alwaysApply}, so
 * the shape is copied from the tool's output. The test pins {@code alwaysApply: true} specifically:
 * the alternative leaves loading to the model's discretion, and a locked-file guardrail that might
 * not load is worse than one that always does.
 */
@Tag("e2e")
class AgentSkillsZencoderReplitEndToEndTest {

    private static final String AGENTS_SKILL = ".agents/skills/vibetags-guardrails/SKILL.md";
    private static final String CLAUDE_SKILL = ".claude/skills/vibetags-guardrails/SKILL.md";
    private static final String ZENCODER_RULE = ".zencoder/rules/com-example-payment-PaymentProcessor.md";

    @TempDir
    static Path tempDir;

    private static ProcessorTestHarness harness;

    @BeforeAll
    static void setUp() throws IOException {
        harness = ProcessorTestHarness.withExampleSources(tempDir);
    }

    @AfterAll
    static void tearDown() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void allThreeAreWritten() {
        assertTrue(harness.fileExists(AGENTS_SKILL), AGENTS_SKILL + " must be written");
        assertTrue(harness.fileExists(ZENCODER_RULE), ZENCODER_RULE + " must be written");
        assertTrue(harness.fileExists("replit.md"), "replit.md must be written");
    }

    /**
     * The cross-client skill is the same artifact as the Claude one, at the path other clients
     * scan. If these ever diverge, one of the two is a second implementation that will drift.
     */
    @Test
    void theAgentsSkillIsByteIdenticalToTheClaudeSkill() throws IOException {
        assertEquals(harness.readFile(CLAUDE_SKILL), harness.readFile(AGENTS_SKILL),
            "the cross-client skill must be the same file the Claude skill is, at a different path");
    }

    @Test
    void theSkillCarriesTheFrontMatterThatMakesItDiscoverable() throws IOException {
        String content = harness.readFile(AGENTS_SKILL);
        assertTrue(content.startsWith("---\nname: vibetags-guardrails\n"),
            "Agent Skills require name front matter on the first line, was:\n" + content);
        assertTrue(content.contains("description:"), "Agent Skills require a description");
    }

    @Test
    void theZencoderRuleCarriesAlwaysApplyTrue() throws IOException {
        String content = harness.readFile(ZENCODER_RULE);
        assertTrue(content.contains("alwaysApply: true"),
            "a guardrail the model may decline to load is not a guardrail, was:\n" + content);
        assertTrue(content.contains("description:"),
            "Zencoder's own repo.md carries description front matter, was:\n" + content);
        assertTrue(content.contains("PaymentProcessor"),
            "the rule must carry its element's guardrails, was:\n" + content);
    }

    /**
     * Markdown markers are what make it safe for the Replit Agent to co-author this file: VibeTags
     * replaces only the region between them.
     */
    @Test
    void replitMdIsMarkerDelimitedSoTheAgentCanCoAuthorIt() throws IOException {
        String content = harness.readFile("replit.md");
        assertTrue(content.contains("<!-- VIBETAGS-START"), "replit.md must carry HTML-comment markers");
        assertTrue(content.contains("<!-- VIBETAGS-END"), "replit.md must carry a closing marker");
        assertTrue(content.contains("PaymentProcessor"), "replit.md must carry the locked element");
    }
}
