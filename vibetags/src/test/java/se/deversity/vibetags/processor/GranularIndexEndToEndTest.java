package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the scoped-rules index behavior: when an aggregate file (CLAUDE.md,
 * .cursorrules, …) is opted in ALONGSIDE its granular sibling, the aggregate collapses to a
 * locked+core+safety summary plus a scoped-rules index instead of duplicating every element's full
 * guardrails. Single opt-in (aggregate only) keeps the full output, byte-for-byte as before.
 */
@Tag("e2e")
class GranularIndexEndToEndTest {

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    /**
     * The index lists owners in a stable order regardless of the order they were collected in
     * (issue #325).
     *
     * <p>Owners reach {@code RenderingContext} in annotation-processing round order, which the JLS
     * does not constrain and which differs between Maven and Gradle. Two builds of identical
     * sources previously emitted the same {@code <element>} lines in a different sequence, so a
     * project kept in lockstep across both build systems got a spurious diff every alternate build.
     */
    @Test
    void granularOwnersAreOrderIndependent() {
        var preset = owner("se.deversity.asynctest.Preset");
        var site = owner("se.deversity.asynctest.diagnostics.SiteCapture.Site");
        var junitXml = owner("se.deversity.asynctest.report.JUnitXmlReportListener");

        java.util.List<String> collectedOneWay =
            new java.util.ArrayList<>(orderOf(preset, site, junitXml));
        java.util.List<String> collectedAnother =
            new java.util.ArrayList<>(orderOf(junitXml, preset, site));

        assertEquals(collectedOneWay, collectedAnother,
            "the scoped-rules index must not depend on annotation-processing round order");
        assertEquals(java.util.List.of(
                "se.deversity.asynctest.Preset",
                "se.deversity.asynctest.diagnostics.SiteCapture.Site",
                "se.deversity.asynctest.report.JUnitXmlReportListener"),
            collectedOneWay,
            "owners are ordered by their stable path identity");
    }

    /** A minimal owner-level TaggedElement identified by {@code path}. */
    private static se.deversity.vibetags.processor.model.TaggedElement owner(String path) {
        String simple = path.substring(path.lastIndexOf('.') + 1);
        return se.deversity.vibetags.processor.model.TaggedElement.builder(path)
            .names(path, simple, simple, path.replace('.', '-'))
            .kind(se.deversity.vibetags.processor.model.ElementTag.CLASS)
            .build();
    }

