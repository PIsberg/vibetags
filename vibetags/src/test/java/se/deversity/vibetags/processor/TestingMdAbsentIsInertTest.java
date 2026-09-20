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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without {@code TESTING.md}, routing must cost the generated files nothing at all.
 *
 * <p>The behavioural cases in {@link TestingMdLifecycleEndToEndTest} check that the test guardrails
 * are still present when the file is absent. They would all pass if routing changed some unrelated
 * byte of {@code CLAUDE.md} for a project that never opts in, and the promise the feature makes is
 * stronger than presence: absent file means the bytes a build produced before routing existed.
 *
 * <p>Issue #784 asked for that as a committed golden from a pre-feature jar. This asserts it as an
 * equivalence between two live builds instead, and the difference matters. A frozen golden proves
 * the claim once; the first legitimate change to any renderer fails it, the golden is regenerated
 * from the current code, and from then on it is a change-detector that can no longer say anything
 * about pre-feature parity. Comparing a test round against a main round moves both sides together
 * through every future rendering change, so it keeps testing routing rather than rendering.
 *
 * <p>The equivalence holds because a test round differs from a main round in exactly one respect:
 * {@code RenderingContext.testRound()}, which only {@code RoutedTestingRenderer} reads, and which
 * {@code RoutedViews} acts on only when the {@code testing} service is active. With no
 * {@code TESTING.md} it is not active, so every other file must render byte for byte as it always
 * did. If that ever stops being true, routing has leaked into the path that opted out of it.
 */
@Tag("e2e")
class TestingMdAbsentIsInertTest {

    private static final String MAIN_SOURCE = """
        package com.example.ledger;
        import se.deversity.vibetags.annotations.AIContext;
        @AIContext(focus = "Posting order is the ledger's contract", avoids = "Reordering entries")
        public class Ledger {
        }
        """;

    private static final String TEST_SOURCE = """
        package com.example.ledger;
        import se.deversity.vibetags.annotations.AIContext;
        @AIContext(focus = "Build ledgers through LedgerFixtures", avoids = "The Ledger constructor")
        public class LedgerTest {
        }
        """;

    private static final String LOCKED_SOURCE = """
        package com.example.ledger;
        import se.deversity.vibetags.annotations.AILocked;
        @AILocked(reason = "Golden ledger fixture is shared with the partner sandbox")
        public class GoldenLedgerFixture {
        }
        """;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles the three sources under {@code sourceSet} in its own root, with only CLAUDE.md opted in. */
    private String buildAndReadClaudeMd(String dirName, String sourceSet) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve(dirName));
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>ledger</artifactId></project>", StandardCharsets.UTF_8);
        Files.createFile(root.resolve("CLAUDE.md"));

        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        harness.writeSourceFile("src/" + sourceSet + "/java/com/example/ledger/Ledger.java", MAIN_SOURCE);
        harness.writeSourceFile("src/" + sourceSet + "/java/com/example/ledger/LedgerTest.java", TEST_SOURCE);
        harness.writeSourceFile(
            "src/" + sourceSet + "/java/com/example/ledger/GoldenLedgerFixture.java", LOCKED_SOURCE);
        harness.compile();

        return Files.readString(root.resolve("CLAUDE.md"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("with no TESTING.md, a test round writes CLAUDE.md exactly as a main round does")
    void aTestRoundWithoutTestingMdIsIndistinguishableFromAMainRound() throws IOException {
        String fromMainRound = buildAndReadClaudeMd("as-main", "main");
        String fromTestRound = buildAndReadClaudeMd("as-test", "test");

        assertEquals(fromMainRound, fromTestRound,
            "a project that never opted into TESTING.md must get the bytes it always got; routing "
                + "has leaked into the path that opted out of it");
    }

    @Test
    @DisplayName("and the guardrails are all still there, so the equivalence is not two empty files")
    void theComparedOutputIsNotEmpty() throws IOException {
        String claude = buildAndReadClaudeMd("populated", "test");

        assertTrue(claude.contains("Build ledgers through LedgerFixtures"),
            "the test code's non-safety guardrail belongs inline when nothing routes it away:\n" + claude);
        assertTrue(claude.contains("Golden ledger fixture is shared with the partner sandbox"),
            "and so does its safety guardrail:\n" + claude);
        assertFalse(claude.isBlank(), "an equivalence between two empty files would prove nothing");
    }
}
