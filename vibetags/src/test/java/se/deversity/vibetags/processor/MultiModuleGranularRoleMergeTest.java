package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression tests for
 * <a href="https://github.com/PIsberg/vibetags/issues/365">issue #365</a>: a granular role file at
 * the reactor root is written by every module whose classes match the role, and before the fix each
 * write <em>replaced</em> the previous module's content instead of merging with it. Only the module
 * that compiled last kept its guardrails; the rest vanished silently.
 *
 * <p>The layout mirrors the report: one {@code .vibetags-roles} at the reactor root routes packages
 * living in three different modules into a single role, so all three write the same file
 * {@code .gemini/rules/instrumentation.md}.
 */
@Tag("e2e")
class MultiModuleGranularRoleMergeTest {

    private static final String BENCHMARK_SOURCE = """
        package com.example.benchmark;

        import se.deversity.vibetags.annotations.AICore;

        @AICore(sensitivity = "high", note = "Benchmark recorder - timing arithmetic is load-bearing")
        public class BenchmarkRecorder {
        }
        """;

    private static final String AGENT_SOURCE = """
        package com.example.agent;

        import se.deversity.vibetags.annotations.AICore;

        @AICore(sensitivity = "critical", note = "Nothing may throw out of premain - an exception there aborts JVM startup")
        public class AsyncTestAgent {
        }
        """;

    private static final String ANALYSIS_SOURCE = """
        package com.example.analysis;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Static pinning scan order is load-bearing")
        public class StaticPinningScanner {
        }
        """;

