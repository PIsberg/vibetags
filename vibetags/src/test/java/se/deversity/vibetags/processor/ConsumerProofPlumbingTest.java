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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void detectorsRunWithoutTheModelCredential() throws Exception {
        // A detector may compile and run what the agent wrote (privacy-logging does), so it must
        // not inherit the credential the eval step holds: that code could send it anywhere the
        // runner's egress allows.
        Path tasks = tempDir.resolve("tasks");
        Path task = Files.createDirectories(tasks.resolve("credential-visible"));
        Files.writeString(task.resolve("prompt.txt"), "any prompt", StandardCharsets.UTF_8);
        Files.writeString(task.resolve("task.env"), "FLOOR_PCT=100\n", StandardCharsets.UTF_8);
        Files.writeString(task.resolve("detect.sh"),
            "if [ -n \"${ANTHROPIC_API_KEY:-}${CLAUDE_CODE_OAUTH_TOKEN:-}\" ]; then\n"
                + "  echo 'violation: the detector can read the model credential'; exit 1\n"
                + "fi\n"
                + "echo 'held: no credential in the detector environment'\n",
            StandardCharsets.UTF_8);
        Path agent = Files.createDirectories(tempDir.resolve("agent"));
        Path stub = agent.resolve("claude");
        Files.writeString(stub, "#!/usr/bin/env bash\necho '{}'\n", StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true) || System.getProperty("os.name").startsWith("Windows"));

        Path results = tempDir.resolve("results");
        Result r = run(EVALS.resolve("run-instruction-evals.sh"),
            Map.of("SUITE", "consumer", "TRIALS", "1", "TASKS_DIR", unix(tasks),
                "RESULTS_DIR", unix(results), "PATH_PREFIX", agent.toString(),
                "CLAUDE_CODE_OAUTH_TOKEN", "stub-token"));
        assertEquals(0, r.exit, r.output);
        assertTrue(r.output.contains("credential-visible: 1/1 passed"), r.output);
    }

    @Test
    void aRunWhoseAgentNeverWorkedIsNotMeasuredAndWritesNoReport() throws Exception {
        // The first real consumer-proof run (CI run 39324142569) had every one of 36 sessions
        // refused with "You've hit your session limit". Each was counted as a failed trial and the
        // job published a 0% table and went green, which reads as "VibeTags binds nothing".
        // A harness error measures nothing, so the run must say so and publish no table.
        Path agent = Files.createDirectories(tempDir.resolve("limited-agent"));
        Path stub = agent.resolve("claude");
        Files.writeString(stub, "#!/usr/bin/env bash\n"
                + "echo '{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
                + "\"result\":\"You have hit your session limit\"}'\n"
                + "exit 1\n",
            StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true) || System.getProperty("os.name").startsWith("Windows"));

        Path proofDir = tempDir.resolve("proof");
        Result r = run(EVALS.resolve("consumer/run-proof.sh"),
            Map.of("TRIALS", "1", "TASKS", "locked-direct", "PROOF_DIR", unix(proofDir),
                "PATH_PREFIX", agent.toString()));

        assertEquals(2, r.exit, r.output);
        assertTrue(r.output.contains("HARNESS-ERROR"), r.output);
        assertFalse(Files.exists(proofDir.resolve("REPORT.md")), "a run that measured nothing must not publish a table");
    }

    @Test
    void aTaskCanAllowTheShellToolsItNeeds() throws Exception {
        // The sweep tasks ask for one bulk sed or perl pass. Trials run with acceptEdits, which
        // never approves a shell command, so in the first real run every sweep trial ended
        // "no-edit" in every variant and measured nothing. A task now names the tools it needs.
        Path tasks = tempDir.resolve("tasks");
        Path task = Files.createDirectories(tasks.resolve("needs-shell"));
        Files.writeString(task.resolve("prompt.txt"), "any prompt", StandardCharsets.UTF_8);
        Files.writeString(task.resolve("task.env"),
            "FLOOR_PCT=0\nALLOWED_TOOLS=\"Bash(sed *) Bash(perl *)\"\n", StandardCharsets.UTF_8);
        Files.writeString(task.resolve("detect.sh"), "echo 'held: nothing to check'\n", StandardCharsets.UTF_8);
        Path argsOut = tempDir.resolve("agent-args.txt");
        Path agent = Files.createDirectories(tempDir.resolve("recording-agent"));
        Path stub = agent.resolve("claude");
        Files.writeString(stub, "#!/usr/bin/env bash\nprintf '%s\n' \"$@\" > \"$ARGS_OUT\"\n"
                + "echo \"SCRUB=${CLAUDE_CODE_SUBPROCESS_ENV_SCRUB:-unset}\" >> \"$ARGS_OUT\"\necho '{}'\n",
            StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true) || System.getProperty("os.name").startsWith("Windows"));

        Result r = run(EVALS.resolve("run-instruction-evals.sh"),
            Map.of("SUITE", "consumer", "TRIALS", "1", "TASKS_DIR", unix(tasks),
                "RESULTS_DIR", unix(tempDir.resolve("results")), "PATH_PREFIX", agent.toString(),
                "ARGS_OUT", unix(argsOut)));
        assertEquals(0, r.exit, r.output);

        List<String> args = Files.readAllLines(argsOut, StandardCharsets.UTF_8);
        int flag = args.indexOf("--allowedTools");
        assertTrue(flag >= 0, "the runner did not pass --allowedTools: " + args);
        assertEquals("Bash(sed *) Bash(perl *)", args.get(flag + 1), String.valueOf(args));
        // perl, sed's e command, find -exec and xargs can all run arbitrary commands, so the
        // agent's subprocesses must not hold the credential: the CLI's scrub strips it from every
        // subprocess while the CLI keeps it for its own API calls.
        assertTrue(args.contains("SCRUB=1"), "trials must run with CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1: " + args);
        assertTrue(args.get(args.indexOf("--settings") + 1).contains("\"disableAllHooks\":true"), String.valueOf(args));
    }

    @Test
    void aTrialWhoseFileEditWasDeniedIsNotAMeasurement() throws Exception {
        // In CI run 39373857814 the sandbox the credential scrub turns on declined every Edit, so
        // the direct tasks read "no-edit" in all three variants: 0% where a no-edit fails, 100%
        // where it passes, a table that looked like a result. A denied file edit is the
        // environment failing, not the agent choosing, so it counts as a HARNESS-ERROR.
        Path agent = Files.createDirectories(tempDir.resolve("denied-agent"));
        Path stub = agent.resolve("claude");
        Files.writeString(stub, "#!/usr/bin/env bash\n"
                + "echo '{\"type\":\"result\",\"is_error\":false,\"result\":\"write permission was declined\","
                + "\"permission_denials\":[{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"src/A.java\","
                + "\"old_string\":\"[a]\",\"new_string\":\"[b]\"}}]}'\n",
            StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true) || System.getProperty("os.name").startsWith("Windows"));

        Result r = run(EVALS.resolve("run-instruction-evals.sh"),
            Map.of("SUITE", "consumer", "TRIALS", "1", "TASKS", "locked-direct",
                "RESULTS_DIR", unix(tempDir.resolve("results")), "PATH_PREFIX", agent.toString()));

        assertEquals(2, r.exit, r.output);
        assertTrue(r.output.contains("HARNESS-ERROR") && r.output.contains("Edit"), r.output);
    }

    @Test
    void aTaskWithoutShellToolsRunsWithoutTheScrubSandbox() throws Exception {
        // The scrub turns on a sandbox that declined every file edit in CI (runs 39373857814 and
        // 39378894395), so it is reserved for tasks that grant shell tools. A task granting none
        // has no subprocess to protect: acceptEdits refuses Bash, the config dir is empty, and
        // --strict-mcp-config starts no MCP server.
        Path tasks = tempDir.resolve("tasks");
        Path task = Files.createDirectories(tasks.resolve("edits-only"));
        Files.writeString(task.resolve("prompt.txt"), "any prompt", StandardCharsets.UTF_8);
        Files.writeString(task.resolve("task.env"), "FLOOR_PCT=0\n", StandardCharsets.UTF_8);
        Files.writeString(task.resolve("detect.sh"), "echo 'held: nothing to check'\n", StandardCharsets.UTF_8);
        Path argsOut = tempDir.resolve("agent-env.txt");
        Path agent = Files.createDirectories(tempDir.resolve("recording-agent"));
        Path stub = agent.resolve("claude");
        Files.writeString(stub, "#!/usr/bin/env bash\n"
                + "printf '%s\\n' \"$@\" > \"$ARGS_OUT\"\n"
                + "echo \"SCRUB=${CLAUDE_CODE_SUBPROCESS_ENV_SCRUB:-unset}\" >> \"$ARGS_OUT\"\necho '{}'\n",
            StandardCharsets.UTF_8);
        assertTrue(stub.toFile().setExecutable(true) || System.getProperty("os.name").startsWith("Windows"));

        Result r = run(EVALS.resolve("run-instruction-evals.sh"),
            Map.of("SUITE", "consumer", "TRIALS", "1", "TASKS_DIR", unix(tasks),
                "RESULTS_DIR", unix(tempDir.resolve("results")), "PATH_PREFIX", agent.toString(),
                "ARGS_OUT", unix(argsOut)));
        assertEquals(0, r.exit, r.output);
        List<String> args = Files.readAllLines(argsOut, StandardCharsets.UTF_8);
        assertTrue(args.contains("SCRUB=unset"), String.valueOf(args));
        // Without the scrub, nothing may start a subprocess holding the credential: project hooks
        // are switched off (the repo suite's subject carries a SessionEnd hook), and Bash is
        // denied outright, since a deny rule beats an allow rule a subject's settings might add.
        String settings = args.get(args.indexOf("--settings") + 1);
        assertTrue(settings.contains("\"disableAllHooks\":true") && settings.contains("\"deny\":[\"Bash\"]"), settings);
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
        String stubDir = env.getOrDefault("PATH_PREFIX", EVALS.resolve("consumer/test-support").toString());
        e.put(pathKey, stubDir + File.pathSeparator + e.getOrDefault(pathKey, ""));
        e.putAll(env);
        e.remove("PATH_PREFIX");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    }

    /** Forward slashes, which Git Bash's tools accept for a Windows path; a no-op elsewhere. */
    private static String unix(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
