package se.deversity.vibetags.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void unreadableMarkerFile_isAFindingNotAHealthyPass() throws Exception {
        // 0xFF can never appear in UTF-8, so Files.readString fails on this file. A file
        // doctor cannot read is exactly the state it must not report as "all intact":
        // the writer needs to read it to preserve hand-authored content.
        mavenProjectWiredForVibeTags();
        Files.write(dir.resolve("CLAUDE.md"), new byte[]{(byte) 0xFF, (byte) 0xFE});

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("could not read CLAUDE.md"), out());
        assertFalse(out().contains("result: healthy"), out());
    }

    @Test
    void unreadableBuildFile_reportsUnknownWiringInsteadOfGuessing() throws Exception {
        Files.write(dir.resolve("pom.xml"), new byte[]{(byte) 0xFF});
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("could not read pom.xml"), out());
        assertFalse(out().contains("not found in pom.xml"),
            "doctor must not claim the wiring is missing when it could not read the file");
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

    // ------------------------------------------------------------------ Groovy field guardrails

    private Path groovySource(String relPath, String source) throws Exception {
        Path file = dir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    @Test
    void groovyFieldGuardrail_isReportedAsDropped() throws Exception {
        // groovyc's Java stubs carry no fields at all, so a field-targeted guardrail generates
        // nothing and the build says nothing (#494). Doctor is the tool that can still see the
        // .groovy source and say so, with the file, the line and the annotation.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        groovySource("src/main/groovy/com/example/Customer.groovy", """
            package com.example

            import se.deversity.vibetags.annotations.AIPrivacy

            class Customer {
                @AIPrivacy(dataType = "email")
                String billingEmail

                String plainField
            }
            """);

        assertEquals(1, doctor(), out());
        assertTrue(out().contains("Customer.groovy"), out());
        assertTrue(out().contains("@AIPrivacy"), out());
        assertTrue(out().contains("billingEmail"), out());
        assertTrue(out().toLowerCase().contains("dropped"), out());
    }

    @Test
    void groovyMethodAndClassGuardrails_areNotFlagged() throws Exception {
        // Types, constructors, methods and parameters all survive into groovyc's stubs; only
        // fields are missing. A doctor that cries wolf on the levels that work gets ignored.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        groovySource("src/main/groovy/com/example/Billing.groovy", """
            package com.example

            import se.deversity.vibetags.annotations.AILocked
            import se.deversity.vibetags.annotations.AICore

            @AICore(sensitivity = "high", note = "settlement core")
            class Billing {
                @AILocked(reason = "wire format")
                def charge(BigDecimal amount) {
                    amount
                }
            }
            """);

        assertEquals(0, doctor(), out());
        assertTrue(out().contains("groovy sources:"), out());
        assertTrue(out().contains("no field-level guardrails"), out());
    }

    @Test
    void projectWithoutGroovy_printsNoGroovyLine() throws Exception {
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");

        assertEquals(0, doctor(), out());
        assertFalse(out().contains("groovy sources:"),
            "a Java-only project has nothing Groovy to report on: " + out());
    }

    @Test
    void groovyUnderBuildDirectories_isNotScanned() throws Exception {
        // build/ and target/ hold generated or copied sources; flagging those reports the
        // build's plumbing, not the developer's code.
        mavenProjectWiredForVibeTags();
        Files.writeString(dir.resolve("CLAUDE.md"), "");
        groovySource("build/tmp/Generated.groovy", """
            class Generated {
                @se.deversity.vibetags.annotations.AIPrivacy(dataType = "x")
                String copied
            }
            """);

        assertEquals(0, doctor(), out());
        assertFalse(out().contains("Generated.groovy"), out());
    }
}
