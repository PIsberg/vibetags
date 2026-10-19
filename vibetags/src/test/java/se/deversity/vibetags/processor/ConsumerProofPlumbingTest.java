package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the consumer proof ({@code evals/consumer/run-proof.sh}) end to end with a stub in place
 * of the claude CLI, so the runner, the three variants, every detector and the report arithmetic
 * are exercised on every CI build rather than only when someone pays for a model run.
 *
 * <p>The stub ({@code evals/consumer/test-support/claude}) obeys exactly when it can see the
 * generated block, so the expected table is known: 0% under baseline and annotations-only, 100%
 * under full. A variant that leaked the block into the baseline, a detector that passed
 * everything, or a delta computed the wrong way round each changes a cell.
 */
@Tag("e2e")
class ConsumerProofPlumbingTest {

    private static final Path EVALS = Paths.get("").toAbsolutePath().getParent().resolve("evals");

    @TempDir
    Path tempDir;

    @Test
    void stubAgentProducesTheExpectedProofTable() throws Exception {
        Path proofDir = tempDir.resolve("proof");
        Result r = run(EVALS.resolve("consumer/run-proof.sh"),
            Map.of("TRIALS", "1", "PROOF_DIR", unix(proofDir)));
        assertEquals(0, r.exit, r.output);

        String report = Files.readString(proofDir.resolve("REPORT.md"), StandardCharsets.UTF_8);
        for (String task : List.of("contract-overload", "locked-direct", "locked-sweep", "privacy-logging")) {
            String row = "| " + task + " | 0/1 (0%) | 0/1 (0%) | 1/1 (100%) | 100 pp | 100 pp |";
            assertTrue(report.contains(row), "missing row " + row + " in:\n" + report + "\nrun output:\n" + r.output);
        }
    }

    @Test
    void consumerSuiteRejectsARepoOnlyVariant() throws Exception {
        Result r = run(EVALS.resolve("run-instruction-evals.sh"),
            Map.of("SUITE", "consumer", "VARIANT", "no-index", "TRIALS", "1",
                "RESULTS_DIR", unix(tempDir.resolve("results"))));
        assertEquals(2, r.exit, r.output);
        assertTrue(r.output.contains("has no variant 'no-index'"), r.output);
    }

    private record Result(int exit, String output) {
    }

    private static Result run(Path script, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ConsumerSweepShell.bashCommand(unix(script)))
            .directory(EVALS.getParent().toFile())
            .redirectErrorStream(true);
        Map<String, String> e = pb.environment();
        // The stub never calls out; the runner only checks that some credential is set.
        e.put("ANTHROPIC_API_KEY", "stub-not-a-key");
        e.remove("CLAUDE_CODE_OAUTH_TOKEN");
        String pathKey = e.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        e.put(pathKey, EVALS.resolve("consumer/test-support") + File.pathSeparator + e.getOrDefault(pathKey, ""));
        e.putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    /** Forward slashes, which Git Bash's tools accept for a Windows path; a no-op elsewhere. */
    private static String unix(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
