package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code tools/consumer-sweep.sh} must report its own outcome in its exit status.
 *
 * <p>The script ended on a {@code printf}, so its status was that printf's: {@code 0}, whatever the
 * table above it said. A sweep in which every consumer failed, and a sweep in which every consumer
 * was skipped and nothing was built at all, were both indistinguishable from a clean pass to
 * anything that checks the status (<a href="https://github.com/PIsberg/vibetags/issues/806">issue
 * #806</a>).
 *
 * <p>That matters more here than in an ordinary script for two reasons. The script is invoked by an
 * agent following the {@code consumer-regression-suite} skill, and an agent that checks the exit
 * code and reports "sweep passed" is behaving correctly and is wrong. And the script is otherwise
 * unusually careful about this exact bug class: its own header says "Never let a pipe eat the exit
 * code", every build's real status is read from {@code $?} and printed in an {@code EXIT} column,
 * and then the aggregate was dropped at the last line.
 *
 * <p>Skip and failure are separated because they call for different actions. A failure means a
 * consumer is broken against this version. A skip means nothing is known about that consumer, which
 * is not a milder version of the same thing: it is the absence of a measurement.
 *
 * @see ConsumerSweepWorktreeExemptionTest ConsumerSweepWorktreeExemptionTest, which guards the
 *     related case of the loop ending early and printing a complete-looking footer anyway
 */
@DisplayName("The consumer sweep exits non-zero when it failed or measured nothing")
class ConsumerSweepExitCodeTest {

    // 0 is "everything built and passed", which no case here can arrange: every consumer these
    // tests build is synthetic and cannot produce a green Maven build. The two non-zero codes are
    // the ones that were indistinguishable from it, and they are what this pins.
    /** At least one consumer is broken against this version. */
    private static final int SOMETHING_FAILED = 1;
    /** Nothing failed, but at least one consumer was never built. */
    private static final int SOMETHING_SKIPPED = 2;

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    @Test
    @DisplayName("a sweep that skipped every consumer does not report success")
    void aSweepThatBuiltNothingExitsNonZero() throws Exception {
        Path script = requireScript();
        Path root = Files.createTempDirectory("sweep-exit-skip");
        dirtyRepoAt(root.resolve("blindbean"));
        dirtyRepoAt(root.resolve("codekarta"));

        // Both on the checkout path, where a dirty tree is still refused. No consumer takes
        // that path by default since #790, so the skip has to be asked for to be tested; what
        // is under test here is the exit code of a sweep that measured nothing, not the guard.
        Result result = runSweepInPlace(root, "blindbean codekarta", "blindbean", "codekarta");

        assertTrue(result.rows().stream().anyMatch(r -> r.contains("SKIP")),
            "precondition: both consumers should have been skipped for a dirty tree" + result);
        assertEquals(SOMETHING_SKIPPED, result.exitCode(),
            "the sweep built nothing at all and still reported success, so a caller that checks "
                + "the status cannot tell this from a clean run of all five consumers. "
                + script + result);
    }

    @Test
    @DisplayName("a sweep with a failing consumer does not report success")
    void aSweepWithAFailingBuildExitsNonZero() throws Exception {
        Path script = requireScript();
        Path root = Files.createTempDirectory("sweep-exit-fail");
        // Clean, so the dirty guard lets it through to a build, and the build cannot succeed:
        // the pom declares the version property the bump needs and nothing else Maven wants.
        cleanRepoWithUnbuildablePomAt(root.resolve("blindbean"));

        Result result = runSweep(root, "blindbean");

        assertTrue(result.rows().stream().anyMatch(r -> r.contains("FAIL") || r.contains("ERROR")),
            "precondition: the build should not have succeeded" + result);
        assertEquals(SOMETHING_FAILED, result.exitCode(),
            "a consumer failed against this version and the sweep reported success anyway"
                + result);
    }

    @Test
    @DisplayName("a failure outranks a skip, because it is the one that names a broken consumer")
    void aFailureOutranksASkip() throws Exception {
        requireScript();
        Path root = Files.createTempDirectory("sweep-exit-mixed");
        cleanRepoWithUnbuildablePomAt(root.resolve("blindbean"));
        dirtyRepoAt(root.resolve("codekarta"));

        Result result = runSweep(root, "blindbean", "codekarta");

        assertEquals(SOMETHING_FAILED, result.exitCode(),
            "with both a failure and a skip present the failure decides the status: it is the "
                + "one that says something is broken rather than unmeasured" + result);
    }

    /**
     * An {@code ERROR} before the build (worktree add, checkout, no version declaration) used to be
     * counted as attempted, the same as a {@code FAIL}, so the footer claimed a build that never
     * happened. In the 1.3.x pre-release sweep a {@code Filename too long} at {@code git worktree
     * add} left one consumer unbuilt while the footer read "Built 5 of 5" (#848). The exit status
     * was right; the sentence a reader trusts was not.
     */
    @Test
    @DisplayName("an error before the build is not counted as a build")
    void aPreBuildErrorIsNotCountedAsBuilt() throws Exception {
        requireScript();
        Path root = Files.createTempDirectory("sweep-exit-prebuild");
        cleanRepoWithUnbuildablePomAt(root.resolve("blindbean"));
        // No vibetags.version anywhere, so the bump refuses before any build starts.
        // common-license-lib rather than codekarta: codekarta is pinned to JDK 21-25 and is skipped
        // on a newer default JDK before it gets as far as the bump.
        repoAt(root.resolve("common-license-lib"), "<project/>");

        Result result = runSweep(root, "blindbean", "common-license-lib");

        assertTrue(result.rows().stream().anyMatch(r -> r.startsWith("common-license-lib")
                && r.contains("ERROR") && r.contains("no version declaration")),
            "precondition: common-license-lib should have errored before building" + result);
        String footer = result.rows().stream().filter(r -> r.startsWith("Built ")).findFirst()
            .orElseThrow(() -> new AssertionError("no footer" + result));
        assertEquals("Built 1 of 2 consumer(s): 1 failed, 1 errored before building, 0 skipped.", footer,
            "only blindbean reached a build; common-license-lib was never compiled and the footer "
                + "must not say it was" + result);
        assertEquals(SOMETHING_FAILED, result.exitCode(),
            "an error before the build is still a consumer nobody measured against this version, "
                + "and it is not a clean pass" + result);
    }

    // -------------------------------------------------------------------------

    /** The sweep's exit status and the lines it printed. */
    private record Result(int exitCode, List<String> rows) {
        @Override
        public String toString() {
            return System.lineSeparator() + "exit=" + exitCode + System.lineSeparator()
                + String.join(System.lineSeparator(), rows);
        }
    }

    private static Path requireScript() {
        Path script = REPO_ROOT.resolve("tools/consumer-sweep.sh");
        assumeTrue(Files.isRegularFile(script), "consumer-sweep.sh not reachable; skipping");
        return script;
    }

    /** Runs the sweep over a synthetic consumer root and keeps its exit status. */
    private static Result runSweep(Path root, String... repos) throws IOException, InterruptedException {
        return runSweepInPlace(root, null, repos);
    }

    /**
     * Runs the sweep with {@code inPlace} naming the consumers to sweep by checkout rather than in
     * a worktree, space separated, or {@code null} for the default (every consumer in a worktree).
     */
    private static Result runSweepInPlace(Path root, String inPlace, String... repos)
            throws IOException, InterruptedException {
        String[] args = new String[repos.length + 1];
        args[0] = "9.9.9";
        System.arraycopy(repos, 0, args, 1, repos.length);

        ProcessBuilder pb = new ProcessBuilder(ConsumerSweepShell.command("tools/consumer-sweep.sh", args));
        pb.directory(REPO_ROOT.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("VIBETAGS_CONSUMER_ROOT", root.toAbsolutePath().toString());
        if (inPlace != null) {
            pb.environment().put("VIBETAGS_SWEEP_IN_PLACE", inPlace);
        }
        // Keep the script's scratch directory inside the temp root: it rm -rf's its own worktree
        // path, which is shared with a real sweep run from the same machine.
        pb.environment().put("TMPDIR", root.toAbsolutePath().toString());
        Process sweep = pb.start();
        String out = new String(sweep.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(sweep.waitFor(), out.lines().toList());
    }

    /** A git repo with one commit and one uncommitted edit on top, which the dirty guard refuses. */
    private static void dirtyRepoAt(Path dir) throws IOException, InterruptedException {
        repoAt(dir, "<project><properties><vibetags.version>1.0.0</vibetags.version></properties></project>");
        Files.writeString(dir.resolve("NOTES.md"), "work in progress");
    }

    /**
     * A clean repo whose {@code pom.xml} carries the version property the bump looks for and is
     * not a buildable project. Maven fails on it whatever version is installed, and if no Maven is
     * on PATH at all the build fails to start, which is the same outcome for this test's purpose.
     */
    private static void cleanRepoWithUnbuildablePomAt(Path dir) throws IOException, InterruptedException {
        repoAt(dir, "<project><properties><vibetags.version>1.0.0</vibetags.version></properties></project>");
    }

    /** A git repo containing {@code pom}, committed on {@code main}, with an {@code origin} to match. */
    private static void repoAt(Path dir, String pom) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("pom.xml"), pom);
        git(dir, "add", "-A");
        git(dir, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "-m", "init");
        // The script checks out origin/main, so the repo needs one to check out.
        Path origin = dir.resolveSibling(dir.getFileName() + "-origin.git");
        git(dir, "init", "-q", "--bare", origin.toAbsolutePath().toString());
        git(dir, "remote", "add", "origin", origin.toAbsolutePath().toString());
        git(dir, "push", "-q", "origin", "main");
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