    /** A class in the same module as the benchmark one, but matching no role — keeps its own file. */
    private static final String UNROUTED_SOURCE = """
        package com.example.plain;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "plain helper", avoids = "reflection")
        public class PlainHelper {
        }
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles one module's sources into the shared reactor root, mimicking one reactor pass. */
    private void compileModule(String module, String... fqnAndSource) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        for (int i = 0; i < fqnAndSource.length; i += 2) {
            harness.writeSourceFile(
                module + "/src/main/java/" + fqnAndSource[i].replace('.', '/') + ".java",
                fqnAndSource[i + 1]);
        }
        harness.compile();
    }

    private void setUpReactor() throws IOException {
        Files.createDirectories(reactorRoot.resolve("module-lib"));
        Files.createDirectories(reactorRoot.resolve("module-agent"));
        Files.createDirectories(reactorRoot.resolve("module-analysis"));
        // Granular rules opted in at the SHARED root — the collision point in the report.
        Files.createDirectories(reactorRoot.resolve(".gemini/rules"));
        Files.writeString(reactorRoot.resolve(".vibetags-roles"),
            "instrumentation = **/benchmark/**, **/agent/**, **/analysis/**\n", StandardCharsets.UTF_8);
    }

    private String roleFile() throws IOException {
        return Files.readString(reactorRoot.resolve(".gemini/rules/instrumentation.md"),
            StandardCharsets.UTF_8);
    }

    @Test
    void fullReactorBuild_roleFileKeepsEveryModulesGuardrails() throws IOException {
        setUpReactor();
        compileModule("module-lib", "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);
        compileModule("module-agent", "com.example.agent.AsyncTestAgent", AGENT_SOURCE);
        compileModule("module-analysis", "com.example.analysis.StaticPinningScanner", ANALYSIS_SOURCE);

        String role = roleFile();
        assertTrue(role.contains("com.example.benchmark.BenchmarkRecorder"),
            "first module's guardrail must survive later modules' compiles:\n" + role);
        assertTrue(role.contains("com.example.agent.AsyncTestAgent"),
            "middle module's guardrail must survive the last module's compile:\n" + role);
        assertTrue(role.contains("com.example.analysis.StaticPinningScanner"),
            "last module's guardrail must be present:\n" + role);
        assertTrue(role.contains("aborts JVM startup"),
            "the critical @AICore note must reach the scoped rule file:\n" + role);
    }

    @Test
    void recompilingOneModule_doesNotDropSiblingsFromTheRoleFile() throws IOException {
        setUpReactor();
        compileModule("module-lib", "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);
        compileModule("module-agent", "com.example.agent.AsyncTestAgent", AGENT_SOURCE);
        compileModule("module-analysis", "com.example.analysis.StaticPinningScanner", ANALYSIS_SOURCE);
        String afterFullBuild = roleFile();

        // The report's second repro: `mvn -pl <one-module> clean compile`.
        compileModule("module-lib", "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);

        String role = roleFile();
        assertTrue(role.contains("com.example.agent.AsyncTestAgent"),
            "a one-module rebuild must not evict a sibling module's guardrails:\n" + role);
        assertTrue(role.contains("com.example.analysis.StaticPinningScanner"),
            "a one-module rebuild must not evict a sibling module's guardrails:\n" + role);
        assertEquals(afterFullBuild, role,
            "which module compiled last must not change the file — that is the spurious-diff half of the report");
    }

    @Test
    void moduleOrderDoesNotChangeTheRoleFile() throws IOException {
        setUpReactor();
        compileModule("module-lib", "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);
        compileModule("module-agent", "com.example.agent.AsyncTestAgent", AGENT_SOURCE);
        compileModule("module-analysis", "com.example.analysis.StaticPinningScanner", ANALYSIS_SOURCE);
        String forward = roleFile();

        compileModule("module-analysis", "com.example.analysis.StaticPinningScanner", ANALYSIS_SOURCE);
        compileModule("module-agent", "com.example.agent.AsyncTestAgent", AGENT_SOURCE);
        compileModule("module-lib", "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);

        assertEquals(forward, roleFile(),
            "the merged role file must be a function of the annotations, not of reactor order");
    }

    @Test
    void unroutedClassesKeepTheirOwnPerClassFile() throws IOException {
        setUpReactor();
        compileModule("module-lib",
            "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE,
            "com.example.plain.PlainHelper", UNROUTED_SOURCE);
        compileModule("module-agent", "com.example.agent.AsyncTestAgent", AGENT_SOURCE);

        Path perClass = reactorRoot.resolve(".gemini/rules/com-example-plain-PlainHelper.md");
        assertTrue(Files.exists(perClass),
            "a class matching no role must still get its per-class file: "
                + Files.list(reactorRoot.resolve(".gemini/rules")).toList());
        assertTrue(Files.readString(perClass, StandardCharsets.UTF_8).contains("plain helper"),
            "the per-class file must carry that class's own guardrails");
        assertTrue(roleFile().contains("com.example.agent.AsyncTestAgent"));
    }

    /** Provenance: each contributing module's section is traceable, as in the aggregate files. */
    @Test
    void mergedRoleFileCarriesModuleSubMarkers() throws IOException {
        setUpReactor();
        compileModule("module-lib", "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);
        compileModule("module-agent", "com.example.agent.AsyncTestAgent", AGENT_SOURCE);

        String role = roleFile();
        assertTrue(role.contains("<!-- VIBETAGS-MODULE: module-lib -->"),
            "each module's contribution must be traceable to it:\n" + role);
        assertTrue(role.contains("<!-- VIBETAGS-MODULE-END: module-lib -->"), role);
        assertTrue(role.contains("<!-- VIBETAGS-MODULE: module-agent -->"), role);
    }

    /**
     * Check mode must reproduce generation exactly, or CI reports drift on a tree a compile just
     * produced. This is the half that broke last time the two paths were fixed one at a time.
     */
    @Test
    void checkModeReportsNoDriftAfterAReactorBuild() throws IOException {
        setUpReactor();
        compileModule("module-lib", "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);
        compileModule("module-agent", "com.example.agent.AsyncTestAgent", AGENT_SOURCE);
        compileModule("module-analysis", "com.example.analysis.StaticPinningScanner", ANALYSIS_SOURCE);

        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        harness.writeSourceFile(
            "module-lib/src/main/java/com/example/benchmark/BenchmarkRecorder.java", BENCHMARK_SOURCE);
        List<String> errors = harness.compileReturningDiagnostics("-Avibetags.check=true").stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .toList();

        assertTrue(errors.isEmpty(),
            "check mode must agree with what generation just wrote, but reported: " + errors);
    }

    /**
     * The same collision one level down: a role matched by both the main and the test sources of a
     * single module. Two source sets are two compilations of one region, and before the merge the
     * second one to run replaced the first's rules.
     */
    @Test
    void roleFileMergesAcrossSourceSetsOfOneModule() throws IOException {
        Files.createDirectories(reactorRoot.resolve(".gemini/rules"));
        Files.writeString(reactorRoot.resolve(".vibetags-roles"),
            "instrumentation = **/benchmark/**, **/agent/**\n", StandardCharsets.UTF_8);
        Files.writeString(reactorRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        compileSourceSet("main", "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);
        compileSourceSet("test", "com.example.agent.AsyncTestAgent", AGENT_SOURCE);

        String role = roleFile();
        assertTrue(role.contains("com.example.benchmark.BenchmarkRecorder"),
            "the main source set's rules must survive the test-compile round:\n" + role);
        assertTrue(role.contains("com.example.agent.AsyncTestAgent"),
            "and the test source set's are added, not substituted:\n" + role);
        assertFalse(role.contains("VIBETAGS-MODULE:"),
            "one module compiled twice is still one module — no sub-markers:\n" + role);
    }

    /** A module's own nested role file has the same two source sets to reconcile. */
    @Test
    void moduleOwnRoleFileMergesAcrossSourceSets() throws IOException {
        Files.createDirectories(reactorRoot.resolve("module-lib/.gemini/rules"));
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.writeString(reactorRoot.resolve("module-lib/.vibetags-roles"),
            "instrumentation = **/benchmark/**, **/agent/**\n", StandardCharsets.UTF_8);

        compileModuleSourceSet("module-lib", "main",
            "com.example.benchmark.BenchmarkRecorder", BENCHMARK_SOURCE);
        compileModuleSourceSet("module-lib", "test",
            "com.example.agent.AsyncTestAgent", AGENT_SOURCE);

        String role = Files.readString(
            reactorRoot.resolve("module-lib/.gemini/rules/instrumentation.md"), StandardCharsets.UTF_8);
        assertTrue(role.contains("com.example.benchmark.BenchmarkRecorder"),
            "the module's own role file must keep its main-source rules:\n" + role);
        assertTrue(role.contains("com.example.agent.AsyncTestAgent"),
            "and gain the test-source ones:\n" + role);
    }

    /** Compiles one source set of the root project itself (single-module layout). */
    private void compileSourceSet(String sourceSet, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        harness.writeSourceFile(
            "src/" + sourceSet + "/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    /** Compiles one source set of one module of a reactor. */
    private void compileModuleSourceSet(String module, String sourceSet, String fqn, String source)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(
            module + "/src/" + sourceSet + "/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }
}
