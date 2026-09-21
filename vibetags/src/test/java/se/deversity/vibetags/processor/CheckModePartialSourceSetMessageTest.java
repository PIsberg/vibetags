package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When check mode fails on a comparison that saw one source set, it says so.
 *
 * <p>This does not fix <a href="https://github.com/PIsberg/vibetags/issues/794">#794</a>, and is
 * not meant to. The verdict is unchanged and still wrong in that case. What changes is that the
 * error stops sending the reader somewhere that cannot help: today it says "Run a normal compile
 * and commit the regenerated files", and for the #794 shape a normal compile reproduces exactly
 * the files already committed, so the reader regenerates a correct tree, sees no diff, and is left
 * with a red gate and no next step.
 *
 * <p>Check mode writes no sidecar, deliberately, so on a clean checkout neither the main nor the
 * test round can see the other's guardrails. The first round to run fails and hides the second,
 * which is why the issue reads as "the first of two rounds"; both are equally blind.
 */
@Tag("e2e")
@DisplayName("A one-source-set check failure says the comparison was partial")
class CheckModePartialSourceSetMessageTest {

    private static final String NOTE = "this comparison saw only the";

    private static final String MAIN_SOURCE = """
        package com.example.ledger;

        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "Ledger totals are integer minor units")
        public class Ledger {
        }
        """;

    private static final String TEST_SOURCE = """
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

    private List<String> compile(String sourceSet, String fqn, String source, String... options)
            throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve("pom.xml"),
            "<project><artifactId>ledger</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(
            "src/" + sourceSet + "/java/" + fqn.replace('.', '/') + ".java", source);
        List<String> messages = harness.compileReturningDiagnostics(options).stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .toList();
        VibeTagsLogger.shutdown();
        return messages;
    }

    /** The #794 shape: build both source sets, delete the sidecars, then check the main round. */
    @Test
    @DisplayName("the note appears when the round is the only contributor to the comparison")
    void aCheckFailureFromOneSourceSetSaysTheComparisonWasPartial() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile("main", "com.example.ledger.Ledger", MAIN_SOURCE);
        compile("test", "com.example.ledger.GoldenLedgerFixture", TEST_SOURCE);
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).contains("Golden ledger fixture"),
            "precondition: the committed file carries both source sets' guardrails");

        // A clean checkout: the sidecars are gitignored build state and CI never has them.
        deleteSidecars();
        List<String> errors = compile("main", "com.example.ledger.Ledger", MAIN_SOURCE,
            "-Avibetags.check=true");

        assertTrue(errors.stream().anyMatch(e -> e.contains("check failed")),
            "precondition: this is the #794 false drift, which is still reported. Errors: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains(NOTE)),
            "the failure must say the comparison saw one source set, or the reader follows the "
                + "advice above it and regenerates a tree that is already correct. Errors: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("794")),
            "and name the issue, so the reader can tell a known limit from their own mistake: " + errors);
    }

    /**
     * The note must not appear on a real drift that a full comparison found, or it becomes the
     * sentence people learn to skip.
     */
    @Test
    @DisplayName("no note when both source sets are in the comparison")
    void aCheckFailureWithEverySourceSetPresentCarriesNoNote() throws IOException {
        Files.createFile(root.resolve("CLAUDE.md"));
        compile("main", "com.example.ledger.Ledger", MAIN_SOURCE);
        compile("test", "com.example.ledger.GoldenLedgerFixture", TEST_SOURCE);

        // Genuine drift, with both sidecars still on disk: an edited annotation nobody regenerated.
        String edited = MAIN_SOURCE.replace("integer minor units", "stored as decimal strings");
        List<String> errors = compile("main", "com.example.ledger.Ledger", edited,
            "-Avibetags.check=true");

        assertTrue(errors.stream().anyMatch(e -> e.contains("check failed")),
            "precondition: the annotation changed, so this is real drift. Errors: " + errors);
        assertFalse(errors.stream().anyMatch(e -> e.contains(NOTE)),
            "the test round's sidecar is present, so the comparison was complete and the note "
                + "would be noise on a finding that is genuinely the reader's to fix: " + errors);
    }

    private void deleteSidecars() throws IOException {
        try (var files = Files.list(root)) {
            for (Path p : files.toList()) {
                String name = String.valueOf(p.getFileName());
                if (name.startsWith(".vibetags-mod-") || ".vibetags-cache".equals(name)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
