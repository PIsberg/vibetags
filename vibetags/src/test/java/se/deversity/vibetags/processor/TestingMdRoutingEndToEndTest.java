package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guardrails on test code go to {@code TESTING.md} when that file is present.
 *
 * <p>Maven and Gradle compile a module's main and test sources as two javac invocations, so each
 * test here runs them as two compilations over one module root, in build order, the way
 * {@link SourceSetIsolationEndToEndTest} does. Assertions are on the annotations' own text, never
 * on a class name ({@code LedgerTest} contains {@code Ledger}) and never on a file existing: an
 * opted-in file that exists and holds only a header is the defect shape this repository has
 * shipped before.
 */
@Tag("e2e")
class TestingMdRoutingEndToEndTest {

    private static final String MAIN_FOCUS = "Ledger totals are integer minor units";
    private static final String TEST_FOCUS = "Fixtures build ledgers through LedgerBuilder only";

    private static final String MAIN_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "Ledger totals are integer minor units")
        public class Ledger {
        }
        """;

    private static final String TEST_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "Fixtures build ledgers through LedgerBuilder only")
        public class LedgerTest {
        }
        """;

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles one source set of the single module at {@link #root}, mimicking one Maven phase. */
    private void compileSourceSet(String sourceSet, List<String[]> fqnAndSource) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>ledger</artifactId></project>", StandardCharsets.UTF_8);
        for (String[] pair : fqnAndSource) {
            harness.writeSourceFile(
                "src/" + sourceSet + "/java/" + pair[0].replace('.', '/') + ".java", pair[1]);
        }
        harness.compile();
    }

    private void compileMain() throws IOException {
        compileSourceSet("main", List.<String[]>of(new String[]{"com.example.ledger.Ledger", MAIN_SOURCE}));
    }

    private void compileTests() throws IOException {
        compileSourceSet("test", List.<String[]>of(new String[]{"com.example.ledger.LedgerTest", TEST_SOURCE}));
    }

    private String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative));
    }

    @Test
    void aTestRoundWritesItsGuardrailsToTestingMd() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));

        compileMain();
        assertFalse(read("TESTING.md").contains(MAIN_FOCUS),
            "a main round has nothing to say about test code:\n" + read("TESTING.md"));

        compileTests();
        String testing = read("TESTING.md");
        assertTrue(testing.contains(TEST_FOCUS), "the test class's guardrail must reach TESTING.md:\n" + testing);
        assertFalse(testing.contains(MAIN_FOCUS), "and the main class's must not:\n" + testing);
    }

    /** One HTML-marker file, one hash-marker file, and a second Markdown aggregate. */
    private static final List<String> ALWAYS_LOADED = List.of("CLAUDE.md", ".cursorrules", "GEMINI.md");

    private void optInto(List<String> files) throws IOException {
        for (String file : files) {
            Files.createFile(root.resolve(file));
        }
    }

    @Test
    void aTestRoundsGuardrailsLeaveTheAlwaysLoadedFiles() throws IOException {
        optInto(ALWAYS_LOADED);
        Files.createFile(root.resolve("TESTING.md"));

        compileMain();
        compileTests();

        for (String file : ALWAYS_LOADED) {
            String content = read(file);
            assertTrue(content.contains(MAIN_FOCUS), file + " must keep the main code's guardrail:\n" + content);
            assertFalse(content.contains(TEST_FOCUS),
                file + " must not carry a test-code guardrail once TESTING.md is present:\n" + content);
        }
        assertTrue(read("TESTING.md").contains(TEST_FOCUS), "it has to have gone somewhere");
    }

    /**
     * The control for the test above. Without it, that test also passes when the test round
     * simply never reaches these files, which is a different defect with the same symptom.
     */
    @Test
    void withoutTestingMdTheTestGuardrailsStayWhereTheyWere() throws IOException {
        optInto(ALWAYS_LOADED);

        compileMain();
        compileTests();

        for (String file : ALWAYS_LOADED) {
            String content = read(file);
            assertTrue(content.contains(MAIN_FOCUS), file + " must keep the main code's guardrail:\n" + content);
            assertTrue(content.contains(TEST_FOCUS), file + " is where a test guardrail lives by default:\n" + content);
        }
        assertFalse(Files.exists(root.resolve("TESTING.md")), "VibeTags never creates the opt-in file");
    }
}
