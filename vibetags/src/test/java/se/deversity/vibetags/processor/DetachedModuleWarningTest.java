package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
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
 * A module that compiles as its own root while a reactor above it declares it
 * (<a href="https://github.com/PIsberg/vibetags/issues/296">issue #296</a>).
 *
 * <p>The failure is invisible by construction: the module renders a complete, correct set of
 * guardrail files into its own directory, contributes nothing to the reactor's merged files, and
 * the build stays green. The usual cause is a module that overrides the compiler plugin's
 * {@code compilerArgs} or {@code annotationProcessorPaths} and so never inherits
 * {@code -Avibetags.root}.
 */
class DetachedModuleWarningTest {

    private static final String SOURCE = """
        package com.example.core;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "core model")
        public class IrNode {}
        """;

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /**
     * Compiles {@code module} with the VibeTags root pointed at the module itself — the detached
     * configuration — and returns the diagnostics.
     */
    private List<Diagnostic<? extends JavaFileObject>> compileDetached(Path module) throws IOException {
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"),
            "<project><artifactId>" + module.getFileName() + "</artifactId></project>", StandardCharsets.UTF_8);
        Files.createFile(module.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(module, false);
        harness.writeSourceFile("src/main/java/com/example/core/IrNode.java", SOURCE);
        return harness.compileReturningDiagnostics();
    }

    private static boolean warnsAboutDetachment(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        return diagnostics.stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
            .anyMatch(d -> d.getMessage(null).contains("generated its guardrails as its own root"));
    }

    @Test
    void warnsWhenAMavenReactorAboveDeclaresThisDirectoryAsAModule() throws IOException {
        Path reactor = tmp.resolve("reactor");
        Files.createDirectories(reactor);
        Files.writeString(reactor.resolve("pom.xml"),
            "<project><modules><module>module-core</module></modules></project>", StandardCharsets.UTF_8);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = compileDetached(reactor.resolve("module-core"));

        assertTrue(warnsAboutDetachment(diagnostics),
            "a module the reactor declares, generating into its own root, must say so");
        assertTrue(diagnostics.stream().anyMatch(d -> d.getMessage(null).contains("-Avibetags.root=")),
            "the warning must name the fix");
    }

    @Test
    void warnsForAGradleSettingsInclude() throws IOException {
        Path reactor = tmp.resolve("reactor");
        Files.createDirectories(reactor);
        Files.writeString(reactor.resolve("build.gradle.kts"), "", StandardCharsets.UTF_8);
        Files.writeString(reactor.resolve("settings.gradle.kts"),
            "include(\"module-core\", \"module-cli\")\n", StandardCharsets.UTF_8);

        assertTrue(warnsAboutDetachment(compileDetached(reactor.resolve("module-core"))),
            "Gradle's include(...) declares the relationship just as <module> does");
    }

    /**
     * The guard that keeps this from being noise: a standalone project that merely happens to live
     * inside a directory with a build file is not a detached module, and must not be told it is.
     */
    @Test
    void staysSilentWhenTheDirectoryAboveDoesNotDeclareThisModule() throws IOException {
        Path outer = tmp.resolve("outer");
        Files.createDirectories(outer);
        Files.writeString(outer.resolve("pom.xml"),
            "<project><modules><module>something-else</module></modules></project>", StandardCharsets.UTF_8);

        assertFalse(warnsAboutDetachment(compileDetached(outer.resolve("standalone"))),
            "an unrelated project nested inside another repository is not a detached module");
    }

    @Test
    void staysSilentWhenThereIsNoBuildFileAbove() throws IOException {
        assertFalse(warnsAboutDetachment(compileDetached(tmp.resolve("solo"))),
            "an ordinary standalone project must never see this warning");
    }

    // ---------------------------------------------------------------- shared-build-file collapse

    /**
     * The Gradle style where subprojects are declared in {@code settings.gradle} but configured
     * entirely from the root build file, so none has a build file of its own.
     *
     * <p>Module roots are found by walking up to the nearest build file, and settings.gradle is
     * deliberately not one of those markers, so the walk passes through the subproject and lands
     * on the root. Every subproject then shares one identity, writes one sidecar and overwrites
     * the one before it. Measured on {@code examples/gradle-shared-buildfile} before its remedy
     * was applied: a single {@code .vibetags-mod-_root_}, and an aggregate carrying one module's
     * guardrails with the other's simply absent. Not stale and not duplicated. Whichever
     * subproject compiled last is the only one that survives.
     *
     * <p>Nothing in a javac round says which Gradle subproject it belongs to, so the build cannot
     * repair this itself. The honest remedy is to say so and name the option that fixes it.
     * Silence is the whole defect: the output is well-formed and looks complete.
     */
    @Test
    void warnsWhenSubprojectsShareTheRootBuildFileAndSoShareAnIdentity() throws IOException {
        Path reactor = tmp.resolve("shared");
        Files.createDirectories(reactor);
        Files.writeString(reactor.resolve("build.gradle"), "", StandardCharsets.UTF_8);
        Files.writeString(reactor.resolve("settings.gradle"),
            "include 'core'\ninclude 'app'\n", StandardCharsets.UTF_8);

        List<String> warnings = compileAtRootCapturingWarnings(reactor);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("core")
                && w.contains("-Avibetags.module")),
            "the collapse has to be named, with the option that fixes it. Warnings were:\n  "
                + String.join("\n  ", warnings));
    }

    /** The guard: a subproject with its own build file resolves itself and needs no lecture. */
    @Test
    void staysSilentWhenEachSubprojectHasItsOwnBuildFile() throws IOException {
        Path reactor = tmp.resolve("proper");
        Files.createDirectories(reactor.resolve("core"));
        Files.writeString(reactor.resolve("build.gradle"), "", StandardCharsets.UTF_8);
        Files.writeString(reactor.resolve("settings.gradle"), "include 'core'\n", StandardCharsets.UTF_8);
        Files.writeString(reactor.resolve("core/build.gradle"), "", StandardCharsets.UTF_8);

        assertTrue(compileAtRootCapturingWarnings(reactor).stream()
                .noneMatch(w -> w.contains("-Avibetags.module")),
            "a subproject carrying its own build file supplies its own identity");
    }

    /** The other guard: no settings.gradle at all means no subprojects to collapse. */
    @Test
    void staysSilentWithoutASettingsFile() throws IOException {
        Path plain = tmp.resolve("plain");
        Files.createDirectories(plain);
        Files.writeString(plain.resolve("build.gradle"), "", StandardCharsets.UTF_8);

        assertTrue(compileAtRootCapturingWarnings(plain).stream()
                .noneMatch(w -> w.contains("-Avibetags.module")),
            "an ordinary single-module Gradle project must never see this");
    }

    /**
     * Compiles a source that lives under {@code core/} with the VibeTags root at {@code reactor},
     * which is the shape a subproject without its own build file produces.
     */
    private List<String> compileAtRootCapturingWarnings(Path reactor) throws IOException {
        Files.createDirectories(reactor);
        Files.createFile(reactor.resolve("CLAUDE.md"));
        ProcessorTestHarness harness = new ProcessorTestHarness(reactor, false);
        harness.writeSourceFile("core/src/main/java/com/example/core/IrNode.java", SOURCE);
        List<String> warnings = harness.compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.WARNING
                || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }

}
