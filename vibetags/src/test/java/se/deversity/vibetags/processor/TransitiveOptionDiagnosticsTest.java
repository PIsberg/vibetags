package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a mistyped transitive-guardrail option does to somebody's build.
 *
 * <p>The three {@code -Avibetags.manifest.*} options are typed into a {@code pom.xml} by hand and
 * are never validated by the build tool: a typo reaches the processor as a string. Transitive
 * guardrails are an advisory feature — they add rules a dependency published — so the required
 * behaviour for every bad value is the same shape: say what is wrong, then carry on and generate.
 * A compile that fails over a mistyped advisory cap is a compile the annotation processor had no
 * business failing.
 *
 * <p>Silence is the other failure. An unreadable manifest directory that is skipped without a word
 * reads exactly like a directory that held nothing, and the guardrails a dependency published
 * quietly do not arrive.
 */
@Tag("e2e")
@DisplayName("transitive option diagnostics")
class TransitiveOptionDiagnosticsTest {

    private static final String SOURCE = """
        package com.example;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "settlement maths is regulator-audited")
        public class Ledger {
        }
        """;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** A project opted into transitive guardrails, with Claude opted in so something is written. */
    private ProcessorTestHarness optedInProject(Path root) throws IOException {
        Files.createFile(root.resolve(".vibetags-transitive"));
        Files.createFile(root.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/main/java/com/example/Ledger.java", SOURCE);
        return harness;
    }

    private static List<String> warnings(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .map(d -> d.getMessage(null))
            .toList();
    }

    private static List<String> notes(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
            .map(d -> d.getMessage(null))
            .toList();
    }

    /** The one "inherited" note, with its lookup count written as N; or none. */
    private static List<String> inheritedNotes(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return notes(diagnostics).stream()
            .filter(n -> n.contains("inherited"))
            .map(n -> n.replaceAll("after \\d+ classpath", "after N classpath"))
            .toList();
    }

    /**
     * A manifest directory holding one dependency package with one safety rule (@AISecure) and
     * three advisory ones, so a cap has something to drop and something it must keep.
     */
    private static Path manifestsWithOneSafetyAndThreeAdvisoryRules(Path root) throws IOException {
        Path manifests = Files.createDirectories(root.resolve("manifests"));
        Files.writeString(manifests.resolve("com.acme.api.json"), """
            {"manifestVersion": 1, "origin": "com.acme:api:1.0", "package": "com.acme.api",
             "rules": [
               {"annotation": "@AISecure", "members": {"aspect": "keys never leave the HSM"}},
               {"annotation": "@AIContext", "members": {"focus": "the wire format"}},
               {"annotation": "@AIPerformance", "members": {"constraint": "no allocation per call"}},
               {"annotation": "@AIThreadSafe", "members": {"strategy": "immutable"}}
             ]}""", StandardCharsets.UTF_8);
        return manifests;
    }

    @Test
    @DisplayName("the inherited note counts the rules kept, and says how many the cap dropped")
    void theInheritedNoteNamesWhatTheCapDropped(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        Path manifests = manifestsWithOneSafetyAndThreeAdvisoryRules(root);

        var diagnostics = harness.compileReturningDiagnostics(
            "-Avibetags.manifest.dir=" + manifests.toAbsolutePath(), "-Avibetags.manifest.max=1");

        assertEquals(List.of(), errors(diagnostics));
        assertEquals(List.of("VibeTags: inherited 2 guardrail(s) from dependencies (2 advisory rule(s) "
                + "dropped by -Avibetags.manifest.max=1) after N classpath lookup(s)."),
            inheritedNotes(diagnostics), "the safety rule and one advisory rule are kept");
    }

    @Test
    @DisplayName("without a cap, or with one nothing reaches, the note names no drop")
    void theInheritedNoteNamesNoDropWhenNothingWasDropped(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        Path manifests = manifestsWithOneSafetyAndThreeAdvisoryRules(root);
        String expected = "VibeTags: inherited 4 guardrail(s) from dependencies after N classpath lookup(s).";

        assertEquals(List.of(expected), inheritedNotes(harness.compileReturningDiagnostics(
            "-Avibetags.manifest.dir=" + manifests.toAbsolutePath())));
        assertEquals(List.of(expected), inheritedNotes(harness.compileReturningDiagnostics(
            "-Avibetags.manifest.dir=" + manifests.toAbsolutePath(), "-Avibetags.manifest.max=3")),
            "a cap of exactly the advisory count drops nothing");
    }

    @Test
    @DisplayName("a dependency graph that publishes nothing gets no inherited note")
    void noRulesMeansNoInheritedNote(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        Path empty = Files.createDirectories(root.resolve("manifests"));

        var diagnostics = harness.compileReturningDiagnostics(
            "-Avibetags.manifest.dir=" + empty.toAbsolutePath(), "-Avibetags.manifest.max=1");

        assertEquals(List.of(), inheritedNotes(diagnostics));
    }

    /**
     * Without a Tree API (ECJ, or a build tool's wrapper reflection cannot see through) imports
     * cannot be walked, but a manifest directory named by option needs none: its rules must arrive,
     * and the "no Tree API" note, which says nothing was discovered, must not be printed.
     */
    @Test
    @DisplayName("without a Tree API, a named manifest directory still delivers its rules")
    void withoutATreeApiANamedManifestDirectoryStillDelivers(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        Path manifests = manifestsWithOneSafetyAndThreeAdvisoryRules(root);

        var diagnostics = harness.compileWithReturningDiagnostics(new TreelessProcessor(),
            "-Avibetags.manifest.dir=" + manifests.toAbsolutePath());

        assertEquals(List.of("VibeTags: inherited 4 guardrail(s) from dependencies after N classpath lookup(s)."),
            inheritedNotes(diagnostics));
        assertTrue(notes(diagnostics).stream().noneMatch(n -> n.contains("exposes no Tree API")), notes(diagnostics).toString());
        assertTrue(Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8).contains("keys never leave the HSM"),
            "the safety rule reaches the always-loaded file");
    }