    /** Paths of a RenderingContext's granular owners, in the order the index would emit them. */
    private static java.util.List<String> orderOf(
            se.deversity.vibetags.processor.model.TaggedElement... owners) {
        var ctx = new se.deversity.vibetags.processor.internal.content.RenderingContext(
            "P", "h", java.util.Set.of("claude", "claude_granular"), 1024,
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(owners)));
        return ctx.granularOwners().stream()
            .map(se.deversity.vibetags.processor.model.TaggedElement::path)
            .toList();
    }

    /** Two owners covering an always-inline bucket (locked, privacy) and an indexed one (context, contract). */
    private static void addMixedSources(ProcessorTestHarness h) {
        h.addSource("com.example.Gateway",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.*;\n"
                + "@AIContext(focus = \"routing\", avoids = \"reflection\")\n"
                + "public interface Gateway {\n"
                + "    @AIContract(reason = \"partner SLA\")\n"
                + "    double charge(String id, double amount);\n"
                + "}\n");
        h.addSource("com.example.Vault",
            "package com.example;\n"
                + "import se.deversity.vibetags.annotations.*;\n"
                + "@AILocked(reason = \"crypto keys\")\n"
                + "public class Vault {\n"
                + "    @AIPrivacy(reason = \"PII\")\n"
                + "    private String ssn;\n"
                + "}\n");
    }

    @Test
    void claudeSingleOptIn_rendersFullOutput(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        addMixedSources(h);
        h.compile();

        String claude = h.readFile("CLAUDE.md");
        assertTrue(claude.contains("<contextual_instructions>"), "full mode keeps contextual_instructions");
        assertTrue(claude.contains("<contract_signatures>"), "full mode keeps contract_signatures");
        assertFalse(claude.contains("<scoped_rules>"), "no scoped-rules index without a granular sibling");
    }

    @Test
    void claudeDualOptIn_collapsesToIndex(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".claude/rules/.vibetags");
        addMixedSources(h);
        h.compile();

        String claude = h.readFile("CLAUDE.md");
        // Always-inline safety guardrails survive.
        assertTrue(claude.contains("<locked_files>"), "locked stays inline");
        assertTrue(claude.contains("<pii_guardrails>"), "privacy stays inline");
        // Omitted buckets leave the aggregate.
        assertFalse(claude.contains("<contract_signatures>"), "contract detail moves to scoped files");
        assertFalse(claude.contains("<contextual_instructions>"), "context moves to scoped files");
        // The index is present and points at the real scoped files.
        assertTrue(claude.contains("<scoped_rules>"), "scoped-rules index present");
        assertTrue(claude.contains("rules=\".claude/rules/com-example-Gateway.md\""),
            "index must point at the Gateway scoped file with the exact granular filename");
        assertTrue(h.fileExists(".claude/rules/com-example-Gateway.md"),
            "the scoped file the index points at must exist");
        // The scoped file carries the detail that left the aggregate.
        assertTrue(h.readFile(".claude/rules/com-example-Gateway.md").contains("charge"),
            "per-method contract detail lives in the scoped file");
    }

    @Test
    void geminiSingleOptIn_staysFullyRendered(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("GEMINI.md");
        addMixedSources(h);
        h.compile();

        String gemini = h.readFile("GEMINI.md");
        assertTrue(gemini.contains("# GEMINI AI INSTRUCTIONS"), "single opt-in keeps the Gemini header");
        assertFalse(gemini.contains("## Scoped Rules Index"), "no index without a granular sibling");
    }

    @Test
    void geminiDualOptIn_collapsesToIndex(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("GEMINI.md");
        h.touchOptIn(".gemini/rules/.vibetags");
        addMixedSources(h);
        h.compile();

        String gemini = h.readFile("GEMINI.md");
        // Always-inline safety guardrails survive.
        assertTrue(gemini.contains("LOCKED FILES"), "locked stays inline");
        // The index is present and points at the real scoped file.
        assertTrue(gemini.contains("## Scoped Rules Index"), "scoped-rules index present");
        assertTrue(gemini.contains(".gemini/rules/com-example-Gateway.md"),
            "index must point at the Gateway scoped file");
        assertTrue(h.fileExists(".gemini/rules/com-example-Gateway.md"),
            "the scoped file the index points at must exist");
        assertTrue(h.readFile(".gemini/rules/com-example-Gateway.md").contains("charge"),
            "per-method contract detail lives in the scoped file");
    }

    @Test
    void claudeDualOptIn_withRoles_indexPointsAtRoleFile(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".claude/rules/.vibetags");
        // A .vibetags-roles config groups the Gateway into a human-named role file. The scoped-rules
        // index in CLAUDE.md must reference the role-grouped filename that GranularRulesWriter
        // actually wrote — not the per-class default — or the pointer dangles (issue #295 follow-up).
        Files.writeString(dir.resolve(".vibetags-roles"), "gateways = **/Gateway.java\n", StandardCharsets.UTF_8);
        addMixedSources(h);
        h.compile();

        String claude = h.readFile("CLAUDE.md");
        assertTrue(claude.contains("<scoped_rules>"), "scoped-rules index present");
        assertTrue(claude.contains("rules=\".claude/rules/gateways.md\""),
            "index must point at the role-grouped file when .vibetags-roles routes the element");
        assertFalse(claude.contains("com-example-Gateway.md"),
            "index must NOT use the per-class default name once a role remaps the file");
        assertTrue(h.fileExists(".claude/rules/gateways.md"),
            "the role-grouped file the index points at must exist");
        assertFalse(h.fileExists(".claude/rules/com-example-Gateway.md"),
            "no per-class file once the element is grouped into a role");
    }

    @Test
    void cursorReuseRenderers_stayFullWhileClaudeLocalMirrors(@TempDir Path dir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(dir, false);
        h.touchOptIn(".cursorrules");
        h.touchOptIn(".cursor/rules/.vibetags");
        h.touchOptIn(".clinerules");            // reuses CursorRenderer, but reads no scoped dir
        h.touchOptIn("CLAUDE.md");
        h.touchOptIn(".claude/rules/.vibetags");
        h.touchOptIn("CLAUDE.local.md");        // mirrors CLAUDE.md
        addMixedSources(h);
        h.compile();

        // Cursor itself collapses to an index (its sibling is active)…
        assertTrue(h.readFile(".cursorrules").contains("## Scoped Rules Index"),
            ".cursorrules collapses to an index when .cursor/rules is opted in");
        // …but Cline, which merely reuses the Cursor format, must stay full — it has no scoped dir.
        assertFalse(h.readFile(".clinerules").contains("## Scoped Rules Index"),
            ".clinerules must NOT collapse — it has no granular sibling");
        assertTrue(h.readFile(".clinerules").contains("CONTEXTUAL RULES"),
            ".clinerules keeps the full contextual rules");
        // CLAUDE.local.md follows Claude's granular state, so it mirrors the indexed CLAUDE.md.
        assertEquals(h.readFile("CLAUDE.md"), h.readFile("CLAUDE.local.md"),
            "CLAUDE.local.md must mirror CLAUDE.md (both in index mode)");
    }
}
