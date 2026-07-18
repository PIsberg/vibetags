package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.internal.ModuleSidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression tests for
 * <a href="https://github.com/PIsberg/vibetags/issues/278">issue #278</a>: in a multi-module
 * reactor build the JVM working directory is the reactor root for <em>every</em> module, so
 * module identity must come from the compiled sources, not from {@code Paths.get("")}.
 *
 * <p>These tests compile <em>file-backed</em> sources (real {@code file:} URIs) laid out as a
 * two-module Maven reactor sharing one VibeTags root — exactly the blindbean/codekarta setup —
 * while the JVM working directory stays wherever the test JVM happens to run. Before the fix,
 * both "modules" computed the same identity and the shared files degraded to last-writer-wins.
 */
class MultiModuleReactorEndToEndTest {

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

    private static final String CLI_PLAIN_SOURCE = """
        package com.example.cli;

        public class CliTestHelper {
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
            "<project><artifactId>" + module + "</artifactId></project>",
            java.nio.charset.StandardCharsets.UTF_8);
        harness.writeSourceFile(
            module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    private void setUpReactor() throws IOException {
        // Opt-in files at the shared root only; module dirs created by compileModule.
        Files.createDirectories(reactorRoot.resolve("module-core"));
        Files.createDirectories(reactorRoot.resolve("module-cli"));
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.createFile(reactorRoot.resolve(".cursorrules"));
    }

    @Test
    void reactorBuild_bothModulesSurviveInSharedOutput() throws IOException {
        setUpReactor();
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);
        compileModule("module-cli", "com.example.cli.KartaCli", CLI_SOURCE);

        // Per-module sidecars must exist under their source-derived identities — NOT a single
        // shared "_root_" (the working directory of this test JVM is far from reactorRoot).
        assertTrue(Files.exists(reactorRoot.resolve(".vibetags-mod-module-core")),
            "module-core must persist a sidecar under its own module identity");
        assertTrue(Files.exists(reactorRoot.resolve(".vibetags-mod-module-cli")),
            "module-cli must persist a sidecar under its own module identity");

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.example.core.IrNode"),
            "first-compiled module's @AILocked must survive the second module's compile");
        assertTrue(claude.contains("com.example.cli.KartaCli"),
            "second module's @AIAudit must be present");
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-core -->"),
            "merged CLAUDE.md must carry module sub-markers");
        assertTrue(claude.contains("<!-- VIBETAGS-MODULE: module-cli -->"));

        String cursor = Files.readString(reactorRoot.resolve(".cursorrules"));
        assertTrue(cursor.contains("com.example.core.IrNode"));
        assertTrue(cursor.contains("com.example.cli.KartaCli"));
        assertTrue(cursor.contains("# VIBETAGS-MODULE: module-core"));
        assertTrue(cursor.contains("# VIBETAGS-MODULE: module-cli"));
    }

    @Test
    void recompilingOneModule_doesNotDropSiblings() throws IOException {
        setUpReactor();
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);
        compileModule("module-cli", "com.example.cli.KartaCli", CLI_SOURCE);
        // Recompile the FIRST module again (reactor order reshuffled — the issue's repro notes
        // that reordering the reactor changed which module "won").
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.example.core.IrNode"));
        assertTrue(claude.contains("com.example.cli.KartaCli"),
            "sibling content must survive regardless of compile order");
    }

    @Test
    void annotationlessCompile_doesNotWipeModuleSidecar() throws IOException {
        setUpReactor();
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);
        compileModule("module-cli", "com.example.cli.KartaCli", CLI_SOURCE);
        // Simulate Maven's test-compile pass for module-cli: same module, zero annotations.
        compileModule("module-cli", "com.example.cli.CliTestHelper", CLI_PLAIN_SOURCE);

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains("com.example.cli.KartaCli"),
            "an annotation-less pass (test-compile) must not evict the module's contribution");
        assertTrue(claude.contains("com.example.core.IrNode"));
        assertTrue(Files.exists(reactorRoot.resolve(".vibetags-mod-module-cli")),
            "the sidecar written by the main compile must survive the test-compile pass");
    }

    @Test
    void legacyV1Sidecar_isPrunedAndExcludedFromMerge() throws IOException {
        setUpReactor();
        // A pre-fix sidecar: v1 format, wrong shared "_root_" identity, stale content.
        String staleBody = java.util.Base64.getEncoder().encodeToString(
            "<locked_files><file path=\"com.example.stale.Ghost\"/></locked_files>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(reactorRoot.resolve(".vibetags-mod-_root_"),
            "# version=1\nmoduleId=_root_\nmodulePath=\nclaude=" + staleBody + "\n");

        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);

        assertFalse(Files.exists(reactorRoot.resolve(".vibetags-mod-_root_")),
            "v1 sidecars carry the broken working-directory identity and must be pruned");
        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertFalse(claude.contains("com.example.stale.Ghost"),
            "stale v1 content must not leak into the merged output");
        assertTrue(claude.contains("com.example.core.IrNode"));
    }

    @Test
    void savedSidecar_roundTripsUnderCurrentFormat(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("m"));
        ModuleSidecar s = new ModuleSidecar("m", "m");
        s.putBody("claude", "body");
        s.save(dir);
        assertEquals(1, ModuleSidecar.readAll(dir).size(),
            "a sidecar written by the current version must load back");
    }
}
