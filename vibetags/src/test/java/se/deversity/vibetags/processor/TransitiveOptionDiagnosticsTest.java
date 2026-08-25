package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
