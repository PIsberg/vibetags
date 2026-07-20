package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for per-module (nested) output: opting into a guardrail file INSIDE a module's
 * own directory makes VibeTags write that module's own guardrails there, scoped to that module's
 * annotations — alongside the merged reactor-root file, which is unchanged.
 *
 * <p>Uses file-backed sources laid out as a Maven reactor (real {@code pom.xml} per module) so
 * {@link se.deversity.vibetags.processor.internal.ModuleRootResolver} resolves genuine module
 * directories, exactly like {@link MultiModuleReactorEndToEndTest}.
 */
class PerModuleOutputEndToEndTest {

    private static final String CORE_SOURCE = """
        package com.example.core;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "Core IR node")
        public class IrNode {}
        """;

    private static final String CLI_SOURCE = """
        package com.example.cli;
        import se.deversity.vibetags.annotations.AIAudit;
        @AIAudit(checkFor = {"Path Traversal"})
        public class KartaCli {}
        """;

    // @AIContext is NOT in the always-inline safety set, so a module that also opts into a granular
    // dir collapses its aggregate to a scoped-rules index.
    private static final String CTX_SOURCE = """
        package com.example.core;
        import se.deversity.vibetags.annotations.AIContext;
        @AIContext(focus = "hot path", avoids = "allocation")
        public class Router {}
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles one module (real pom.xml + file-backed source) into the shared reactor root. */
    private void compileModule(String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve(module));
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    private void touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            Files.createFile(p);
        }
    }

    @Test
    void moduleWithOwnClaude_getsOnlyItsOwnGuardrails() throws IOException {
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));                          // root opt-in
        touch(reactorRoot.resolve("module-core/CLAUDE.md"));                         // module opt-in

        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);
        compileModule("module-cli", "com.example.cli.KartaCli", CLI_SOURCE);

        // The module file holds ONLY module-core's guardrails.
        String moduleClaude = Files.readString(reactorRoot.resolve("module-core/CLAUDE.md"));
        assertTrue(moduleClaude.contains("com.example.core.IrNode"),
            "module-core/CLAUDE.md must contain module-core's own @AILocked");
        assertFalse(moduleClaude.contains("com.example.cli.KartaCli"),
            "module-core/CLAUDE.md must NOT contain a sibling module's guardrails");

        // The reactor-root file is unchanged behavior: it still merges ALL modules via sidecars.
        String rootClaude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(rootClaude.contains("com.example.core.IrNode"));
        assertTrue(rootClaude.contains("com.example.cli.KartaCli"));
        assertTrue(rootClaude.contains("<!-- VIBETAGS-MODULE: module-core -->"),
            "root still uses the sidecar merge with module sub-markers");

        // module-cli did NOT opt in → no per-module file for it.
        assertFalse(Files.exists(reactorRoot.resolve("module-cli/CLAUDE.md")),
            "a module that did not opt in gets no per-module file");
    }

    @Test
    void moduleWithGranularSibling_collapsesToScopedIndex() throws IOException {
        touch(reactorRoot.resolve("module-core/CLAUDE.md"));
        Files.createDirectories(reactorRoot.resolve("module-core/.claude/rules")); // granular opt-in (dir presence)

        compileModule("module-core", "com.example.core.Router", CTX_SOURCE);

        String moduleClaude = Files.readString(reactorRoot.resolve("module-core/CLAUDE.md"));
        assertTrue(moduleClaude.contains("<scoped_rules>"),
            "module aggregate collapses to a scoped-rules index when it opts into a granular dir too");
        assertFalse(moduleClaude.contains("<contextual_instructions>"),
            "context detail moves to the module's scoped files");
        assertTrue(Files.exists(reactorRoot.resolve("module-core/.claude/rules/com-example-core-Router.md")),
            "module-scoped granular rule file must be written under the module directory");
    }

    @Test
    void moduleWithoutOptIn_writesNothingUnderIt() throws IOException {
        Files.createFile(reactorRoot.resolve("CLAUDE.md")); // only the root opts in
        compileModule("module-core", "com.example.core.IrNode", CORE_SOURCE);

        assertFalse(Files.exists(reactorRoot.resolve("module-core/CLAUDE.md")),
            "no module opt-in → no per-module file");
        assertTrue(Files.readString(reactorRoot.resolve("CLAUDE.md")).contains("com.example.core.IrNode"),
            "the reactor-root file is still generated");
    }
}
