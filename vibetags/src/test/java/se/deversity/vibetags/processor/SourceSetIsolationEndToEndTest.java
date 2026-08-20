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

    private static final String OTHER_TEST_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AIParallelTests;

        @AIParallelTests(reason = "no shared fixtures")
        public class ParserTest {
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


    // ----------------------------------------------------------------- withdrawal

    /**
     * The withdrawal direction of the source-set split: a test class is deleted and the test round
     * still runs, over a different test class. The round that owns the source set is the one that
     * can see the loss, and it must replace its own contribution rather than add to it.
     */
    @Test
    void aTestRoundThatRunsReplacesItsOwnContribution() throws IOException {
        setUpReactor("module-core");
        compileSourceSet("module-core", "main", List.<String[]>of(new String[]{"com.example.core.IrNode", MAIN_SOURCE}));
        compileSourceSet("module-core", "test", List.<String[]>of(new String[]{"com.example.core.IrNodeTest", TEST_SOURCE}));
        assertTrue(Files.readString(reactorRoot.resolve("CLAUDE.md")).contains(TEST_REASON),
            "precondition: the test source set contributed first");

        // IrNodeTest is gone; ParserTest takes its place, so test-compile has sources and runs.
        Files.delete(reactorRoot.resolve("module-core/src/test/java/com/example/core/IrNodeTest.java"));
        compileSourceSet("module-core", "test",
            List.<String[]>of(new String[]{"com.example.core.ParserTest", OTHER_TEST_SOURCE}));

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("no shared fixtures"), "the new test class must be there");
        assertFalse(claude.contains(TEST_REASON),
            "the deleted test class's guardrail describes code that is gone:\n" + claude);
        assertTrue(claude.contains(MAIN_REASON),
            "and the other source set must be untouched by any of it:\n" + claude);
    }

    /**
     * The boundary of that, pinned rather than fixed. When a source set is emptied of every
     * annotated class, the build stops compiling it at all — Maven's {@code test-compile} over zero
     * sources runs no annotation processor — so no round is in a position to notice. A main round
     * cannot tell "the test sources were deleted" from "test-compile has not run yet", and guessing
     * would delete a sibling source set's guardrails on every {@code mvn compile} (issue #383: a
     * round never argues from an absence it cannot see).
     *
     * <p>The recorded escape is the same as for a module emptied entirely: delete the source set's
     * sidecar, {@code .vibetags-mod-<module>__test}. This test exists so that the day the behaviour
     * changes, it changes deliberately.
     */
    @Test
    void anEmptiedSourceSetKeepsItsLastContributionUntilItsSidecarIsDeleted() throws IOException {
        setUpReactor("module-core");
        compileSourceSet("module-core", "main", List.<String[]>of(new String[]{"com.example.core.IrNode", MAIN_SOURCE}));
        compileSourceSet("module-core", "test", List.<String[]>of(new String[]{"com.example.core.IrNodeTest", TEST_SOURCE}));

        // Every annotated test class is deleted, so a real build compiles no test sources at all.
        Files.delete(reactorRoot.resolve("module-core/src/test/java/com/example/core/IrNodeTest.java"));
        compileSourceSet("module-core", "main", List.<String[]>of(new String[]{"com.example.core.IrNode", MAIN_SOURCE}));

        Path testSidecar = reactorRoot.resolve(".vibetags-mod-module-core__test");
        assertTrue(Files.exists(testSidecar), "the emptied source set's sidecar is what keeps it alive");
        assertTrue(Files.readString(reactorRoot.resolve("CLAUDE.md")).contains(TEST_REASON),
            "pinned limitation: nothing compiled the test source set, so nothing could retire it");

        // The escape hatch, and the proof that it is the sidecar doing it.
        Files.delete(testSidecar);
        compileSourceSet("module-core", "main", List.<String[]>of(new String[]{"com.example.core.IrNode", MAIN_SOURCE}));

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertFalse(claude.contains(TEST_REASON),
            "deleting the sidecar must clear the emptied source set:\n" + claude);
        assertTrue(claude.contains(MAIN_REASON), "and must cost the main source set nothing:\n" + claude);
    }

    /**
     * The element does not go away, it changes source set: a class moves from {@code src/main} to
     * {@code src/test} of the same module. Both rounds run, and both sidecars carry the same region
     * id, so the merge has to see one contribution rather than the main round's memory of it beside
     * the test round's fresh copy.
     *
     * <p>The module analogue of this is {@code AnnotationTransitionEndToEndTest}'s move between
     * modules, where the failure is a duplicate rather than an absence. Same failure here.
     */
    @Test
    void aClassThatChangesSourceSetIsNotCountedTwice() throws IOException {
        setUpReactor("module-core");
        compileSourceSet("module-core", "main", List.<String[]>of(
            new String[]{"com.example.core.IrNode", MAIN_SOURCE},
            new String[]{"com.example.core.Parser", OTHER_MAIN_SOURCE}));
        assertTrue(Files.readString(reactorRoot.resolve("CLAUDE.md")).contains(MAIN_REASON),
            "precondition: the class is a main-source contribution first");

        // IrNode moves to the test source set. Parser keeps the main round non-empty, which is what
        // keeps the processor running at all - javac only invokes it for a round with annotations.
        Files.delete(reactorRoot.resolve("module-core/src/main/java/com/example/core/IrNode.java"));
        compileSourceSet("module-core", "main", List.<String[]>of(
            new String[]{"com.example.core.Parser", OTHER_MAIN_SOURCE}));
        compileSourceSet("module-core", "test", List.<String[]>of(
            new String[]{"com.example.core.IrNode", MAIN_SOURCE}));

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(claude, MAIN_REASON),
            "the class changed source set, it was not duplicated: " + claude);
        assertTrue(claude.contains("parser core"),
            "and the class that stayed in main must be untouched: " + claude);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
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
