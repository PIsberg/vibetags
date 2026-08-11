package se.deversity.vibetags.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vibetags doctor} — exit code 0 must mean "the processor would behave" and 1 must
 * mean "something needs action". Each test builds the smallest project directory that
 * produces one verdict.
 */
class DoctorCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    private int doctor() {
        PrintStream stream = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        return Main.run(new String[]{"doctor"}, stream, stream, dir);
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private void mavenProjectWiredForVibeTags() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <dependencies>
                <dependency><artifactId>vibetags-annotations</artifactId></dependency>
              </dependencies>
              <annotationProcessorPaths>
                <path><artifactId>vibetags-processor</artifactId></path>
              </annotationProcessorPaths>
            </project>
            """);
    }

    @Test
    void wiredProjectWithIntactMarkers_isHealthy() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), """
            hand-authored intro
            <!-- VIBETAGS-START -->
            generated
            <!-- VIBETAGS-END -->
            """);

        assertEquals(0, doctor(), out());
        assertTrue(out().contains("result: healthy"), out());
        assertTrue(out().contains("claude -> CLAUDE.md"), out());
    }

    @Test
    void missingProcessorWiring_needsAction() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("vibetags-processor"), out());
    }

    @Test
    void noOptInFiles_needsAction() throws Exception {
        mavenProjectWiredForVibeTags();

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("no opt-in files present"), out());
        assertTrue(out().contains("vibetags init"), out());
    }

    @Test
    void unbalancedMarkers_needAction() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"),
            "<!-- VIBETAGS-START -->\nan END marker someone deleted\n");

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("unbalanced VIBETAGS markers in CLAUDE.md"), out());
    }

    @Test
    void agentsMdNextToOtherConfigs_isExplainedAsAPointer() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        Files.writeString(dir.resolve("AGENTS.md"), "see CLAUDE.md");

        int code = doctor();

        // The pointer case is a note, not a finding: this is the processor's documented rule.
        assertEquals(0, code, out());
        assertTrue(out().contains("hand-authored pointer"), out());
    }

    @Test
    void noBuildFile_needsAction() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("no build file found"), out());
    }
}
