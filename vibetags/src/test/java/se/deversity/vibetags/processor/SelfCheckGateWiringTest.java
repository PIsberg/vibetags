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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI's self-check and the local pre-commit hook run the same script, so they cannot disagree.
 *
 * <p>The self-check regenerates this repository's own guardrail files and fails on any
 * difference. Until {@code tools/self-check.sh} it existed only as an inline step in
 * {@code build.yml}, so drift surfaced one CI round-trip after the push. That was paid for in PRs
 * #687, #793, #797 and #862 (a line added above a locked method moves its recorded range in
 * {@code .vibetags-locks}) and #861 (a regeneration moved the line count README.md quotes).
 *
 * <p>The failure this test guards against is the twin drifting: someone edits the CI step inline
 * again, or narrows the hook's file filter, and the local check quietly stops being the CI check.
 * The hook would then pass commits CI rejects, which is the situation it was added to end.
 */
@DisplayName("CI's self-check and the pre-commit hook run the same script")
class SelfCheckGateWiringTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final String SCRIPT = "tools/self-check.sh";

    @Test
    @DisplayName("build.yml's self-check step calls the script rather than an inline copy")
    void ciStepCallsTheScript() throws IOException {
        String workflow = read(".github/workflows/build.yml");
        int at = workflow.indexOf("- name: Verify VibeTags' Own Guardrails Are Current (self-check)");
        assertTrue(at >= 0,
            "build.yml has no self-check step. Removing it leaves the committed CLAUDE.md, "
                + ".claude/rules/ and .vibetags-locks unverified on every PR; if it was renamed, "
                + "update this test.");
        int next = workflow.indexOf("- name:", at + 1);
        String step = workflow.substring(at, next < 0 ? workflow.length() : next);

        assertTrue(step.contains(SCRIPT),
            "the self-check step no longer runs " + SCRIPT + ", so the pre-commit hook and CI "
                + "are two copies of one check that can drift apart:\n" + step);
        assertFalse(step.contains("-Pself-annotate"),
            "the self-check step runs Maven inline again. Put the change in " + SCRIPT
                + " so the pre-commit hook gets it too:\n" + step);
    }

    @Test
    @DisplayName("the pre-commit hook calls the script and fires on the files that move its output")
    void preCommitHookCallsTheScript() throws IOException {
        String config = read(".pre-commit-config.yaml");
        int at = config.indexOf("id: vibetags-self-check");
        assertTrue(at >= 0,
            ".pre-commit-config.yaml has no vibetags-self-check hook, so self-check drift is "
                + "found only after a push again.");
        int next = config.indexOf("- id:", at + 1);
        String hook = config.substring(at, next < 0 ? config.length() : next);

        assertTrue(hook.contains("entry: " + SCRIPT),
            "the vibetags-self-check hook does not run " + SCRIPT + ":\n" + hook);

        Matcher files = Pattern.compile("(?m)^\\s*files:\\s*(\\S+)\\s*$").matcher(hook);
        assertTrue(files.find(), "the vibetags-self-check hook has no files: filter:\n" + hook);
        Pattern filter = Pattern.compile(files.group(1));
        // pre-commit applies the filter with Python's re.search, which find() mirrors.
        for (String path : new String[] {
            "vibetags/src/main/java/se/deversity/vibetags/processor/AIGuardrailProcessor.java",
            "vibetags/src/test/java/se/deversity/vibetags/processor/SelfCheckGateWiringTest.java",
            "vibetags-annotations/src/main/java/se/deversity/vibetags/annotations/AILocked.java",
            "README.md",
        }) {
            assertTrue(filter.matcher(path).find(),
                "the vibetags-self-check hook does not fire for " + path + ", which can change "
                    + "the regenerated output or the counts ProjectFactsConsistencyTest pins. "
                    + "Filter: " + files.group(1));
        }
    }

    @Test
    @DisplayName("the script reproduces a clean clone and checks the README counts after regenerating")
    void scriptReproducesCi() throws IOException {
        Path script = REPO_ROOT.resolve(SCRIPT);
        // Deliberately not assumeTrue on a missing file: a skipped check reads like a passed one.
        assertTrue(Files.isRegularFile(script), SCRIPT + " is missing");
        String text = Files.readString(script, StandardCharsets.UTF_8);

        assertTrue(text.contains("rm -f .vibetags-mod-* .vibetags-cache"),
            SCRIPT + " no longer deletes the gitignored sidecars and write cache first. A "
                + "leftover .vibetags-mod-* merges into the regeneration, so the check passes "
                + "on a machine that has built before and fails in CI on the same commit (#794).");
        assertTrue(text.contains("test-compile") || text.contains(" test "),
            SCRIPT + " regenerates with a main-only compile. TESTING.md routing needs the test "
                + "round, so every test guardrail would be reported as drift.");
        assertTrue(text.contains("-Dtest=ProjectFactsConsistencyTest"),
            SCRIPT + " no longer runs ProjectFactsConsistencyTest after regenerating. That test "
                + "pins the line counts README.md quotes for the regenerated files, which is the "
                + "drift PR #861 found only in CI.");
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
