package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for source-set isolation
 * (<a href="https://github.com/PIsberg/vibetags/issues/330">issue #330</a>).
 *
 * <p>{@code compile} and {@code test-compile} are two javac invocations over disjoint sources of
 * the <em>same</em> module. Before this, both mapped to one module identity, so the test round —
 * which legitimately cannot see a single main source — rewrote the module's whole region from what
 * it alone saw and orphan-cleaned every main-source rule file. The failure was silent: the build
 * succeeded and the output stayed well-formed, just missing the guardrails that matter most.
 *
 * <p>Each test therefore compiles the two source sets as separate compilations, in the order Maven
 * runs them, and asserts that the second does not erase the first.
 */
@Tag("e2e")
class SourceSetIsolationEndToEndTest {

    /**
     * Assertions use these, not the FQNs: {@code com.example.core.IrNodeTest} contains
     * {@code com.example.core.IrNode}, so an FQN assertion would be satisfied by the test class
     * alone and would not notice the main-source guardrails going missing.
     */
    private static final String MAIN_REASON = "Core IR node - structural changes break every downstream module";
    private static final String TEST_REASON = "shares no static state";

    private static final String MAIN_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Core IR node - structural changes break every downstream module")
        public class IrNode {
        }
        """;

    private static final String OTHER_MAIN_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AICore;

        @AICore(sensitivity = "high", note = "parser core")
        public class Parser {
        }
        """;

    private static final String TEST_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AIParallelTests;

        @AIParallelTests(reason = "shares no static state")
        public class IrNodeTest {
        }
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles one source set of one module into the shared root, mimicking one Maven phase. */
    private void compileSourceSet(String module, String sourceSet, List<String[]> fqnAndSource) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve(module));
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        for (String[] pair : fqnAndSource) {
            harness.writeSourceFile(
                module + "/src/" + sourceSet + "/java/" + pair[0].replace('.', '/') + ".java", pair[1]);
        }
        harness.compile();
    }

    /** Reactor root with a merged CLAUDE.md; the module keeps its own scoped rules. */
    private void setUpReactor(String module) throws IOException {
        Files.createDirectories(reactorRoot.resolve(module).resolve(".claude/rules"));
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
    }

    @Test
    void testCompileRoundKeepsMainSourceGranularFiles() throws IOException {
        setUpReactor("module-core");
        compileSourceSet("module-core", "main", List.<String[]>of(
            new String[]{"com.example.core.IrNode", MAIN_SOURCE},
            new String[]{"com.example.core.Parser", OTHER_MAIN_SOURCE}));

        Path rules = reactorRoot.resolve("module-core/.claude/rules");
        assertEquals(2, ruleFileNames(rules).size(), "main compile writes one rule file per class");

        compileSourceSet("module-core", "test", List.<String[]>of(
            new String[]{"com.example.core.IrNodeTest", TEST_SOURCE}));

        List<String> after = ruleFileNames(rules);
        assertTrue(after.contains("com-example-core-IrNode.md"),
            "the test round must not orphan-clean a main-source rule file it could not see: " + after);
        assertTrue(after.contains("com-example-core-Parser.md"),
            "…nor any of the others: " + after);
        assertTrue(after.contains("com-example-core-IrNodeTest.md"),
            "the test round still writes its own rule file: " + after);
    }

    @Test
    void testCompileRoundKeepsMainSourceGuardrailsInTheRootAggregate() throws IOException {
        setUpReactor("module-core");
        compileSourceSet("module-core", "main", List.<String[]>of(
            new String[]{"com.example.core.IrNode", MAIN_SOURCE}));
        compileSourceSet("module-core", "test", List.<String[]>of(
            new String[]{"com.example.core.IrNodeTest", TEST_SOURCE}));

        // Assert on the reason text, not the FQN: "com.example.core.IrNodeTest" contains
        // "com.example.core.IrNode", so an FQN assertion here would pass on the test class alone.
        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains(MAIN_REASON),
            "the main source's @AILocked guardrail must survive the test-compile round:\n" + claude);
        assertTrue(claude.contains(TEST_REASON),
            "and the test source's guardrail is added, not substituted:\n" + claude);
    }

    /**
     * The two source sets are two sidecar files but one module, so a single-module project must
     * keep its historical sub-marker-free output. Regression guard for the fix itself.
     */
    @Test
    void twoSourceSetsOfOneModuleDoNotLookLikeTwoModules() throws IOException {
        setUpReactor("module-core");
        compileSourceSet("module-core", "main", List.<String[]>of(
            new String[]{"com.example.core.IrNode", MAIN_SOURCE}));
        compileSourceSet("module-core", "test", List.<String[]>of(
            new String[]{"com.example.core.IrNodeTest", TEST_SOURCE}));

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertFalse(claude.contains("VIBETAGS-MODULE:"),
            "one module compiled twice is still one module — no sub-markers:\n" + claude);
        try (Stream<Path> files = Files.list(reactorRoot)) {
            assertEquals(2, files.filter(p -> p.getFileName().toString().startsWith(".vibetags-mod-")).count(),
                "each source set owns its own sidecar file");
        }
    }

    /** The module's own aggregate file must gain the test guardrails, not be replaced by them. */
    @Test
    void moduleOwnAggregateMergesAcrossSourceSets() throws IOException {
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.createDirectories(reactorRoot.resolve("module-core"));
        Files.createFile(reactorRoot.resolve("module-core/CLAUDE.md"));

        compileSourceSet("module-core", "main", List.<String[]>of(
            new String[]{"com.example.core.IrNode", MAIN_SOURCE}));
        compileSourceSet("module-core", "test", List.<String[]>of(
            new String[]{"com.example.core.IrNodeTest", TEST_SOURCE}));

        String moduleClaude = Files.readString(reactorRoot.resolve("module-core/CLAUDE.md"));
        assertTrue(moduleClaude.contains(MAIN_REASON),
            "module-scoped output keeps its main-source guardrails after test-compile:\n" + moduleClaude);
        assertTrue(moduleClaude.contains(TEST_REASON),
            "and carries the test-source guardrails too:\n" + moduleClaude);
    }

    /**
     * The single-module case, where the two source sets are the only two sidecars there are. The
     * merge has to run for them as well — gating it on "more than one module" leaves the test round
     * writing its own content straight over the main round's.
     */
    @Test
    void singleModuleProjectKeepsBothSourceSets() throws IOException {
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.writeString(reactorRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        for (String[] pair : new String[][]{
                {"main", "com.example.core.IrNode", MAIN_SOURCE},
                {"test", "com.example.core.IrNodeTest", TEST_SOURCE}}) {
            ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
            harness.writeSourceFile("src/" + pair[0] + "/java/" + pair[1].replace('.', '/') + ".java", pair[2]);
            harness.compile();
        }

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        assertTrue(claude.contains(MAIN_REASON),
            "the main source's guardrail must survive test-compile in a single-module project:\n" + claude);
        assertTrue(claude.contains(TEST_REASON), claude);
        assertFalse(claude.contains("VIBETAGS-MODULE:"),
            "one module is still one module — no sub-markers:\n" + claude);
    }

    /** A second module's rule files are equally invisible to this module's round. */
    @Test
    void oneModuleRoundKeepsAnotherModulesRootScopedRuleFiles() throws IOException {
        Files.createDirectories(reactorRoot.resolve(".claude/rules"));
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));

        compileSourceSet("module-core", "main", List.<String[]>of(
            new String[]{"com.example.core.IrNode", MAIN_SOURCE}));
        compileSourceSet("module-cli", "main", List.<String[]>of(
            new String[]{"com.example.core.Parser", OTHER_MAIN_SOURCE}));

        List<String> rules = ruleFileNames(reactorRoot.resolve(".claude/rules"));
        assertTrue(rules.contains("com-example-core-IrNode.md"),
            "module-cli's compile must not delete module-core's shared scoped rules: " + rules);
        assertTrue(rules.contains("com-example-core-Parser.md"), rules.toString());
    }

    private static List<String> ruleFileNames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
        }
    }
}
