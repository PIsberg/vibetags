package se.deversity.vibetags.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import se.deversity.vibetags.processor.AIGuardrailProcessor;
import se.deversity.vibetags.processor.VibeTagsLogger;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day zero, end to end: {@code vibetags init} creates the opt-in files, a real compilation through
 * {@code AIGuardrailProcessor} fills them, and {@code vibetags doctor} pronounces the result
 * healthy. The three steps are the whole onboarding path a new consumer walks, and until now each
 * was tested against its own fixtures — {@code InitCommandTest} against paths it asserted itself,
 * {@code DoctorCommandTest} against files it hand-wrote with markers it hand-typed.
 *
 * <p>Both sides read {@code ServiceRegistry}, so the paths cannot drift. The markers can: doctor
 * decides a file is intact by looking for {@code GuardrailFileWriter}'s marker constants in it, and
 * nothing checked that the writer actually puts those markers in the files init creates. A file
 * whose marker form doctor does not recognise reads as unbalanced, and doctor's one job is to not
 * report a healthy project as broken.
 *
 * <p>This is the only place in the repository where the CLI and the processor run against the same
 * directory, which is also the only way to find out.
 */
class OnboardingLifecycleTest {

    private static final String LEDGER_SOURCE = """
        package com.example;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Reconciliation order is load-bearing")
        public class Ledger {
        }
        """;

    @TempDir
    Path dir;

    private final java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
    private final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();

    @AfterEach
    void releaseLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void initThenCompileThenDoctor_reportsAHealthyProject() throws Exception {
        writeWiredPom();

        assertEquals(0, cli("init", "--platforms", "claude,cursor"), err());
        assertEquals(0, Files.size(dir.resolve("CLAUDE.md")),
            "init creates the opt-in empty — the compile is what fills it");

        compileWithProcessor();

        String claude = Files.readString(dir.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
        assertTrue(claude.contains("com.example.Ledger"),
            "the file init created must be the file the processor writes to:\n" + claude);
        assertTrue(Files.readString(dir.resolve(".cursorrules"), StandardCharsets.UTF_8)
                .contains("com.example.Ledger"),
            "every platform init opted in must be filled by the same compile");

        resetOutput();
        assertEquals(0, cli("doctor"),
            "doctor must call a freshly initialised, freshly compiled project healthy:\n" + out());
        assertTrue(out().contains("result: healthy"), out());
        assertTrue(out().contains("claude -> CLAUDE.md"), out());
        assertTrue(out().contains("markers:         all intact"),
            "doctor looks for the writer's marker constants; if it cannot find them in a file the "
                + "writer just wrote, the two halves of the product disagree about what a managed "
                + "file looks like:\n" + out());
    }

    /**
     * The other half of doctor's contract: when a real generated file loses half its marker pair —
     * the shape a half-applied patch or a merge-conflict resolution leaves behind — doctor has to
     * report it rather than pass. Pinned against a file the processor generated, not one the test
     * typed, so the marker doctor removes is the marker the writer emits.
     */
    @Test
    void doctorReportsAHalfLostMarkerPairInAGeneratedFile() throws Exception {
        writeWiredPom();
        assertEquals(0, cli("init", "--platforms", "claude"), err());
        compileWithProcessor();

        Path claude = dir.resolve("CLAUDE.md");
        String generated = Files.readString(claude, StandardCharsets.UTF_8);
        String endMarker = "<!-- VIBETAGS-END -->";
        assertTrue(generated.contains(endMarker),
            "the generated file must carry the end marker before this test removes it");
        Files.writeString(claude, generated.replace(endMarker, ""), StandardCharsets.UTF_8);

        resetOutput();
        assertEquals(1, cli("doctor"),
            "a generated file with a start marker and no end must be a finding, not a pass:\n" + out());
        assertTrue(out().contains("unbalanced VIBETAGS markers in CLAUDE.md"), out());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** A pom carrying both artifact names, which is all doctor greps the build file for. */
    private void writeWiredPom() throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <artifactId>consumer</artifactId>
              <dependencies>
                <dependency><artifactId>vibetags-annotations</artifactId></dependency>
              </dependencies>
              <build><plugins><plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration><annotationProcessorPaths>
                  <path><artifactId>vibetags-processor</artifactId></path>
                </annotationProcessorPaths></configuration>
              </plugin></plugins></build>
            </project>
            """, StandardCharsets.UTF_8);
    }

    /**
     * One real annotation-processing round over {@link #LEDGER_SOURCE}, with the processor rooted
     * at the same directory the CLI operates on. {@code -proc:only} keeps it to the processing
     * round: this test is about the files VibeTags writes, not about class output.
     */
    private void compileWithProcessor() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JavaCompiler unavailable — run tests with a JDK, not a JRE");
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Path classOut = dir.resolve("target/classes");
            Files.createDirectories(classOut);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));

            JavaFileObject unit = new SimpleJavaFileObject(
                    URI.create("string:///com/example/Ledger.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return LEDGER_SOURCE;
                }
            };

            JavaCompiler.CompilationTask task = compiler.getTask(null, fm, null, List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-proc:only",
                "-Avibetags.root=" + dir.toAbsolutePath()
            ), null, List.of(unit));
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            assertTrue(task.call(), "the consumer compilation must succeed");
        } finally {
            VibeTagsLogger.shutdown();
        }
    }

    private int cli(String... args) {
        return Main.run(args,
            new java.io.PrintStream(stdout, true, StandardCharsets.UTF_8),
            new java.io.PrintStream(stderr, true, StandardCharsets.UTF_8),
            dir);
    }

    private void resetOutput() {
        stdout.reset();
        stderr.reset();
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }
}
