package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The diagnostic that makes a silently-shrinking guardrail file visible.
 *
 * <p>Every multi-module defect VibeTags has shipped failed the same way — well-formed output, green
 * build, guardrails quietly gone (#278, #330, #331). {@code DestructiveRewriteWarner} exists so the
 * next one announces itself in the build log instead of being found by bisecting a diff.
 *
 * <p>The two halves of the contract are equally important: it fires on a wholesale replacement, and
 * it stays silent on ordinary work. A warning that cries wolf is one people configure away.
 */
@Tag("e2e")
class DestructiveRewriteWarningTest {

    private static final String LOCKED_A = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "first")
        public class Alpha {}
        """;

    private static final String LOCKED_B = """
        package com.example;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "second")
        public class Beta {}
        """;

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    private List<Diagnostic<? extends JavaFileObject>> compile(String sourceSet, String simpleName, String source)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        harness.writeSourceFile("src/" + sourceSet + "/java/com/example/" + simpleName + ".java", source);
        return harness.compileReturningDiagnostics();
    }

    private static boolean warns(List<Diagnostic<? extends JavaFileObject>> diagnostics, String needle) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .anyMatch(d -> d.getMessage(null).contains(needle));
    }

    @Test
    void warnsWhenAModulesElementsAreAllReplacedByADisjointSet() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile("main", "Alpha", LOCKED_A);
        // Same module id, same source set, and not one element in common: this is what a round that
        // could not see the sources looks like, not what editing an annotation looks like.
        List<Diagnostic<? extends JavaFileObject>> second = compile("main", "Beta", LOCKED_B);

        assertTrue(warns(second, "completely different set"),
            "a wholesale replacement must be reported, not applied silently: " + messages(second));
        assertTrue(warns(second, "com-example-Alpha"),
            "the warning must name what is being lost: " + messages(second));
    }

    @Test
    void staysSilentWhenAnElementSurvives() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile("main", "Alpha", LOCKED_A);
        // Alpha is still there, with an edited reason — ordinary work, no diagnostic.
        List<Diagnostic<? extends JavaFileObject>> second = compile("main", "Alpha", """
            package com.example;
            import se.deversity.vibetags.annotations.AILocked;
            @AILocked(reason = "first, reworded")
            public class Alpha {}
            """);

        assertFalse(warns(second, "completely different set"),
            "editing an annotation is not a destructive rewrite: " + messages(second));
    }

    @Test
    void staysSilentOnAFirstBuild() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        List<Diagnostic<? extends JavaFileObject>> first = compile("main", "Alpha", LOCKED_A);
        assertFalse(warns(first, "completely different set"),
            "there is nothing to lose on a first build: " + messages(first));
    }

    /**
     * The source-set split (#330) means the test round no longer looks like a replacement of the
     * main round — it is a different sidecar id entirely, so no warning either.
     */
    @Test
    void staysSilentWhenTheOtherSourceSetCompiles() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile("main", "Alpha", LOCKED_A);
        List<Diagnostic<? extends JavaFileObject>> testRound = compile("test", "AlphaTest", """
            package com.example;
            import se.deversity.vibetags.annotations.AIParallelTests;
            @AIParallelTests(reason = "no shared state")
            public class AlphaTest {}
            """);

        assertFalse(warns(testRound, "completely different set"),
            "compile and test-compile own separate sidecars; neither replaces the other: "
                + messages(testRound));
    }

    /**
     * A round that deletes more rules than it writes is describing a compilation that could not see
     * the sources, not one whose annotations were deleted — the arithmetic of issue #330, where the
     * test round wrote one rule file and swept eleven away.
     */
    @Test
    void warnsWhenASweepRemovesMoreThanTheRoundWrote() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        ProcessorTestHarness first = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        for (String name : new String[]{"Alpha", "Gamma", "Delta"}) {
            first.writeSourceFile("src/main/java/com/example/" + name + ".java", """
                package com.example;
                import se.deversity.vibetags.annotations.AILocked;
                @AILocked(reason = "%s")
                public class %s {}
                """.formatted(name, name));
        }
        first.compile();
        assertEquals(3, ruleFileCount(), "precondition: three scoped rule files");

        // One annotated class where there were three: two files go, one is written.
        List<Diagnostic<? extends JavaFileObject>> sweep = compile("main", "Beta", LOCKED_B);

        assertTrue(warns(sweep, "while writing only 1"),
            "a sweep that removes more than it writes must be reported: " + messages(sweep));
    }

    /** Deleting one annotation from a module full of them is ordinary work, and stays quiet. */
    @Test
    void staysSilentWhenASweepRemovesFewerThanTheRoundWrote() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        ProcessorTestHarness first = new ProcessorTestHarness(root, false);
        for (String name : new String[]{"Alpha", "Gamma", "Delta"}) {
            first.writeSourceFile("src/main/java/com/example/" + name + ".java", """
                package com.example;
                import se.deversity.vibetags.annotations.AILocked;
                @AILocked(reason = "%s")
                public class %s {}
                """.formatted(name, name));
        }
        first.compile();

        // Two of the three keep their annotation: one file removed, two written.
        ProcessorTestHarness second = new ProcessorTestHarness(root, false);
        for (String name : new String[]{"Alpha", "Gamma"}) {
            second.writeSourceFile("src/main/java/com/example/" + name + ".java", """
                package com.example;
                import se.deversity.vibetags.annotations.AILocked;
                @AILocked(reason = "%s")
                public class %s {}
                """.formatted(name, name));
        }
        List<Diagnostic<? extends JavaFileObject>> sweep = second.compileReturningDiagnostics();

        assertFalse(warns(sweep, "while writing only"),
            "removing one annotation of three is not a destructive rewrite: " + messages(sweep));
    }

    /**
     * The fresh-clone case: {@code .vibetags-mod-*} is gitignored, so the first module of a reactor
     * to compile after a clone has no sibling sidecars on disk. Its exclusion list is therefore
     * empty of every other module's stems, and a sweep judged on that evidence deletes rule files
     * whose sources it was never shown.
     *
     * <p>This is not hypothetical arithmetic. On a cold clone of this repository,
     * {@code cd examples/multimodule && mvn -B -pl core clean compile} deleted 256 committed rule
     * files and exited 0. The full reactor hid it, because a later module rewrote them all before
     * the build ended.
     *
     * <p>A round that cannot see the project has no basis for the word "orphan", so it must not
     * sweep at all. Deleting on no information is the failure; the warning was only ever the symptom.
     */
    @Test
    void coldReactorModule_withNoSiblingSidecars_leavesTheOtherModulesRulesAlone() throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        compileModule("module-core", "com.example.core.IrNode", "Core IR node");
        compileModule("module-cli", "com.example.cli.Cli", "CLI entry point");

        Path siblingRule = root.resolve(".claude/rules/com-example-cli-Cli.md");
        assertTrue(Files.exists(siblingRule), "precondition: both modules own a scoped rule file");

        // The fresh clone: rule files are committed, sidecars are not.
        deleteAllSidecars();
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        List<Diagnostic<? extends JavaFileObject>> cold =
            compileModuleReturningDiagnostics("module-core", "com.example.core.IrNode", "Core IR node");

        assertTrue(Files.exists(siblingRule),
            "a reactor module with no sibling sidecars cannot tell an orphan from a file it simply "
                + "never saw, so it must delete nothing: " + messages(cold));
        assertFalse(warns(cold, "while writing only"),
            "and with nothing swept there is nothing to report: " + messages(cold));
    }

    private void deleteAllSidecars() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(root)) {
            for (Path p : files.filter(f -> f.getFileName().toString().startsWith(".vibetags-mod-")).toList()) {
                Files.delete(p);
            }
        }
    }

    private void compileModule(String module, String fqn, String reason) throws IOException {
        compileModuleReturningDiagnostics(module, fqn, reason);
    }

    private List<Diagnostic<? extends JavaFileObject>> compileModuleReturningDiagnostics(
            String module, String fqn, String reason) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        int lastDot = fqn.lastIndexOf('.');
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java",
            "package " + fqn.substring(0, lastDot) + ";\n"
                + "import se.deversity.vibetags.annotations.AILocked;\n"
                + "@AILocked(reason = \"" + reason + "\")\n"
                + "public class " + fqn.substring(lastDot + 1) + " {}\n");
        List<Diagnostic<? extends JavaFileObject>> diagnostics = harness.compileReturningDiagnostics();
        VibeTagsLogger.shutdown();
        return diagnostics;
    }

    private int ruleFileCount() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(root.resolve(".claude/rules"))) {
            return (int) files.filter(Files::isRegularFile).count();
        }
    }

    private static String messages(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        StringBuilder sb = new StringBuilder("\n");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            sb.append("  ").append(d.getKind()).append(": ").append(d.getMessage(null)).append('\n');
        }
        return sb.toString();
    }
}
