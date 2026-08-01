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
 * <p>What the root keeps inline is the <em>safety tier</em> — {@code @AILocked}, {@code @AICore},
 * {@code @AIPrivacy}, {@code @AIIgnore}, {@code @AIAudit}, {@code @AISecure}
 * (<a href="https://github.com/PIsberg/vibetags/issues/332">issue #332</a>). Those guardrails earn
 * their keep by being unconditionally present: an agent reasoning about a rename from three files
 * away has to know a file is locked before it opens it. The verbose per-element detail is exactly
 * what should load on demand, and that is what the index replaces.
 *
 * <p>These compile file-backed sources laid out as a two-module reactor sharing one VibeTags root,
 * exercising the real processor pipeline (not just {@code ModuleSidecar} in isolation).
 */
class LeanIndexedRootEndToEndTest {

    /** Carries one safety-tier annotation and one verbose-tier one, so the split is observable. */
    private static final String CORE_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AIContext;
        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Core IR node - structural changes break every downstream module")
        @AIContext(focus = "graph traversal order", avoids = "reflection-based access")
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
        // The verbose tier is what the pointer replaces.
        assertFalse(claude.contains("graph traversal order"),
            "module-core's @AIContext detail must NOT be embedded in the lean root");
        assertFalse(claude.contains("<contextual_instructions>"),
            "no verbose per-element buckets in the lean root:\n" + claude);
        // Module sub-markers still frame each pointer for traceability.
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-core -->"));
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-cli -->"));

        // .cursorrules collapses the same way (hash-style markers).
        String cursor = Files.readString(reactorRoot.resolve(".cursorrules"));
        assertTrue(cursor.contains("module-core/.cursor/rules/"), "root .cursorrules must link module-core's scoped rules");
        assertTrue(cursor.contains("module-cli/.cursor/rules/"));
        assertFalse(cursor.contains("graph traversal order"), "no verbose detail in lean .cursorrules");

        // The per-module scoped rule files are the real source of truth and DO contain the detail
        // (the FQN is encoded in the file NAME; the rule text carries the reason/checks).
        assertTrue(moduleRulesContain(reactorRoot.resolve("module-core/.claude/rules"), "Core IR node"),
            "module-core's own scoped rules must contain its @AILocked guardrail");
        assertTrue(moduleRulesContain(reactorRoot.resolve("module-core/.claude/rules"), "graph traversal order"),
            "…and the verbose detail the root stopped carrying");
        assertTrue(moduleRulesContain(reactorRoot.resolve("module-cli/.claude/rules"), "Path Traversal"),
            "module-cli's own scoped rules must contain its @AIAudit guardrail");
    }

    /**
     * Issue #332: the lean root must keep the always-on safety tier inline. A guardrail that only
     * loads when the agent opens the very file it protects has become a comment — by the time it is
     * in context, the agent is already there.
     */
    @Test
    void indexedRoot_keepsTheSafetyTierInlinePerModule() throws IOException {
        setUpIndexedReactor();
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);
        compileModule("module-cli", "com.example.cli.KartaCli", CLI_SOURCE);

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.example.core.IrNode"),
            "@AILocked must stay inline in the lean root:\n" + claude);
        assertTrue(claude.contains("Core IR node - structural changes break every downstream module"),
            "…with its reason, so the agent knows why before it opens anything");
        assertTrue(claude.contains("com.example.cli.KartaCli") && claude.contains("Path Traversal"),
            "@AIAudit is a precondition for working on the file, so it stays inline too:\n" + claude);
        assertFalse(claude.contains("<scoped_rules>"),
            "the root cannot host a module's scoped index — the pointer names the directory instead");
        // Digest first, pointer after: the module's own files remain the full source of truth.
        assertTrue(claude.indexOf("Core IR node") < claude.indexOf("module-core/.claude/rules/"),
            "the inline safety digest precedes the pointer within a module's region");
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
        assertTrue(gemini.contains("graph traversal order"),
            "including the verbose tier the lean aggregates dropped");
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
        assertTrue(claude.contains("graph traversal order"), "…including the verbose tier");
        assertTrue(claude.contains("com.example.cli.KartaCli"), "without opt-in, root embeds module-cli fully");
    }

    /**
     * Regression: the opt-in must engage when only ONE module contributes a sidecar.
     *
     * <p>A module writes a sidecar only when its compilation actually saw annotations, so a reactor
     * whose annotations all live in one module produces exactly one. The merge path — which owns
     * pointer substitution — used to be gated on {@code allSidecars.size() > 1}, so for those
     * projects {@code .vibetags-root-index} was read, logged as an active service, and then had no
     * effect at all. Real-world case: async-test-lib, where only the library module is annotated
     * and the agent/analysis modules are not.
     */
    @Test
    void indexedRoot_collapsesWhenOnlyOneModuleContributes() throws IOException {
        setUpIndexedReactor();
        // module-cli is part of the reactor but has no annotated sources, so it writes no sidecar.
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("module-core/.claude/rules/"),
            "the sole contributing module must still be linked, not embedded");
        assertFalse(claude.contains("graph traversal order"),
            "the lean root must not embed the sole module's verbose tier");
        assertTrue(claude.contains("Core IR node - structural changes break every downstream module"),
            "…but its safety tier stays inline");
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-core -->"),
            "the pointer keeps its owning-module sub-marker even when it is the only one");

        // The module's own scoped rules remain the source of truth.
        assertTrue(moduleRulesContain(reactorRoot.resolve("module-core/.claude/rules"), "Core IR node"),
            "module-core's own scoped rules must still carry its @AILocked guardrail");
    }

    /**
     * A module with nothing in the safety tier contributes only its pointer — an empty
     * {@code <project_guardrails>} shell would be noise, not a guardrail.
     */
    @Test
    void indexedRoot_emitsNoDigestForAModuleWithNoSafetyTier() throws IOException {
        setUpIndexedReactor();
        compileModule("module-core", "com.example.core.Helper", """
            package com.example.core;

            import se.deversity.vibetags.annotations.AIContext;

            @AIContext(focus = "graph traversal order", avoids = "reflection-based access")
            public class Helper {
            }
            """);

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("module-core/.claude/rules/"), claude);
        assertFalse(claude.contains("<project_guardrails>"),
            "no safety tier means no digest at all:\n" + claude);
    }

    /**
     * The single-sidecar case with NO opt-in must keep the historical shape: one contribution is
     * returned verbatim and without sub-markers, exactly as a single-module project always was.
     */
    @Test
    void singleModuleWithoutOptIn_staysVerbatimAndUnmarked() throws IOException {
        Files.createDirectories(reactorRoot.resolve("module-core").resolve(".claude/rules"));
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.example.core.IrNode"),
            "without the opt-in a lone module is embedded as before");
        assertFalse(claude.contains("<!-- VIBETAGS-MODULE:"),
            "a lone contribution must not gain module sub-markers");
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
