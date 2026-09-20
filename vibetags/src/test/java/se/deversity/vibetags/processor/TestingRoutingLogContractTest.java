package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
 * The {@code testing.*} events are a contract, not commentary.
 *
 * <p>Routing changes which file a guardrail is in without changing whether the build succeeds, so
 * "why is this rule not in CLAUDE.md" and "why is TESTING.md empty" have no answer in the output
 * files themselves. The answer is the round's decision, and the log is the only place it is
 * recorded: routed, with how much moved and how much stayed, or not routed and why.
 *
 * <p>Renaming an event or a key asserted here is a breaking change. See docs/LOGGING.md.
 */
@Tag("e2e")
@DisplayName("testing.* routing events")
class TestingRoutingLogContractTest {

    private static final String DEBUG = "-Avibetags.log.level=DEBUG";

    private static final String MAIN_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "Ledger totals are integer minor units")
        public class Ledger {
        }
        """;

    private static final String ADVISORY_TEST_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "Fixtures build ledgers through LedgerBuilder only")
        public class LedgerTest {
        }
        """;

    private static final String SAFETY_TEST_SOURCE = """
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

    /** Compiles one source set at DEBUG and returns what that round alone logged. */
    private String compile(String sourceSet, List<String[]> fqnAndSource) throws IOException {
        VibeTagsLogger.shutdown();
        Files.deleteIfExists(root.resolve("vibetags.log"));
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>ledger</artifactId></project>", StandardCharsets.UTF_8);
        for (String[] pair : fqnAndSource) {
            harness.writeSourceFile(
                "src/" + sourceSet + "/java/" + pair[0].replace('.', '/') + ".java", pair[1]);
        }
        harness.compile(DEBUG);
        VibeTagsLogger.shutdown();
        return Files.readString(root.resolve("vibetags.log"));
    }

    private String compileMain() throws IOException {
        return compile("main", List.<String[]>of(new String[]{"com.example.ledger.Ledger", MAIN_SOURCE}));
    }

    @Test
    @DisplayName("a routed round says how much moved and how much stayed")
    void aRoutedRoundReportsWhatMovedAndWhatStayed() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();

        String log = compile("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", ADVISORY_TEST_SOURCE},
            new String[]{"com.example.ledger.GoldenLedgerFixture", SAFETY_TEST_SOURCE}));

        assertTrue(log.contains("testing.route sourceSet=test routed=1 moved=1 kept=1"),
            "one routed file (CLAUDE.md), one advisory guardrail moved, one safety guardrail kept:\n" + log);
    }

    @Test
    @DisplayName("a main round beside TESTING.md says it is not a test round")
    void aMainRoundSaysItIsNotATestRound() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));

        String log = compileMain();

        assertTrue(log.contains("testing.skip reason=not-test-round sourceSet=main"), log);
        assertFalse(log.contains("testing.route"), log);
    }

    @Test
    @DisplayName("a test round with only safety guardrails says nothing moved")
    void aSafetyOnlyTestRoundSaysNothingMoved() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();

        String log = compile("test", List.<String[]>of(
            new String[]{"com.example.ledger.GoldenLedgerFixture", SAFETY_TEST_SOURCE}));

        assertTrue(log.contains("testing.skip reason=no-test-guardrails sourceSet=test"), log);
        assertFalse(log.contains("testing.route"), log);
    }

    @Test
    @DisplayName("a project without TESTING.md logs no testing event at all")
    void withoutTestingMdNothingIsLogged() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        String mainLog = compileMain();
        String testLog = compile("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", ADVISORY_TEST_SOURCE}));

        assertTrue(mainLog.contains("sidecar.save"), "control: the log is being captured at DEBUG:\n" + mainLog);
        assertFalse(mainLog.contains("testing."), mainLog);
        assertFalse(testLog.contains("testing."), testLog);
    }

    @Test
    @DisplayName("the merge says when it fell back to a round's unrouted body")
    void theMergeSaysWhenItFellBack() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve("TESTING.md"));
        compileMain();
        compile("test", List.<String[]>of(
            new String[]{"com.example.ledger.LedgerTest", ADVISORY_TEST_SOURCE}));

        Files.delete(root.resolve("TESTING.md"));
        String log = compileMain();

        assertTrue(log.contains("merge.testing.fallback service=claude module="),
            "deleting TESTING.md changes what the merge reads, and nothing in CLAUDE.md says so:\n" + log);
    }
}
