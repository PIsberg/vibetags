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
 * A compilation that recompiles only some of a module's sources must not rewrite that module's
 * guardrails from what it happened to see.
 *
 * <p>Reported from a Gradle 9.4 single-module build: touching one annotated file and running an ordinary
 * incremental {@code compileJava} deleted 22 checked-in rule files and cut the whole
 * {@code security_elements} block out of {@code CLAUDE.md}, exit code 0, nothing on the console.
 * Gradle's incremental compiler hands javac a subset of the source set; the processor saw one
 * element, wrote the module's sidecar from that one, and swept every rule file it could not
 * account for. The elements were still annotated, and their sources were still on disk - this
 * round was simply never shown them.
 *
 * <p>The distinction the fix rests on is evidence, not arithmetic. An element that vanished from a
 * round <em>whose source file that round compiled</em> really did lose its annotation, and its
 * rules must go. An element whose source file this round never compiled says nothing at all, and a
 * round has to leave what it cannot see alone. That is the same rule issue #383 established for a
 * cold reactor, applied within a single module.
 */
@Tag("e2e")
class PartialRoundGuardrailLossTest {

    private static final String NL = System.lineSeparator();
    private static final String Q = String.valueOf((char) 34);

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    /**
     * The reported failure, reduced: three annotated classes, then a round that compiles one.
     */
    @Test
    void aRoundThatCompilesOneOfThreeSourcesKeepsTheOtherTwoRuleFiles(@TempDir Path root)
            throws Exception {
        Path[] sources = writeThreeAnnotatedSources(root);

        compileAll(root, sources);

        assertTrue(Files.exists(rule(root, "Alpha")), "precondition: Alpha's rule file was written");
        assertTrue(Files.exists(rule(root, "Beta")), "precondition: Beta's rule file was written");
        assertTrue(Files.exists(rule(root, "Gamma")), "precondition: Gamma's rule file was written");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        // Gradle's incremental round: one source recompiled, the other two untouched on disk.
        compileSubset(root, sources[0]);

        assertTrue(Files.exists(rule(root, "Beta")),
            "Beta is still annotated and its source is still on disk - this round was never shown "
                + "it. Deleting its rule file on that evidence is the reported data loss: the "
                + "developer commits the deletion without ever seeing it.");
        assertTrue(Files.exists(rule(root, "Gamma")),
            "Gamma's rule file went the same way as Beta's");
    }

    /** The aggregate is the other half of the loss: 27 lines and a whole block went with it. */
    @Test
    void aPartialRoundDoesNotStripTheAggregateBackToWhatItSaw(@TempDir Path root) throws Exception {
        Path[] sources = writeThreeAnnotatedSources(root);
        compileAll(root, sources);

        String full = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(full.contains("Beta"), "precondition: the full round named Beta in CLAUDE.md");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        compileSubset(root, sources[0]);

        String after = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(after.contains("Beta"),
            "CLAUDE.md was rebuilt from the one element this round compiled, so the guardrails for "
                + "the two it never saw are gone from the aggregate too. Was:" + NL + after);
        assertTrue(after.contains("Gamma"), "Gamma left the aggregate with Beta");
    }