    @Test
    @DisplayName("without a Tree API and nothing named, the build says discovery did not run")
    void withoutATreeApiAndNothingNamedTheBuildSaysSo(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);

        var diagnostics = harness.compileWithReturningDiagnostics(new TreelessProcessor());

        assertEquals(List.of(), errors(diagnostics));
        assertTrue(notes(diagnostics).stream().anyMatch(n -> n.contains("transitive guardrails opted in, but this "
                + "compiler exposes no Tree API") && n.contains("-Avibetags.manifest.dir")),
            "silence would read as \"no dependency publishes guardrails\": " + notes(diagnostics));
        assertEquals(List.of(), inheritedNotes(diagnostics));
    }

    /** The processor behind a decorator that hides its delegate, and so the Tree API, from reflection. */
    @SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")
    @SupportedOptions({"vibetags.root", "vibetags.project", "vibetags.log.path", "vibetags.log.level",
                       "vibetags.cache", "vibetags.check", "vibetags.module", "vibetags.manifest.dir",
                       "vibetags.manifest.packages", "vibetags.manifest.max", "vibetags.manifest.origin"})
    private static final class TreelessProcessor extends AIGuardrailProcessor {
        @Override
        public synchronized void init(ProcessingEnvironment processingEnv) {
            super.init(new OpaqueEnvironment(processingEnv));
        }
    }

    /** A pass-through whose delegate is typed {@code Object}, so no reflective unwrap finds it. */
    private static final class OpaqueEnvironment implements ProcessingEnvironment {
        private final Object hidden;

        OpaqueEnvironment(ProcessingEnvironment delegate) {
            this.hidden = delegate;
        }

        private ProcessingEnvironment env() {
            return (ProcessingEnvironment) hidden;
        }

        @Override public Map<String, String> getOptions() { return env().getOptions(); }
        @Override public Messager getMessager() { return env().getMessager(); }
        @Override public Filer getFiler() { return env().getFiler(); }
        @Override public Elements getElementUtils() { return env().getElementUtils(); }
        @Override public Types getTypeUtils() { return env().getTypeUtils(); }
        @Override public SourceVersion getSourceVersion() { return env().getSourceVersion(); }
        @Override public Locale getLocale() { return env().getLocale(); }
    }

    private static List<String> errors(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .toList();
    }

    @Test
    @DisplayName("a non-numeric advisory cap warns and applies no limit")
    void aNonNumericManifestMaxIsReportedRatherThanFailingTheBuild(@TempDir Path root)
            throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        var diagnostics = harness.compileReturningDiagnostics(
            "-Avibetags.manifest.max=not-a-number");

        assertEquals(List.of(), errors(diagnostics),
            "a mistyped advisory cap must never fail a compile");
        assertTrue(warnings(diagnostics).stream()
                .anyMatch(w -> w.contains("manifest.max") && w.contains("not-a-number")),
            "the warning has to name the option and the value, or nobody can find the typo: "
                + warnings(diagnostics));
        assertTrue(Files.isRegularFile(root.resolve("CLAUDE.md")),
            "generation must still have happened");
    }

    @Test
    @DisplayName("a blank advisory cap is simply no cap")
    void aBlankManifestMaxIsNotAnError(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        var diagnostics = harness.compileReturningDiagnostics("-Avibetags.manifest.max=   ");

        assertEquals(List.of(), errors(diagnostics));
        assertTrue(warnings(diagnostics).stream().noneMatch(w -> w.contains("manifest.max")),
            "an omitted value is not a typo and must not warn: " + warnings(diagnostics));
    }

    @Test
    @DisplayName("a negative advisory cap is clamped rather than rejected")
    void aNegativeManifestMaxMeansNoLimit(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        var diagnostics = harness.compileReturningDiagnostics("-Avibetags.manifest.max=-5");

        assertEquals(List.of(), errors(diagnostics));
        assertTrue(warnings(diagnostics).stream().noneMatch(w -> w.contains("manifest.max")),
            "-5 parses; it is a cap of nothing, not a mistake worth a diagnostic: "
                + warnings(diagnostics));
    }

    @Test
    @DisplayName("a manifest directory holding an unreadable entry says which one")
    void anUnreadableManifestEntryIsNamed(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        Path manifests = root.resolve("manifests");
        Files.createDirectories(manifests);
        // A .json entry that is a directory: present, listed, and impossible to read as a document.
        Files.createDirectories(manifests.resolve("com.example.dep.json"));

        var diagnostics = harness.compileReturningDiagnostics(
            "-Avibetags.manifest.dir=" + manifests.toAbsolutePath());

        assertEquals(List.of(), errors(diagnostics),
            "an unreadable dependency manifest must not fail the consuming build");
        assertTrue(warnings(diagnostics).stream()
                .anyMatch(w -> w.contains("could not read dependency manifest")),
            "skipping it silently reads like the dependency published nothing: "
                + warnings(diagnostics));
    }

    @Test
    @DisplayName("a manifest directory that does not exist is not a build failure")
    void anAbsentManifestDirectoryIsTolerated(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);

        var diagnostics = harness.compileReturningDiagnostics(
            "-Avibetags.manifest.dir=" + root.resolve("no-such-directory").toAbsolutePath());

        assertEquals(List.of(), errors(diagnostics));
        assertTrue(Files.isRegularFile(root.resolve("CLAUDE.md")));
    }

    @Test
    @DisplayName("explicitly named manifest packages that resolve to nothing are tolerated")
    void unresolvableExplicitPackagesAreTolerated(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);

        var diagnostics = harness.compileReturningDiagnostics(
            "-Avibetags.manifest.packages=com.nowhere.absent, ,com.also.absent");

        assertEquals(List.of(), errors(diagnostics),
            "a dependency that was dropped from the build must not take the compile with it");
        assertTrue(Files.isRegularFile(root.resolve("CLAUDE.md")));
    }

    @Test
    @DisplayName("the manifest origin coordinate is optional")
    void anEmptyOriginIsAccepted(@TempDir Path root) throws IOException {
        ProcessorTestHarness harness = optedInProject(root);
        Files.writeString(root.resolve("CLAUDE.md"), "", StandardCharsets.UTF_8);

        var diagnostics = harness.compileReturningDiagnostics("-Avibetags.manifest.origin=   ");

        assertEquals(List.of(), errors(diagnostics));
    }
}
