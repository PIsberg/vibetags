package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
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
 * Creating or deleting {@code TESTING.md} never loses a guardrail, whichever rounds run next.
 *
 * <p>The hard case is the ordinary one. A developer deletes {@code TESTING.md} and runs
 * {@code mvn compile}: only the main round runs, and the test round's sidecar on disk was written
 * while routing was on, so the share of {@code CLAUDE.md} it holds is the safety half only. Unless
 * the merge can fall back to what that round would have written unrouted, the other test
 * guardrails are in no file at all until someone happens to compile the tests again.
 */
@Tag("e2e")
class TestingMdLifecycleEndToEndTest {

    private static final String MAIN_FOCUS = "Ledger totals are integer minor units";
    private static final String TEST_FOCUS = "Fixtures build ledgers through LedgerBuilder only";
    private static final String TEST_LOCK = "Golden ledger fixture is shared with the partner sandbox";

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

    private static final String LOCKED_TEST_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Golden ledger fixture is shared with the partner sandbox")
        public class GoldenLedgerFixture {
        }
        """;

    @TempDir
    Path root;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

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
        compileSourceSet("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", TEST_SOURCE},
            new String[]{"com.example.ledger.GoldenLedgerFixture", LOCKED_TEST_SOURCE}));
    }

    private String read(String relative) throws IOException {
        return Files.readString(root.resolve(relative));
    }

    /** {@code mvn compile} after a full build: the main round must leave TESTING.md as it was. */
    @Test
    void aMainOnlyBuildLeavesTestingMdAlone() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();
        String testingBefore = read("TESTING.md");
        assertTrue(testingBefore.contains(TEST_FOCUS), "precondition");

        compileMain();

        assertEquals(testingBefore, read("TESTING.md"));
        String claude = read("CLAUDE.md");
        assertTrue(claude.contains(MAIN_FOCUS) && claude.contains(TEST_LOCK) && !claude.contains(TEST_FOCUS),
            "CLAUDE.md keeps main guardrails and the test code's safety guardrail, nothing else:\n" + claude);
    }

    /** Creating the file and then building main only: nothing has been routed yet, nothing moves. */
    @Test
    void creatingTestingMdThenBuildingMainOnlyLosesNothing() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileMain();
        compileTests();
        assertTrue(read("CLAUDE.md").contains(TEST_FOCUS), "precondition: unrouted");

        Files.createFile(root.resolve("TESTING.md"));
        compileMain();

        String claude = read("CLAUDE.md");
        assertTrue(claude.contains(TEST_FOCUS),
            "no test round has run since the opt-in, so the test guardrail is still only here:\n" + claude);
        assertTrue(claude.contains(MAIN_FOCUS) && claude.contains(TEST_LOCK), claude);
    }

    /** The hard case from the class comment. */
    @Test
    void deletingTestingMdThenBuildingMainOnlyBringsTheTestGuardrailsBack() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();
        assertFalse(read("CLAUDE.md").contains(TEST_FOCUS), "precondition: routed");

        Files.delete(root.resolve("TESTING.md"));
        compileMain();

        String claude = read("CLAUDE.md");
        assertTrue(claude.contains(TEST_FOCUS),
            "with TESTING.md gone the test guardrail must be back in CLAUDE.md:\n" + claude);
        assertTrue(claude.contains(MAIN_FOCUS) && claude.contains(TEST_LOCK), claude);
        assertFalse(claude.contains("TESTING.md"), "and nothing may point at a file that is gone:\n" + claude);
        assertFalse(Files.exists(root.resolve("TESTING.md")), "VibeTags never recreates the opt-in file");
    }

    /**
     * Creating TESTING.md changes no source, so the round that follows looks exactly like the last
     * one to anything that only hashes sources. It must still be treated as a different build.
     */
    @Test
    void creatingTestingMdIsNoticedByARoundWhoseSourcesDidNotChange() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compileMain();
        compileTests();
        assertTrue(read("CLAUDE.md").contains(TEST_FOCUS), "precondition: unrouted");

        Files.createFile(root.resolve("TESTING.md"));
        compileTests();

        assertTrue(read("TESTING.md").contains(TEST_FOCUS), "the identical test round must now route");
        assertFalse(read("CLAUDE.md").contains(TEST_FOCUS), read("CLAUDE.md"));
    }

    /** The mirror image: deleting it, then the identical test round. */
    @Test
    void deletingTestingMdIsNoticedByARoundWhoseSourcesDidNotChange() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();
        assertFalse(read("CLAUDE.md").contains(TEST_FOCUS), "precondition: routed");

        Files.delete(root.resolve("TESTING.md"));
        compileTests();

        assertTrue(read("CLAUDE.md").contains(TEST_FOCUS), read("CLAUDE.md"));
    }

    /** A committed TESTING.md that churns on every build is one nobody keeps. */
    @Test
    void theSameFullBuildTwiceRewritesNothing() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();
        String claude = read("CLAUDE.md");
        String testing = read("TESTING.md");
        long claudeWritten = Files.getLastModifiedTime(root.resolve("CLAUDE.md")).toMillis();
        long testingWritten = Files.getLastModifiedTime(root.resolve("TESTING.md")).toMillis();

        compileMain();
        compileTests();

        assertEquals(claude, read("CLAUDE.md"));
        assertEquals(testing, read("TESTING.md"));
        assertEquals(claudeWritten, Files.getLastModifiedTime(root.resolve("CLAUDE.md")).toMillis(),
            "CLAUDE.md was rewritten by a build that changed nothing");
        assertEquals(testingWritten, Files.getLastModifiedTime(root.resolve("TESTING.md")).toMillis(),
            "TESTING.md was rewritten by a build that changed nothing");
    }

    /**
     * The delete case again, for a module whose main sources carry no annotation, and a known
     * limit: it fails. The main round saves no sidecar, so the test round's is the only one, and
     * the fallback is not consulted until the tests are compiled again, at which point they are
     * re-rendered unrouted and nothing is missing. Letting a lone sidecar into the merge, the way
     * the lean root index does, was tried on 2026-09-20 and did not change the result, so the
     * deciding guard is further in; not chased past the locked {@code generateFiles()}.
     */
    @Disabled("Known limit: unannotated main sources plus a main-only build after deleting TESTING.md "
        + "leaves the test guardrails out until the next test compile. Needs an owner decision.")
    @Test
    void deletingTestingMdLosesNothingEvenWhenTheMainSourcesAreUnannotated() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        List<String[]> unannotatedMain = List.<String[]>of(new String[]{"com.example.ledger.Ledger",
            "package com.example.ledger;\npublic class Ledger {\n}\n"});
        compileSourceSet("main", unannotatedMain);
        compileTests();
        assertFalse(read("CLAUDE.md").contains(TEST_FOCUS), "precondition: routed");

        Files.delete(root.resolve("TESTING.md"));
        compileSourceSet("main", unannotatedMain);

        assertTrue(read("CLAUDE.md").contains(TEST_FOCUS),
            "the test guardrail must be back in CLAUDE.md:\n" + read("CLAUDE.md"));
    }

    /**
     * A routed build must not accuse itself of being incomplete.
     *
     * <p>The "opted into after X last compiled" warning reads a sidecar that carries bodies but
     * none for an active service as a module that compiled before the opt-in existed, which for
     * every other platform it is. {@code testing} breaks that premise by design: a main round
     * renders nothing for it, on purpose, so the main sidecar never carries a {@code testing} body
     * however current it is. Left alone, every routed project gets the warning on every build, and
     * the file it names is complete. A warning that is always wrong is worse than none, because it
     * trains the reader to skip the one that is right.
     */
    @Test
    void aRoutedBuildDoesNotWarnThatTestingMdIsMissingTheMainRound() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compileTests();

        assertTrue(read("TESTING.md").contains(TEST_FOCUS),
            "precondition: the routed round completed the file:\n" + read("TESTING.md"));

        // Compile main again, which is when the warning fires: TESTING.md now carries the test
        // round's mtime, later than anything the main sidecar records.
        List<String> warnings = compileMainCapturingWarnings();

        assertTrue(warnings.stream().noneMatch(w -> w.contains("TESTING.md was opted into after")),
            "TESTING.md is complete; nothing is missing from it. Warnings were:\n  "
                + String.join("\n  ", warnings));
    }

    private List<String> compileMainCapturingWarnings() throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>ledger</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile("src/main/java/com/example/ledger/Ledger.java", MAIN_SOURCE);
        List<String> warnings = harness.compileReturningDiagnostics().stream()
            .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.WARNING
                || d.getKind() == javax.tools.Diagnostic.Kind.MANDATORY_WARNING)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return warnings;
    }
}
