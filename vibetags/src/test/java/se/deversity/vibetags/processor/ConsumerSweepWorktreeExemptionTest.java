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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The dirty-tree guard in {@code tools/consumer-sweep.sh} must not apply to a repo swept in a
 * worktree.
 *
 * <p>The guard refuses to sweep a consumer with uncommitted work, and for the checkout path that
 * is right: {@code git checkout -B} switches the branch of the very checkout those edits live
 * in. The worktree path does no such thing. {@code git worktree add} builds a separate directory
 * from {@code origin/main} and never touches the contended checkout or its index.
 *
 * <p>A repo is in {@code WORKTREE_REPOS} precisely because somebody else works in it, so a dirty
 * checkout is its normal state rather than an exception. With the guard applied to it,
 * {@code async-test-lib} was skipped on every sweep (#617). That is the consumer worth losing
 * least: it is the only one that commits its {@code .vibetags-mod-*} sidecars, which is the file
 * class #590 was found through.
 *
 * <p>The failure is silent, which is why this is a test rather than a comment. The sweep's footer
 * prints the same words whether the loop covered five consumers or four, so a partial sweep reads
 * exactly like a complete one.
 */
@DisplayName("The consumer sweep exempts worktree repos from the dirty-tree guard")
class ConsumerSweepWorktreeExemptionTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    @Test
    @DisplayName("a dirty checkout skips an ordinary consumer but not one swept in a worktree")
    void aDirtyCheckoutDoesNotSkipAWorktreeConsumer() throws Exception {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");

        // blindbean is swept by checkout, async-test-lib by worktree. Both start dirty.
        Path root = Files.createTempDirectory("sweep-consumer-root");
        dirtyRepoAt(root.resolve("blindbean"));
        dirtyRepoAt(root.resolve("async-test-lib"));

        List<String> rows = runSweep(root);

        String checkoutRow = rowFor(rows, "blindbean");
        String worktreeRow = rowFor(rows, "async-test-lib");
        String all = String.join(System.lineSeparator(), rows);

        assertTrue(checkoutRow.contains("working tree dirty"),
            "a consumer swept by checkout must still be refused while it has uncommitted work, "
                + "because checkout -B would switch the branch under those edits. Row was: "
                + checkoutRow + System.lineSeparator() + all);

        assertTrue(!worktreeRow.contains("working tree dirty"),
            "async-test-lib was skipped for having a dirty checkout, but it is swept in a "
                + "worktree, which cannot touch that checkout at all. A repo is in "
                + "WORKTREE_REPOS because someone else works in it, so dirty is its normal "
                + "state and this guard removes it from every sweep (#617). Row was: "
                + worktreeRow + System.lineSeparator() + all);
    }

    /** Runs the sweep over a synthetic consumer root, with no build reached by either repo. */
    private static List<String> runSweep(Path root) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "sh", "tools/consumer-sweep.sh", "9.9.9", "blindbean", "async-test-lib");
        pb.directory(REPO_ROOT.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("VIBETAGS_CONSUMER_ROOT", root.toAbsolutePath().toString());
        // Keep the script's scratch directory inside the temp root. It rm -rf's its own worktree
        // path, and that path is shared with a real sweep run from the same machine.
        pb.environment().put("TMPDIR", root.toAbsolutePath().toString());
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

    /** The row the sweep printed for one repo, or a message naming what it printed instead. */
    private static String rowFor(List<String> rows, String repo) {
        Optional<String> row = rows.stream().filter(l -> l.startsWith(repo + " ")).findFirst();
        assertTrue(row.isPresent(),
            "the sweep printed no row at all for " + repo + ". Every consumer named on the "
                + "command line gets a row, so a missing one means the loop ended early: "
                + String.join(System.lineSeparator(), rows));
        return row.get();
    }

    /** A git repo with one commit and one uncommitted edit on top. */
    private static void dirtyRepoAt(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        git(dir, "init", "-q");
        Files.writeString(dir.resolve("pom.xml"),
            "<project><properties><vibetags.version>1.0.0</vibetags.version></properties></project>");
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "-m", "init");
        // The uncommitted edit that the guard reacts to.
        Files.writeString(dir.resolve("NOTES.md"), "work in progress");
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
