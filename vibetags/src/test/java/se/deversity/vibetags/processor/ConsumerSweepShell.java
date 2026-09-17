package se.deversity.vibetags.processor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Finds the POSIX shell the consumer-sweep tests run {@code tools/consumer-sweep.sh} with.
 *
 * <p>Those tests used to start a plain {@code sh} and skip when it could not be started. Maven run
 * from PowerShell or cmd on Windows has no {@code sh} on PATH, so every one of them was reported
 * as skipped, in a summary line that reads like a pass unless the skip count is noticed (#744).
 * Git for Windows ships a shell, and these tests need git anyway, so it is located through
 * {@code git --exec-path}. Only {@code bin/sh.exe} will do: {@code usr/bin/sh.exe}, started from
 * outside Git Bash, resolves no {@code awk}, {@code grep} or {@code cut}, which the script needs.
 *
 * <p>Under CI a missing shell fails the test instead of skipping it, so a CI leg cannot report
 * these tests green without running them.
 */
final class ConsumerSweepShell {

    private ConsumerSweepShell() {
    }

    /**
     * The command that runs {@code script} with {@code args}. Aborts the calling test when no
     * shell can be found, or fails it when running under CI.
     */
    static List<String> command(String script, String... args) {
        Optional<String> shell = choose(runs("sh"), gitRoot());
        if (shell.isEmpty()) {
            skipOrFail("true".equalsIgnoreCase(System.getenv("CI")));
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(shell.orElseThrow());
        cmd.add(script);
        cmd.addAll(List.of(args));
        return cmd;
    }

    /** {@code sh} when it runs from PATH, else Git for Windows' {@code bin/sh.exe} when present. */
    static Optional<String> choose(boolean shOnPath, Optional<Path> gitRoot) {
        if (shOnPath) {
            return Optional.of("sh");
        }
        return gitRoot
            .map(root -> root.resolve("bin").resolve("sh.exe"))
            .filter(Files::isRegularFile)
            .map(sh -> sh.toAbsolutePath().toString());
    }

    /** Fails under CI, where a skip would be reported as a green check; aborts the test elsewhere. */
    static void skipOrFail(boolean ci) {
        String message = "no sh on PATH and no Git for Windows bin/sh.exe to run tools/consumer-sweep.sh with (#744)";
        if (ci) {
            fail(message + "; under CI this test must run, not skip");
        }
        abort(message);
    }

    /**
     * The Git for Windows install root for a {@code git --exec-path} of
     * {@code <root>/mingw64/libexec/git-core}; empty for any other layout.
     */
    static Optional<Path> rootFromExecPath(String execPath) {
        Path gitCore = Paths.get(execPath.trim());
        Path libexec = gitCore.getParent();
        Path platform = libexec == null ? null : libexec.getParent();
        Path root = platform == null ? null : platform.getParent();
        if (root == null || !"libexec".equals(String.valueOf(libexec.getFileName()))) {
            return Optional.empty();
        }
        return Optional.of(root);
    }

    private static Optional<Path> gitRoot() {
        try {
            Process git = new ProcessBuilder("git", "--exec-path").redirectErrorStream(true).start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return git.waitFor() == 0 ? rootFromExecPath(out) : Optional.empty();
        } catch (IOException noGit) {
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static boolean runs(String shell) {
        try {
            Process probe = new ProcessBuilder(shell, "-c", "exit 0").redirectErrorStream(true).start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor() == 0;
        } catch (IOException notOnPath) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
