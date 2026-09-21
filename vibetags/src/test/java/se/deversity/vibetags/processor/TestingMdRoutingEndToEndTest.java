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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final String SECOND_TEST_FOCUS = "Clock is injected, never read from the system";

    private static final String SECOND_TEST_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "Clock is injected, never read from the system")
        public class LedgerClockTest {
        }
        """;

    private static final String UNANNOTATED_TEST_SOURCE = """
        package com.example.ledger;

        public class LedgerTest {
        }
        """;

    /** The generated region of a Markdown file: what lies between the two markers. */
    private static String generatedRegion(String content) {
        int start = content.indexOf("<!-- VIBETAGS-START -->");
        int end = content.indexOf("<!-- VIBETAGS-END -->");
        assertTrue(start >= 0 && end > start, "no marker pair in:\n" + content);
        return content.substring(start, end);
    }

    @Test
    void addingATestAnnotationChangesTestingMdAndNothingElse() throws IOException {
        optInto(ALWAYS_LOADED);
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();
        String claudeBefore = read("CLAUDE.md");
        String cursorBefore = read(".cursorrules");

        compileSourceSet("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", TEST_SOURCE},
            new String[]{"com.example.ledger.LedgerClockTest", SECOND_TEST_SOURCE}));

        assertTrue(read("TESTING.md").contains(SECOND_TEST_FOCUS), "the new guardrail must reach TESTING.md");
        assertTrue(read("TESTING.md").contains(TEST_FOCUS), "and must not displace the first");
        assertEquals(claudeBefore, read("CLAUDE.md"), "a new test guardrail is no business of CLAUDE.md's");
        assertEquals(cursorBefore, read(".cursorrules"), "nor of .cursorrules'");
    }

    /**
     * Spec US1 scenario 3 (#781). It was committed disabled: a round that found no annotations never
     * saved its sidecar, so the source set's previous one kept contributing the removed guardrail.
     * Three things had to change, and the first is javac's: a source set with no annotation left is
     * never handed to a processor that claims only VibeTags' annotations, so the processor now
     * claims {@code "*"} while any sidecar records elements. Then an emptied round that was shown
     * its sources saves its empty sidecar, and rewrites the files it has withdrawn from. Partial
     * rounds are refused before generation (invariant 17), which is what makes the empty result
     * safe to believe.
     */
    @Test
    void removingTheLastTestAnnotationEmptiesTheRegionAndKeepsTheFile() throws IOException {
        optInto(ALWAYS_LOADED);
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();
        assertTrue(read("TESTING.md").contains(TEST_FOCUS), "precondition");

        compileSourceSet("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", UNANNOTATED_TEST_SOURCE}));

        assertTrue(Files.exists(root.resolve("TESTING.md")), "the opt-in file is the user's, never deleted");
        assertFalse(read("TESTING.md").contains(TEST_FOCUS),
            "a guardrail whose annotation is gone must not linger:\n" + read("TESTING.md"));
        assertTrue(read("CLAUDE.md").contains(MAIN_FOCUS), "and the main guardrails are untouched");
    }

    /**
     * The same removal when the emptied source set owned the only sidecar: main carries no
     * annotation and nothing is routed. With one sidecar the merge path is not taken, and the
     * single-module write is gated on this round having found annotations, which it did not. The
     * file is rewritten anyway because this source set has just withdrawn from it (#781); without
     * that the removed guardrail stays in {@code CLAUDE.md} for good, on a build reporting no
     * changes.
     */
    @Test
    void removingTheOnlyAnnotationInTheProjectClearsTheAlwaysLoadedFile() throws IOException {
        optInto(List.of("CLAUDE.md"));
        compileSourceSet("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", TEST_SOURCE}));
        assertTrue(read("CLAUDE.md").contains(TEST_FOCUS), "precondition: unrouted, so it is in CLAUDE.md");

        compileSourceSet("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", UNANNOTATED_TEST_SOURCE}));

        assertTrue(Files.exists(root.resolve("CLAUDE.md")), "the opt-in file is never deleted");
        assertFalse(read("CLAUDE.md").contains(TEST_FOCUS),
            "a guardrail whose annotation is gone must not linger:\n" + read("CLAUDE.md"));
    }

    @Test
    void handWrittenTextAroundTheMarkersSurvives() throws IOException {
        optInto(ALWAYS_LOADED);
        String above = "# Testing\n\nRun the fast tier before pushing.\n\n";
        String below = "\n## Flaky tests\n\nQuarantine, do not retry.\n";
        Files.writeString(root.resolve("TESTING.md"),
            above + "<!-- VIBETAGS-START -->\n<!-- VIBETAGS-END -->\n" + below, StandardCharsets.UTF_8);

        compileMain();
        compileTests();

        String testing = read("TESTING.md");
        assertTrue(generatedRegion(testing).contains(TEST_FOCUS), "the region must be filled:\n" + testing);
        assertTrue(testing.startsWith(above), "text above the markers must be byte-identical:\n" + testing);
        assertTrue(testing.endsWith(below), "text below the markers must be byte-identical:\n" + testing);
    }

    /**
     * A module with test sources and no main sources has one sidecar, so the reactor merge never
     * runs for it. Routing that lived in the merge would silently skip exactly this module.
     */
    @Test
    void aModuleWithOnlyTestSourcesIsRoutedToo() throws IOException {
        optInto(ALWAYS_LOADED);
        Files.createFile(root.resolve("TESTING.md"));

        compileTests();

        assertTrue(read("TESTING.md").contains(TEST_FOCUS), "TESTING.md:\n" + read("TESTING.md"));
        for (String file : ALWAYS_LOADED) {
            assertFalse(read(file).contains(TEST_FOCUS), file + " must not carry it:\n" + read(file));
        }
    }

    /**
     * Opting in on a project that was already built. The test round's share of CLAUDE.md is now
     * empty, and an empty share still has to replace what that round wrote there last time.
     */
    @Test
    void optingInRemovesTheTestGuardrailsAnEarlierBuildLeftInTheAlwaysLoadedFiles() throws IOException {
        optInto(ALWAYS_LOADED);
        compileMain();
        compileTests();
        assertTrue(read("CLAUDE.md").contains(TEST_FOCUS), "precondition: unrouted build put it there");

        Files.createFile(root.resolve("TESTING.md"));
        // A second annotated class, so the round is not the one the fingerprint already saw. That
        // the bare toggle is also noticed is TestingMdLifecycleEndToEndTest's business.
        compileSourceSet("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", TEST_SOURCE},
            new String[]{"com.example.ledger.LedgerClockTest", SECOND_TEST_SOURCE}));

        for (String file : ALWAYS_LOADED) {
            String content = read(file);
            assertFalse(content.contains(TEST_FOCUS), file + " still carries a stale test guardrail:\n" + content);
            assertTrue(content.contains(MAIN_FOCUS), file + " lost the main guardrail:\n" + content);
        }
        assertTrue(read("TESTING.md").contains(TEST_FOCUS));
    }

    // -----------------------------------------------------------------------
    // The pointer: a file that gave up its test guardrails says where they went
    // -----------------------------------------------------------------------

    /**
     * Written out, not read from the production constant. Consumers commit the files that contain
     * this sentence, so a change to it rewrites a generated file in every consuming build, and a
     * test that read the constant would follow the change instead of failing on it.
     */
    private static final String POINTER =
        "Guardrails for test code are in TESTING.md. Read it before modifying anything under a test source set.";

    private static final String SAFETY_ONLY_TEST_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Golden ledger fixture is shared with the partner sandbox")
        public class GoldenLedgerFixture {
        }
        """;

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void aFileThatGaveUpTestGuardrailsPointsAtTestingMdExactlyOnce() throws IOException {
        optInto(ALWAYS_LOADED);
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();

        for (String file : ALWAYS_LOADED) {
            assertEquals(1, occurrences(read(file), POINTER), file + ":\n" + read(file));
        }
        assertEquals(0, occurrences(read("TESTING.md"), POINTER), "TESTING.md does not point at itself");
    }

    /** A {@code #} comment in a hash-marker file, prose in a Markdown one. */
    @Test
    void thePointerIsACommentWhereTheFileHasNoProse() throws IOException {
        optInto(ALWAYS_LOADED);
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();

        assertTrue(read(".cursorrules").contains("\n# " + POINTER + "\n"), read(".cursorrules"));
        assertTrue(read("GEMINI.md").contains("\n" + POINTER + "\n"), read("GEMINI.md"));
    }

    /**
     * CLAUDE.md wraps its rules in {@code <project_guardrails>}. A sentence inside that element
     * would be parsed as one of the rules, so the pointer goes after it closes.
     */
    @Test
    void thePointerIsNeverInsideTheProjectGuardrailsElement() throws IOException {
        optInto(ALWAYS_LOADED);
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();

        String claude = read("CLAUDE.md");
        int pointerAt = claude.indexOf(POINTER);
        int lastOpen = claude.lastIndexOf("<project_guardrails>", pointerAt);
        int lastClose = claude.lastIndexOf("</project_guardrails>", pointerAt);
        assertTrue(pointerAt >= 0, claude);
        assertTrue(lastOpen < 0 || lastClose > lastOpen,
            "the pointer sits inside an open <project_guardrails> element:\n" + claude);
    }

    @Test
    void noPointerWithoutTestingMd() throws IOException {
        optInto(ALWAYS_LOADED);
        compileMain();
        compileTests();

        for (String file : ALWAYS_LOADED) {
            assertEquals(0, occurrences(read(file), "TESTING.md"), file + ":\n" + read(file));
        }
    }

    /** Nothing moved, so there is nothing to point at, and an empty TESTING.md is no use to read. */
    @Test
    void noPointerWhenTheTestCodeCarriesOnlySafetyAnnotations() throws IOException {
        optInto(ALWAYS_LOADED);
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileSourceSet("test", List.<String[]>of(
            new String[]{"com.example.ledger.GoldenLedgerFixture", SAFETY_ONLY_TEST_SOURCE}));

        for (String file : ALWAYS_LOADED) {
            assertEquals(0, occurrences(read(file), POINTER), file + ":\n" + read(file));
            assertTrue(read(file).contains("Golden ledger fixture"), file + " must keep the safety guardrail");
        }
    }
}
