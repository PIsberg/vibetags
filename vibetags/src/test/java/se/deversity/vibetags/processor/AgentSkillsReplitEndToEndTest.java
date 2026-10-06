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
 * End-to-end tests for the cross-client Agent Skills location and Replit Agent's {@code replit.md}.
 * Zencoder's scoped rules were tested here too until 1.4.0 removed them (#720).
 */
@Tag("e2e")
class AgentSkillsReplitEndToEndTest {

    private static final String AGENTS_SKILL = ".agents/skills/vibetags-guardrails/SKILL.md";
    private static final String CLAUDE_SKILL = ".claude/skills/vibetags-guardrails/SKILL.md";

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
    void bothAreWritten() {
        assertTrue(harness.fileExists(AGENTS_SKILL), AGENTS_SKILL + " must be written");
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
