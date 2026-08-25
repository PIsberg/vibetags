package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Node version pinned in {@code instruction-evals.yml} must satisfy the claude CLI's own
 * {@code engines.node} range, read out of {@code evals/package-lock.json}.
 *
 * <p>The lockfile pins the CLI by integrity hash; nothing pinned the {@code node} and {@code npm}
 * that install it, so the job ran on whatever {@code ubuntu-latest} shipped that week. That is not
 * only plumbing: npm 11.17 added an allow-scripts gate that can defer a package's postinstall, and
 * this package's postinstall is what replaces its 500-byte placeholder with the native binary. A
 * runner npm that starts deferring scripts leaves the stub behind.
 *
 * <p>Pinning creates the opposite failure — a pin that rots. Dependabot raises the CLI on its own
 * schedule, and when a bump raises {@code engines.node} past the pinned runtime, {@code npm ci}
 * fails on the runner partway through a job that costs money to reach. Nothing in Dependabot
 * watches a {@code node-version} literal inside a workflow, so this test is what watches it: it
 * compares the two numbers directly and fails in the fast test tier, before any model session runs.
 *
 * <p>Deliberately not {@code assumeTrue} on a missing file. A skipped parity check reads exactly
 * like a passed one, and the whole point here is that the pin cannot go unwatched.
 */
@DisplayName("The evals workflow pins a Node the claude CLI will actually run on")
class EvalsNodePinTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /** Matches {@code node-version: '22.23.2'} — quoted, because YAML would read 22.23 as a float. */
    private static final Pattern NODE_VERSION =
        Pattern.compile("node-version:\\s*['\"]?(\\d+)(?:\\.\\d+)*['\"]?");

    /** Matches {@code "node": ">=22.0.0"} inside the CLI package's engines block. */
    private static final Pattern ENGINES_NODE =
        Pattern.compile("\"node\"\\s*:\\s*\"[^\"\\d]*(\\d+)");

    @Test
    void thePinnedNodeSatisfiesTheClaudeCliEnginesFloor() throws IOException {
        Path workflow = REPO_ROOT.resolve(".github/workflows/instruction-evals.yml");
        Path lockfile = REPO_ROOT.resolve("evals/package-lock.json");
        assertTrue(Files.isRegularFile(workflow), "workflow not found at " + workflow);
        assertTrue(Files.isRegularFile(lockfile), "lockfile not found at " + lockfile);

        String yaml = Files.readString(workflow, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("actions/setup-node@"),
            "instruction-evals.yml no longer pins a Node toolchain, so the job is back on whatever "
                + "node and npm the runner image ships — the state issue #472 was opened about. "
                + "If that is intended, delete this test rather than let it pass vacuously");

        Matcher pinned = NODE_VERSION.matcher(yaml);
        assertTrue(pinned.find(),
            "instruction-evals.yml uses actions/setup-node without an explicit node-version, so "
                + "the action falls back to the runner's default and pins nothing");
        int pinnedMajor = Integer.parseInt(pinned.group(1));

        String lock = Files.readString(lockfile, StandardCharsets.UTF_8);
        Matcher floor = ENGINES_NODE.matcher(lock);
        assertTrue(floor.find(),
            "evals/package-lock.json declares no engines.node for the claude CLI, so there is "
                + "nothing to check the pin against — read the range by hand before trusting this");
        int requiredMajor = Integer.parseInt(floor.group(1));

        assertTrue(pinnedMajor >= requiredMajor,
            "instruction-evals.yml pins Node " + pinnedMajor + " but the claude CLI in "
                + "evals/package-lock.json requires Node " + requiredMajor + " or newer. A CLI "
                + "bump raised the floor past the pin; `npm ci` will fail on the runner partway "
                + "into a job that costs real money to reach. Raise node-version to " + requiredMajor
                + " or above, preferring an LTS line.");
    }
}
