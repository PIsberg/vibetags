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

    /**
     * Generated-source trees are left out of the walk by their {@code generated} path segment, but
     * the segment was looked for in the whole absolute path, including the directories the
     * checkout sits in. A project under {@code /ci/generated/} (or any workspace so named) had no
     * source root left to measure against, the guard concluded every round was complete, and an
     * incremental round deleted the rule files it was never shown.
     */
    @Test
    void theGuardStillFiresWhenTheCheckoutSitsUnderADirectoryNamedGenerated(@TempDir Path dir)
            throws Exception {
        Path root = dir.resolve("generated").resolve("proj");
        Files.createDirectories(root);
        Path[] sources = writeThreeAnnotatedSources(root);

        compileAll(root, sources);
        assertTrue(Files.exists(rule(root, "Beta")), "precondition: Beta's rule file was written");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        compileSubset(root, sources[0]);

        assertTrue(Files.exists(rule(root, "Beta")),
            "the directory the checkout sits in is not a generated-source tree; the round was "
                + "partial and must not delete Beta's rule file");
        assertTrue(Files.exists(rule(root, "Gamma")), "Gamma's rule file went the same way");
    }

    /**
     * Condition (1) asks whether a sidecar describing this compilation's source tree names an
     * element the round did not produce. The test source set's sidecar describes the same module
     * directory, and a main round never produces test elements, so once the tests had compiled,
     * (1) held on every main round. An annotated main source excluded from the build (a
     * {@code <excludes>} entry, a file kept for reference) then made every main round "partial",
     * and main never wrote again: the edit below was never generated, with a warning on each build.
     */
    @Test
    void aMainRoundIsNotPartialBecauseTheTestSourceSetRecordsElementsOfItsOwn(@TempDir Path root)
            throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve("core/pom.xml"), "<project><artifactId>core</artifactId></project>",
            StandardCharsets.UTF_8);
        Path alpha = lockedSource(root, "core/src/main/java/com/example/Alpha.java", "Alpha", "alpha-v1");
        lockedSource(root, "core/src/main/java/com/example/Excluded.java", "Excluded", "excluded");
        Path alphaTest = lockedSource(root, "core/src/test/java/com/example/AlphaTest.java", "AlphaTest", "fixture");
        compileSources(root, alpha);
        compileSources(root, alphaTest);
        assertTrue(Files.exists(root.resolve(".vibetags-mod-core__test")),
            "precondition: the test source set recorded its own sidecar");
        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("alpha-v1"),
            "precondition: the main round wrote Alpha's lock");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        lockedSource(root, "core/src/main/java/com/example/Alpha.java", "Alpha", "alpha-v2");
        compileSources(root, alpha);

        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("alpha-v2"),
            "the main round compiled every main source its own sidecar recorded; the test source "
                + "set's elements are not missing from it, they were never its to produce");
    }

    /**
     * Condition (1) consults every sidecar whose module directory contains this round's source
     * root, and the reactor root's sidecar has the whole tree as its directory. A root that compiles
     * sources of its own records elements no module round produces, so while that sidecar existed
     * (1) held for every module, and a module keeping one annotated source out of its build was
     * refused as partial on every compile, its first included. The root is an ancestor module, not
     * a record of this one.
     */
    @Test
    void theRootsOwnSidecarDoesNotMakeAModuleRoundPartial(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>parent</artifactId></project>",
            StandardCharsets.UTF_8);
        Path rootSource = lockedSource(root, "src/main/java/com/example/Bootstrap.java", "Bootstrap", "bootstrap");
        compileSources(root, rootSource);
        assertTrue(Files.exists(root.resolve(".vibetags-mod-_root_")), "precondition: the root recorded its sidecar");

        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve("core/pom.xml"), "<project><artifactId>core</artifactId></project>",
            StandardCharsets.UTF_8);
        Path alpha = lockedSource(root, "core/src/main/java/com/example/Alpha.java", "Alpha", "alpha-lock");
        lockedSource(root, "core/src/main/java/com/example/Excluded.java", "Excluded", "excluded"); // never compiled
        compileSources(root, alpha);

        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("alpha-lock"),
            "core's round compiled every source core has on record; the root's elements are not core's");
    }

    /**
     * Condition (2) walked only the source roots this round compiled from. A source set with a
     * second root ({@code src/main/extra}, added by build-helper or a Gradle {@code srcDir}) that is
     * compiled incrementally from the first root alone never had the second walked: its elements
     * were missing, no unread file explained them, and the round was taken for a deletion. Beta's
     * rule file was swept although Beta is still annotated on disk (#1060).
     */
    @Test
    void aRoundThatCompilesOneOfTwoSourceRootsKeepsTheOtherRootsRuleFiles(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.createFile(root.resolve("CLAUDE.md"));
        Path alpha = write(root, "Alpha", "alpha-routing");
        Path beta = writeUnder(root, "src/main/extra", "Beta", "@AIContext(focus = " + Q + "beta-routing" + Q + ")");
        compileAll(root, alpha, beta);
        assertTrue(Files.exists(rule(root, "Beta")), "precondition: Beta's rule file was written");
        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("Beta"),
            "precondition: the full round indexed Beta in CLAUDE.md");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        compileSubset(root, alpha);

        assertTrue(Files.exists(rule(root, "Beta")),
            "Beta is still annotated in src/main/extra, a root this round compiled nothing from; "
                + "the round was never shown it and must not retire its rule file");
        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("Beta"),
            "the aggregate must keep indexing Beta as well");
    }

    /**
     * The same source set with its second root outside the module directory: build-helper's
     * {@code add-source} pointing at {@code ../shared/src}, or a Gradle {@code srcDir("../common")}.
     * The walk of the module directory added for #1060 never reaches it, so a round compiled from
     * the module's own root read Beta as deleted and rewrote CLAUDE.md and app's sidecar without it
     * (#1065). The root rule file is not the measure here: a module round never sweeps the shared
     * root (#383), so it survives either way.
     */
    @Test
    void aRoundThatCompilesOneRootKeepsTheGuardrailsOfARootOutsideTheModule(@TempDir Path root)
            throws Exception {
        Path[] sources = writeModuleWithAnOutsideRoot(root);
        compileAll(root, sources);
        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("Beta"),
            "precondition: the full round indexed Beta in CLAUDE.md");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        List<String> warnings = compileSubsetCapturingWarnings(root, sources[0]);

        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("Beta"),
            "Beta is still annotated in shared/src, a root of app's source set outside app/; the round "
                + "compiled nothing from it and must not drop it from CLAUDE.md");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("did not compile every annotated source")
                && w.contains("Beta.java")),
            "the round must say it was partial and name the unread source: " + warnings);
    }

    /**
     * The control: with Beta's file deleted from the outside root nothing explains its missing
     * element, so the round is complete and CLAUDE.md stops naming Beta.
     */
    @Test
    void aSourceDeletedFromARootOutsideTheModuleIsStillRetired(@TempDir Path root) throws Exception {
        Path[] sources = writeModuleWithAnOutsideRoot(root);
        compileAll(root, sources);
        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("Beta"),
            "precondition: the full round indexed Beta in CLAUDE.md");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        Files.delete(sources[1]);
        List<String> warnings = compileSubsetCapturingWarnings(root, sources[0]);

        assertFalse(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("Beta"),
            "Beta's source is gone from disk; CLAUDE.md must stop naming it");
        assertTrue(warnings.stream().noneMatch(w -> w.contains("did not compile every annotated source")),
            "a genuine deletion is not a partial round: " + warnings);
    }

    /**
     * The other direction of #1065: a round compiled from the outside root alone. No build file
     * above {@code shared/src} names app, so the round resolved to the reactor root (or, with no
     * parent pom, to a content hash), read the directory after {@code src/} as its source set, and
     * was never measured against app's sidecar. It rewrote CLAUDE.md from Beta alone and Alpha's
     * guardrail left it, with no warning when a parent pom was there. App's sidecar recorded
     * {@code shared/src} as one of its roots, which is what now files the round under app.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "parent pom: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void aRoundCompiledFromTheOutsideRootAloneKeepsTheModulesOtherGuardrails(boolean parentPom,
                                                                              @TempDir Path root)
            throws Exception {
        Path[] sources = writeModuleWithAnOutsideRoot(root);
        if (parentPom) {
            Files.writeString(root.resolve("pom.xml"),
                "<project><artifactId>parent</artifactId></project>", StandardCharsets.UTF_8);
        }
        compileAll(root, sources);
        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("Alpha"),
            "precondition: the full round indexed Alpha in CLAUDE.md");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        List<String> warnings = compileSubsetCapturingWarnings(root, sources[1]);

        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("Alpha"),
            "Alpha is still annotated in app/src/main/java; a round compiled from shared/src alone "
                + "must not drop it from CLAUDE.md");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("did not compile every annotated source")
                && w.contains("Alpha.java")),
            "the round must say it was partial and name the unread source: " + warnings);
    }

    /**
     * The limits of that rule. A recorded root with a build file of its own is its own module, even
     * when app compiles it too: a round of {@code shared} alone is filed under {@code shared}, not
     * refused as a partial round of app. {@code shared/java} is the case that reaches the rule; a
     * root under {@code shared/src} is settled by the build file before any sidecar is read.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"shared/src", "shared/java"})
    void aRecordedRootWithItsOwnBuildFileStaysItsOwnModule(String outsideRoot, @TempDir Path root)
            throws Exception {
        Path[] sources = writeModuleWithAnOutsideRoot(root, outsideRoot);
        Files.writeString(root.resolve("shared/pom.xml"),
            "<project><artifactId>shared</artifactId></project>", StandardCharsets.UTF_8);
        compileAll(root, sources);
        assertTrue(Files.exists(root.resolve(".vibetags-mod-app")), "precondition: app's round recorded");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        List<String> warnings = compileSubsetCapturingWarnings(root, sources[1]);

        assertTrue(warnings.stream().noneMatch(w -> w.contains("did not compile every annotated source")),
            "shared's round compiled all of shared: " + warnings);
        assertTrue(Files.exists(root.resolve(".vibetags-mod-shared")),
            "the round belongs to shared, whose pom.xml says so");
    }

    /** And a root two modules recorded is neither one's to claim. */
    @Test
    void aRootTwoModulesRecordedIsClaimedByNeither(@TempDir Path root) throws Exception {
        Path[] sources = writeModuleWithAnOutsideRoot(root);
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>parent</artifactId></project>", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/pom.xml"),
            "<project><artifactId>lib</artifactId></project>", StandardCharsets.UTF_8);
        Path gamma = writeUnder(root, "lib/src/main/java", "Gamma", "@AIContext(focus = " + Q + "gamma-routing" + Q + ")");
        compileAll(root, sources);
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        compileAll(root, gamma, sources[1]);
        assertTrue(Files.exists(root.resolve(".vibetags-mod-lib")), "precondition: lib's round recorded");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        List<String> warnings = compileSubsetCapturingWarnings(root, sources[1]);

        assertTrue(warnings.stream().noneMatch(w -> w.contains("did not compile every annotated source")),
            "app and lib both compile shared/src; the round must not be filed under either: " + warnings);
    }

    /**
     * Module {@code app} (its own {@code pom.xml}) with Alpha in {@code app/src/main/java} and Beta
     * in {@code shared/src}, a sibling directory with no build file of its own. Alpha comes first,
     * so the round resolves to {@code app}.
     */
    private static Path[] writeModuleWithAnOutsideRoot(Path root) throws IOException {
        return writeModuleWithAnOutsideRoot(root, "shared/src");
    }

    /** As above, with Beta under {@code outsideRoot}. */
    private static Path[] writeModuleWithAnOutsideRoot(Path root, String outsideRoot) throws IOException {
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/pom.xml"),
            "<project><artifactId>app</artifactId></project>", StandardCharsets.UTF_8);
        return new Path[]{
            writeUnder(root, "app/src/main/java", "Alpha", "@AIContext(focus = " + Q + "alpha-routing" + Q + ")"),
            writeUnder(root, outsideRoot, "Beta", "@AIContext(focus = " + Q + "beta-routing" + Q + ")"),
        };
    }

    /**
     * Two modules that share one directory, as in a Gradle build configured entirely from the root
     * build file: neither {@code core/} nor {@code app/} has a build file, so both resolve to the
     * root and only {@code -Avibetags.module} tells them apart. Each module's sidecar then "describes"
     * the other's rounds, so app's elements read as missing in core's round. The walk added for
     * #1060 looked at the whole shared directory, found app's uncompiled source, and refused every
     * core round as partial, so a clean build dropped app's guardrail from CLAUDE.md
     * ({@code examples/gradle-shared-buildfile}, Gradle Build on PR #1064).
     */
    @Test
    void aModuleSharingItsDirectoryWithAnotherIsNotTakenForAPartialRound(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.writeString(root.resolve("build.gradle"), "subprojects {}" + NL, StandardCharsets.UTF_8);
        List<String> appWarnings = compileSharedDirectoryModule(root, "app", "Runner");
        List<String> coreWarnings = compileSharedDirectoryModule(root, "core", "Ledger");

        String claude = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(appWarnings.stream().noneMatch(w -> w.contains("did not compile every annotated source")),
            "app's round compiled all of app: " + appWarnings);
        assertTrue(coreWarnings.stream().noneMatch(w -> w.contains("did not compile every annotated source")),
            "core's round compiled all of core; app's source in the shared directory is app's, not core's: "
                + coreWarnings);
        assertTrue(claude.contains("com.example.gsb.app.Runner") && claude.contains("com.example.gsb.core.Ledger"),
            "both modules' guardrails belong in CLAUDE.md after a full build. Was:" + NL + claude);
    }

    private static List<String> compileSharedDirectoryModule(Path root, String module, String type)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        String pkg = "com.example.gsb." + module;
        harness.writeSourceFile(
            module + "/src/main/java/" + pkg.replace('.', '/') + "/" + type + ".java",
            "package " + pkg + ";" + NL
                + "import se.deversity.vibetags.annotations.AILocked;" + NL
                + "@AILocked(reason = " + Q + type + " wire format" + Q + ")" + NL
                + "public class " + type + " {}" + NL);
        List<String> warnings = harness.compileReturningDiagnostics("-Avibetags.module=" + module)
            .stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING
                || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }

    /**
     * The other half of the same rule: with Beta's file deleted from the second root, nothing
     * explains its missing element, so the round is complete and Beta's rule file is retired.
     */
    @Test
    void aSourceDeletedFromTheOtherRootStillHasItsRuleFileRetired(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.createFile(root.resolve("CLAUDE.md"));
        Path alpha = write(root, "Alpha", "alpha-routing");
        Path beta = writeUnder(root, "src/main/extra", "Beta", "@AIContext(focus = " + Q + "beta-routing" + Q + ")");
        compileAll(root, alpha, beta);
        assertTrue(Files.exists(rule(root, "Beta")), "precondition: Beta's rule file was written");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        Files.delete(beta);
        compileSubset(root, alpha);

        assertFalse(Files.exists(rule(root, "Beta")),
            "Beta's source is gone from disk; its rule file must be retired, not kept as partial");
    }

    private static Path lockedSource(Path root, String relative, String type, String reason) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p,
            "package com.example;" + NL
                + "import se.deversity.vibetags.annotations.AILocked;" + NL
                + "@AILocked(reason = " + Q + reason + Q + ")" + NL
                + "public class " + type + " {}" + NL,
            StandardCharsets.UTF_8);
        return p;
    }

    private static void compileSources(Path root, Path... sources) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        for (Path s : sources) {
            harness.addSourceFile(s);
        }
        harness.compile();
        VibeTagsLogger.shutdown();
    }

    /**
     * A generated-source tree is an output, and a file left in it by an earlier run is not evidence
     * about what this round was shown. Walked as a source root, a stale generated file that names
     * the annotations made every round partial once an annotation was genuinely removed, so the
     * removal was refused until a clean build (#866). Gradle's {@code build/generated} was left out
     * by its {@code generated} segment; Maven's {@code target/generated-sources} was not.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "target/generated-sources/annotations", "build/generated/sources/annotationProcessor/java/main"})
    void aStaleGeneratedSourceDoesNotMakeARoundPartial(String generatedRoot, @TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.createFile(root.resolve("CLAUDE.md"));
        Path alpha = write(root, "Alpha", "alpha-routing");
        Path gen = writeUnder(root, generatedRoot, "Gen", "@AIContext(focus = " + Q + "generated" + Q + ")");
        compileAll(root, alpha, gen);
        assertTrue(Files.exists(rule(root, "Alpha")), "precondition: Alpha's rule file was written");

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();
        // The annotation is removed for real. The generator no longer emits Gen, but a build that
        // does not clean leaves the old file on disk; it emits Gen2 instead.
        Files.writeString(alpha, "package com.example;" + NL + "public class Alpha {}" + NL, StandardCharsets.UTF_8);
        Path gen2 = writeUnder(root, generatedRoot, "Gen2", "");
        compileAll(root, alpha, gen2);

        assertFalse(Files.exists(rule(root, "Alpha")),
            "Alpha's annotation was removed from a source this round compiled; a stale file under "
                + generatedRoot + " must not make the round partial and keep the rule file");
    }

    private static Path writeUnder(Path root, String sourceRoot, String type, String annotation) throws IOException {
        Path p = root.resolve(sourceRoot + "/com/example/" + type + ".java");
        Files.createDirectories(p.getParent());
        Files.writeString(p,
            "package com.example;" + NL
                + "import se.deversity.vibetags.annotations.AIContext;" + NL
                + annotation + NL
                + "public class " + type + " {}" + NL,
            StandardCharsets.UTF_8);
        return p;
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

    /**
     * Invariant 17 has to cover the routed file too. With {@code TESTING.md} present a test
     * round's non-safety guardrails live there, so a partial test round that rewrote it from the
     * subset it happened to compile would delete exactly the guardrails invariant 17 exists to
     * protect - only in a file the reported Gradle incident never touched, because routing did not
     * exist yet. The safety half stays in {@code CLAUDE.md} and is asserted in the same round, so
     * one partial round is checked against both destinations at once.
     */
    @Test
    void aPartialTestRoundStripsNeitherTestingMdNorTheRoutedAggregate(@TempDir Path root)
            throws Exception {
        Path[] tests = writeThreeAnnotatedTestSources(root);
        compileAll(root, tests);

        String routedFull = Files.readString(root.resolve("TESTING.md"), StandardCharsets.UTF_8);
        String aggregateFull = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(routedFull.contains("beta-fixture-rule"),
            "precondition: the full test round routed Beta's @AIContext to TESTING.md. Was:" + NL
                + routedFull);
        assertTrue(aggregateFull.contains("beta-golden-bytes"),
            "precondition: Beta's @AILocked is safety-tier and stayed in CLAUDE.md. Was:" + NL
                + aggregateFull);

        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        // Gradle's incremental round, in the test source set: one of the three recompiled.
        compileSubset(root, tests[0]);

        String routedAfter = Files.readString(root.resolve("TESTING.md"), StandardCharsets.UTF_8);
        assertTrue(routedAfter.contains("beta-fixture-rule"),
            "TESTING.md was rebuilt from the one test class this round compiled, so the routed "
                + "guardrails of the two it never saw are gone. Was:" + NL + routedAfter);
        assertTrue(routedAfter.contains("gamma-fixture-rule"),
            "Gamma's routed guardrail went the same way as Beta's. Was:" + NL + routedAfter);

        String aggregateAfter = Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(aggregateAfter.contains("beta-golden-bytes"),
            "the safety half is written by the same round; a partial test round must leave it "
                + "alone too. Was:" + NL + aggregateAfter);
        assertTrue(aggregateAfter.contains("gamma-golden-bytes"),
            "Gamma's safety guardrail went the same way as Beta's. Was:" + NL + aggregateAfter);
    }

    /** Same silence problem: a partial test round has to say why TESTING.md did not move. */
    @Test
    void aPartialTestRoundSaysSoOnTheConsole(@TempDir Path root) throws Exception {
        Path[] tests = writeThreeAnnotatedTestSources(root);
        compileAll(root, tests);
        ProcessorTestHarness.awaitFilesystemTick(root);
        VibeTagsLogger.shutdown();

        List<String> warnings = compileSubsetCapturingWarnings(root, tests[0]);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("did not compile")),
            "a routed round that refuses to regenerate has to explain itself, or the developer "
                + "reads a TESTING.md that does not reflect their edit. Warnings were:" + NL
                + "  " + String.join(NL + "  ", warnings));
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

    /**
     * Three test classes, each carrying one routed guardrail and one safety guardrail, with
     * {@code TESTING.md} opted in. {@code src/test/java} is what makes the round a test round.
     */
    private static Path[] writeThreeAnnotatedTestSources(Path root) throws IOException {
        Files.createDirectories(root.resolve(".claude/rules"));
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>ledger</artifactId></project>", StandardCharsets.UTF_8);
        return new Path[]{
            writeTest(root, "AlphaTest", "alpha"),
            writeTest(root, "BetaTest", "beta"),
            writeTest(root, "GammaTest", "gamma"),
        };
    }

    private static Path writeTest(Path root, String type, String stem) throws IOException {
        Path p = root.resolve("src/test/java/com/example/" + type + ".java");
        Files.createDirectories(p.getParent());
        Files.writeString(p,
            "package com.example;" + NL
                + "import se.deversity.vibetags.annotations.AIContext;" + NL
                + "import se.deversity.vibetags.annotations.AILocked;" + NL
                + "@AIContext(focus = " + Q + stem + "-fixture-rule" + Q + ")" + NL
                + "@AILocked(reason = " + Q + stem + "-golden-bytes" + Q + ")" + NL
                + "public class " + type + " {}" + NL,
            StandardCharsets.UTF_8);
        return p;
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