    /** Silence is what made this expensive to find; a partial round has to say so. */
    @Test
    void aPartialRoundSaysSoOnTheConsole(@TempDir Path root) throws Exception {
        Path[] sources = writeThreeAnnotatedSources(root);
        compileAll(root, sources);
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        List<String> warnings = compileSubsetCapturingWarnings(root, sources[0]);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("did not compile")),
            "a round that refuses to regenerate has to explain itself, or the developer is left "
                + "with output that silently does not reflect their edit. Warnings were:" + NL
                + "  " + String.join(NL + "  ", warnings));
    }

    /**
     * The guard against over-correcting: an annotation genuinely taken off a class still has its
     * rule file removed, because that round DID compile the source it came from.
     */
    @Test
    void anAnnotationRemovedFromACompiledSourceStillLosesItsRuleFile(@TempDir Path root)
            throws Exception {
        Path[] sources = writeThreeAnnotatedSources(root);
        compileAll(root, sources);
        assertTrue(Files.exists(rule(root, "Beta")), "precondition: Beta had a rule file");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        // Beta loses its annotation, and every source recompiles - an ordinary full build.
        Files.writeString(sources[1],
            "package com.example;" + NL + "public class Beta {}" + NL, StandardCharsets.UTF_8);
        compileAll(root, sources);

        assertFalse(Files.exists(rule(root, "Beta")),
            "this round compiled Beta.java and found no guardrail on it. That is an edit, not an "
                + "unseen source, and the rule file has to go - otherwise the fix for the partial "
                + "round has turned every deletion into a leak.");
        assertTrue(Files.exists(rule(root, "Alpha")), "Alpha is untouched");
    }

    /**
     * A hand-written file in the granular directory is neither owned nor touched by the sweep, so
     * it must not be reported as removed. The report's third defect: {@code booking-flow},
     * {@code code-style} and {@code testing} were named in {@code removed=} while sitting intact
     * on disk, which sends the reader looking for a deletion that never happened.
     */
    @Test
    void aHandWrittenRuleFileIsNotReportedAsRemoved(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("booking-flow.md"),
            "# Booking flow" + NL + "Hand-written, no VibeTags markers anywhere." + NL,
            StandardCharsets.UTF_8);

        List<String> removed = new se.deversity.vibetags.processor.internal.GuardrailFileWriter(
            "# Generated by VibeTags | https://github.com/PIsberg/vibetags" + NL, null, null)
            .cleanupGranularDirectory(dir, ".md", java.util.Set.of());

        assertTrue(Files.exists(dir.resolve("booking-flow.md")),
            "precondition: a marker-free file is left alone on disk");
        assertEquals(List.of(), removed,
            "nothing was removed, so nothing may be reported as removed. Naming a file VibeTags "
                + "does not own inflates the destructive-sweep count and points the reader at a "
                + "deletion that did not happen.");
    }

    /**
     * Check mode must not turn a partial round into a drift report. Its promise is that the
     * committed files differ from what a build would produce; a round that cannot see the whole
     * module produces nothing to compare against, so failing there reports drift that does not
     * exist. Skipped is said out loud rather than passing quietly — a skipped gate is not a
     * passed gate.
     */
    @Test
    void checkModeOnAPartialRoundReportsSkippedRatherThanDrift(@TempDir Path root) throws Exception {
        Path[] sources = writeThreeAnnotatedSources(root);
        compileAll(root, sources);
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        List<Diagnostic<? extends javax.tools.JavaFileObject>> diagnostics =
            newHarness(root, sources[0]).compileReturningDiagnostics("-Avibetags.check=true");
        VibeTagsLogger.shutdown();

        assertTrue(diagnostics.stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            "a partial round has nothing to compare, so it must not fail the build: "
                + messages(diagnostics));
        assertTrue(diagnostics.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .anyMatch(d -> d.getMessage(null).contains("nothing was verified")),
            "silently passing a gate that never ran is worse than failing it: "
                + messages(diagnostics));
    }

    /**
     * The guard's main false-positive risk, pinned: a reactor module round sees none of its
     * siblings' sources, and that is not a partial round. Sibling sources live under a different
     * source root, which is why the unread-source half of the test is scoped to the roots this
     * compilation actually compiled from rather than to the whole tree.
     */
    @Test
    void aReactorModuleRoundIsNotAPartialRound(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve(".claude/rules"));
        compileModule(root, "module-a", "Alpha");
        assertTrue(Files.exists(root.resolve(".claude/rules/com-example-a-Alpha.md")),
            "precondition: module-a wrote its rule file");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        List<String> warnings = compileModuleCapturingWarnings(root, "module-b", "Beta");

        assertTrue(warnings.stream().noneMatch(w -> w.contains("did not compile")),
            "compiling one module of a reactor is a complete round for that module. Calling it "
                + "partial would stop every reactor build writing anything at all. Warnings were:"
                + NL + "  " + String.join(NL + "  ", warnings));
        assertTrue(Files.exists(root.resolve(".claude/rules/com-example-a-Alpha.md")),
            "and module-a keeps its rule file, as it did before this guard existed");
    }

    /**
     * The build that actually reported this hands the processor a decorated
     * {@code ProcessingEnvironment}, not javac's own — that is what an incremental-processing
     * build tool does, and it is why {@code Trees.instance} is unavailable under Gradle
     * (issue #331). A guard that only resolves source files through the Tree API would find no
     * source roots there, conclude nothing, and be silently inert in exactly the builds it exists
     * for. Green with the code never running is the failure mode this pins.
     */
    @Test
    void theGuardStillFiresWhenTheEnvironmentIsWrapped(@TempDir Path root) throws Exception {
        Path[] sources = writeThreeAnnotatedSources(root);
        newHarness(root, sources).compileWith(new WrappedEnvProcessor());
        VibeTagsLogger.shutdown();
        assertTrue(Files.exists(rule(root, "Beta")), "precondition: the full round wrote Beta");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        newHarness(root, sources[0]).compileWith(new WrappedEnvProcessor());
        VibeTagsLogger.shutdown();

        assertTrue(Files.exists(rule(root, "Beta")),
            "under a wrapped environment the ledger has to reach the source files through "
                + "Elements.getFileObjectOf, exactly as module identity does. If this fails the "
                + "guard is inert under Gradle, which is the only place the bug was ever seen");
    }

    /** The processor as an incremental-processing build tool runs it: not javac's own environment. */
    @javax.annotation.processing.SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    @javax.annotation.processing.SupportedOptions({"vibetags.root", "vibetags.project",
        "vibetags.log.path", "vibetags.log.level", "vibetags.cache", "vibetags.check",
        "vibetags.module"})
    private static final class WrappedEnvProcessor extends AIGuardrailProcessor {
        @Override
        public synchronized void init(javax.annotation.processing.ProcessingEnvironment env) {
            super.init(new DelegatingProcessingEnvironment(env));
        }
    }

    /** Pure pass-through; the only thing that matters is that it is not javac's own class. */
    private record DelegatingProcessingEnvironment(
            javax.annotation.processing.ProcessingEnvironment delegate)
            implements javax.annotation.processing.ProcessingEnvironment {

        @Override public java.util.Map<String, String> getOptions() { return delegate.getOptions(); }
        @Override public javax.annotation.processing.Messager getMessager() { return delegate.getMessager(); }
        @Override public javax.annotation.processing.Filer getFiler() { return delegate.getFiler(); }
        @Override public javax.lang.model.util.Elements getElementUtils() { return delegate.getElementUtils(); }
        @Override public javax.lang.model.util.Types getTypeUtils() { return delegate.getTypeUtils(); }
        @Override public javax.lang.model.SourceVersion getSourceVersion() { return delegate.getSourceVersion(); }
        @Override public java.util.Locale getLocale() { return delegate.getLocale(); }
    }

    // ---------------------------------------------------------------- helpers

    private static String messages(List<Diagnostic<? extends javax.tools.JavaFileObject>> diagnostics) {
        return NL + "  " + diagnostics.stream().map(d -> d.getKind() + ": " + d.getMessage(null))
            .collect(java.util.stream.Collectors.joining(NL + "  "));
    }

    private static void compileModule(Path root, String module, String type) throws IOException {
        ProcessorTestHarness harness = newModuleHarness(root, module, type);
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    private static List<String> compileModuleCapturingWarnings(Path root, String module, String type)
            throws IOException {
        List<String> warnings = newModuleHarness(root, module, type).compileReturningDiagnostics()
            .stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING
                || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }

    private static ProcessorTestHarness newModuleHarness(Path root, String module, String type)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.touchOptIn("CLAUDE.md");
        harness.touchOptIn(".claude/rules/.vibetags");
        Files.createDirectories(root.resolve(module));
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        String pkg = "com.example." + module.substring(module.length() - 1);
        harness.writeSourceFile(
            module + "/src/main/java/" + pkg.replace('.', '/') + "/" + type + ".java",
            "package " + pkg + ";" + NL
                + "import se.deversity.vibetags.annotations.AIContext;" + NL
                + "@AIContext(focus = " + Q + type + "-routing" + Q + ")" + NL
                + "public class " + type + " {}" + NL);
        return harness;
    }

    private static Path rule(Path root, String simpleName) {
        return root.resolve(".claude/rules/com-example-" + simpleName + ".md");
    }

    private static Path[] writeThreeAnnotatedSources(Path root) throws IOException {
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.createFile(root.resolve("CLAUDE.md"));
        return new Path[]{
            write(root, "Alpha", "alpha-routing"),
            write(root, "Beta", "beta-routing"),
            write(root, "Gamma", "gamma-routing"),
        };
    }

    private static Path write(Path root, String type, String focus) throws IOException {
        Path p = root.resolve("src/main/java/com/example/" + type + ".java");
        Files.createDirectories(p.getParent());
        Files.writeString(p,
            "package com.example;" + NL
                + "import se.deversity.vibetags.annotations.AIContext;" + NL
                + "@AIContext(focus = " + Q + focus + Q + ")" + NL
                + "public class " + type + " {}" + NL,
            StandardCharsets.UTF_8);
        return p;
    }

    private static void compileAll(Path root, Path... sources) throws IOException {
        newHarness(root, sources).compile();
        VibeTagsLogger.shutdown();
    }

    private static void compileSubset(Path root, Path source) throws IOException {
        newHarness(root, source).compile();
        VibeTagsLogger.shutdown();
    }

    private static List<String> compileSubsetCapturingWarnings(Path root, Path source)
            throws IOException {
        List<String> warnings = newHarness(root, source).compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING
                || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }

    private static ProcessorTestHarness newHarness(Path root, Path... sources) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.touchOptIn("CLAUDE.md");
        harness.touchOptIn(".claude/rules/.vibetags");
        for (Path s : sources) {
            harness.addSourceFile(s);
        }
        return harness;
    }
}
