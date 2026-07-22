package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the lean indexed-root aggregate
 * (<a href="https://github.com/PIsberg/vibetags/issues/298">issue #298</a>).
 *
 * <p>In a multi-module reactor whose modules each carry their own scoped rule files, the reactor
 * root aggregate ({@code CLAUDE.md}, {@code .cursorrules}, …) normally embeds a full verbatim copy
 * of every module's guardrails via the sidecar merge. Touching {@code .vibetags-root-index} at the
 * root flips those four granular-sibling aggregates to a lean <em>index</em>: the root links each
 * module's own scoped rules instead of duplicating them, while aggregates without a granular
 * sibling (e.g. {@code GEMINI.md}) keep the full merge.
 *
 * <p>These compile file-backed sources laid out as a two-module reactor sharing one VibeTags root,
 * exercising the real processor pipeline (not just {@code ModuleSidecar} in isolation).
 */
class LeanIndexedRootEndToEndTest {

    private static final String CORE_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Core IR node - structural changes break every downstream module")
        public class IrNode {
        }
        """;

    private static final String CLI_SOURCE = """
        package com.example.cli;

        import se.deversity.vibetags.annotations.AIAudit;

        @AIAudit(checkFor = {"Path Traversal"})
        public class KartaCli {
        }
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles one module's source into the shared reactor root, mimicking a reactor pass. */
    private void compileModule(String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    /**
     * Reactor root opts into CLAUDE.md, .cursorrules, GEMINI.md AND the lean index; each module
     * carries its own .claude/rules + .cursor/rules directories so its guardrails live there.
     */
    private void setUpIndexedReactor() throws IOException {
        for (String module : new String[]{"module-core", "module-cli"}) {
            Files.createDirectories(reactorRoot.resolve(module).resolve(".claude/rules"));
            Files.createDirectories(reactorRoot.resolve(module).resolve(".cursor/rules"));
        }
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.createFile(reactorRoot.resolve(".cursorrules"));
        Files.createFile(reactorRoot.resolve("GEMINI.md"));
        Files.createFile(reactorRoot.resolve(".vibetags-root-index")); // opt in
    }

    @Test
    void indexedRoot_linksModuleRulesInsteadOfEmbedding() throws IOException {
        setUpIndexedReactor();
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);
        compileModule("module-cli", "com.example.cli.KartaCli", CLI_SOURCE);

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));

        // The root aggregate links each module's scoped rules instead of embedding the full copy.
        assertTrue(claude.contains("module-core/.claude/rules/"), "root must link module-core's scoped rules");
        assertTrue(claude.contains("module-cli/.claude/rules/"), "root must link module-cli's scoped rules");
        assertFalse(claude.contains("com.example.core.IrNode"),
            "module-core's guardrails must NOT be embedded verbatim in the lean root");
        assertFalse(claude.contains("com.example.cli.KartaCli"),
            "module-cli's guardrails must NOT be embedded verbatim in the lean root");
        // Module sub-markers still frame each pointer for traceability.
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-core -->"));
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-cli -->"));

        // .cursorrules collapses the same way (hash-style markers).
        String cursor = Files.readString(reactorRoot.resolve(".cursorrules"));
        assertTrue(cursor.contains("module-core/.cursor/rules/"), "root .cursorrules must link module-core's scoped rules");
        assertTrue(cursor.contains("module-cli/.cursor/rules/"));
        assertFalse(cursor.contains("com.example.core.IrNode"), "no verbatim embedding in lean .cursorrules");

        // The per-module scoped rule files are the real source of truth and DO contain the detail
        // (the FQN is encoded in the file NAME; the rule text carries the reason/checks).
        assertTrue(moduleRulesContain(reactorRoot.resolve("module-core/.claude/rules"), "Core IR node"),
            "module-core's own scoped rules must contain its @AILocked guardrail");
        assertTrue(moduleRulesContain(reactorRoot.resolve("module-cli/.claude/rules"), "Path Traversal"),
            "module-cli's own scoped rules must contain its @AIAudit guardrail");
    }

    @Test
    void indexedRoot_leavesNonGranularAggregateFullyMerged() throws IOException {
        setUpIndexedReactor();
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);
        compileModule("module-cli", "com.example.cli.KartaCli", CLI_SOURCE);

        // GEMINI.md has no granular sibling → the lean index never touches it; full merge remains.
        String gemini = Files.readString(reactorRoot.resolve("GEMINI.md"));
        assertTrue(gemini.contains("com.example.core.IrNode"),
            "GEMINI.md keeps module-core's guardrails (no granular sibling to link)");
        assertTrue(gemini.contains("com.example.cli.KartaCli"),
            "GEMINI.md keeps module-cli's guardrails");
    }

    @Test
    void withoutOptIn_rootEmbedsFullMergeAsBefore() throws IOException {
        // Same layout but WITHOUT .vibetags-root-index → historical behaviour (full embedding).
        for (String module : new String[]{"module-core", "module-cli"}) {
            Files.createDirectories(reactorRoot.resolve(module).resolve(".claude/rules"));
        }
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);
        compileModule("module-cli", "com.example.cli.KartaCli", CLI_SOURCE);

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.example.core.IrNode"), "without opt-in, root embeds module-core fully");
        assertTrue(claude.contains("com.example.cli.KartaCli"), "without opt-in, root embeds module-cli fully");
    }

    private static boolean moduleRulesContain(Path rulesDir, String needle) throws IOException {
        if (!Files.isDirectory(rulesDir)) return false;
        try (Stream<Path> files = Files.walk(rulesDir)) {
            return files.filter(Files::isRegularFile).anyMatch(p -> {
                try {
                    return Files.readString(p).contains(needle);
                } catch (IOException e) {
                    return false;
                }
            });
        }
    }
}
