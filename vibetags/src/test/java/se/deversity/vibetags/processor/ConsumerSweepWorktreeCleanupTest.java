package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An earlier sweep's worktree under another TMPDIR must be cleaned up before {@code worktree add},
 * but worktrees not matching the sweep's own naming must be preserved (#736).
 */
@DisplayName("The consumer sweep cleans up stale worktrees from earlier sweeps under different TMPDIRs")
class ConsumerSweepWorktreeCleanupTest {

    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    @Test
    @DisplayName("an earlier sweep worktree under another TMPDIR is removed and the sweep succeeds")
    void earlierSweepWorktreeUnderAnotherTmpdirIsRemoved() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        Path repoDir = root.resolve("async-test-lib");
        syntheticRepoAt(repoDir);

        Path oldTmp = Files.createTempDirectory("old-sweep-tmp");
        Path oldWt = oldTmp.resolve("vibetags-sweep/wt-async-test-lib");
        Files.createDirectories(oldWt.getParent());

        git(repoDir, "worktree", "add", "-q", "-B", "chore/vibetags-9.9.9",
            oldWt.toAbsolutePath().toString(), "origin/main");
        assertTrue(Files.isDirectory(oldWt), "old worktree must be initialized");

        Path newTmp = Files.createTempDirectory("new-sweep-tmp");
        List<String> rows = runSweep(root, newTmp, "9.9.9", "async-test-lib");

        String worktreeRow = rowFor(rows, "async-test-lib");
        String all = String.join(System.lineSeparator(), rows);

        assertFalse(worktreeRow.contains("ERROR"),
            "stale worktree under another TMPDIR caused worktree add to fail (#736). Row was: "
                + worktreeRow + System.lineSeparator() + all);
        assertTrue(worktreeRow.contains("PASS"),
            "sweep should pass after cleaning up stale sweep worktree. Row was: "
                + worktreeRow + System.lineSeparator() + all);
        assertFalse(Files.exists(oldWt),
            "stale sweep worktree directory should be removed: " + oldWt);
    }

    @Test
    @DisplayName("a non-sweep worktree holding the branch skips the repo and is preserved")
    void nonSweepWorktreeHoldingBranchSkipsRepoAndIsPreserved() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        Path root = Files.createTempDirectory("sweep-consumer-root");
        Path repoDir = root.resolve("async-test-lib");
        syntheticRepoAt(repoDir);

        Path agentDir = Files.createTempDirectory("agent-worktrees");
        Path agentWt = agentDir.resolve("agent-feature-worktree");

        git(repoDir, "worktree", "add", "-q", "-B", "chore/vibetags-9.9.9",
            agentWt.toAbsolutePath().toString(), "origin/main");
        assertTrue(Files.isDirectory(agentWt), "agent worktree must be initialized");

        Path newTmp = Files.createTempDirectory("new-sweep-tmp");
        List<String> rows = runSweep(root, newTmp, "9.9.9", "async-test-lib");

        String worktreeRow = rowFor(rows, "async-test-lib");
        String all = String.join(System.lineSeparator(), rows);

        assertTrue(worktreeRow.contains("SKIP"),
            "non-sweep worktree holding branch should cause SKIP, not ERROR or deletion. Row was: "
                + worktreeRow + System.lineSeparator() + all);
        assertTrue(Files.isDirectory(agentWt),
            "non-sweep worktree must never be deleted by the sweep: " + agentWt);
    }

    private static List<String> runSweep(Path root, Path tmpDir, String version, String repo)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "sh", "tools/consumer-sweep.sh", version, repo);
        pb.directory(REPO_ROOT.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("VIBETAGS_CONSUMER_ROOT", root.toAbsolutePath().toString());
        pb.environment().put("TMPDIR", tmpDir.toAbsolutePath().toString());
        Process sweep;
        try {
            sweep = pb.start();
        } catch (IOException noShell) {
            assumeTrue(false, "no sh on PATH; the Linux CI job runs this");
            return List.of();
        }
        String out = new String(sweep.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        sweep.waitFor();
        return out.lines().toList();
    }

    private static String rowFor(List<String> rows, String repo) {
        Optional<String> row = rows.stream().filter(l -> l.startsWith(repo + " ")).findFirst();
        assertTrue(row.isPresent(),
            "the sweep printed no row at all for " + repo + ". Row output: "
                + String.join(System.lineSeparator(), rows));
        return row.get();
    }

    private static void syntheticRepoAt(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        git(dir, "init", "-q");
        Files.writeString(dir.resolve("pom.xml"),
            "<project><properties><vibetags.version>1.0.0</vibetags.version></properties></project>");
        Path mvnw = dir.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\nexit 0\n");
        mvnw.toFile().setExecutable(true);
        Path gradlew = dir.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\nexit 0\n");
        gradlew.toFile().setExecutable(true);

        git(dir, "add", "-A");
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "-m", "init");
        git(dir, "update-ref", "refs/remotes/origin/main", "HEAD");
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor() == 0, "git " + String.join(" ", args) + " failed: " + log);
    }
}
