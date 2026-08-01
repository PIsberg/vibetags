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
}
