package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The release process has to run the consumer sweep, and the sweep has to exist to be run.
 *
 * <p>VibeTags writes files that consumers commit. A change to a renderer, or to how an element is
 * named, moves those files in every downstream repository on the next upgrade, and nothing in this
 * repository's own CI can see that: the fixtures and the third-party corpus are both projects with
 * no committed VibeTags output of their own to move.
 *
 * <p>#480 is the worked example. It changed the element identity written into
 * {@code .vibetags-locks}, into every {@code path=} attribute and into granular rule filenames, and
 * for a project using jspecify that moves committed files. Two independent reasons nothing
 * noticed, both of them the normal case rather than bad luck: this repository uses jspecify
 * heavily but never on a parameter of an annotated method, which is the only place a parameter
 * type reaches an element path; and the consumers are pinned to the previous release, so they had
 * never run the change. {@code scripts/consumer-sweep.sh} is the only thing that would have run it
 * for them, and it ran in no workflow and at no step (#490).
 *
 * <p>So the gate lives at the release, which is the moment the consequence becomes real. This test
 * is what stops it being quietly dropped: a checklist step is prose, and prose gets tidied.
 */
@DisplayName("The release process gates on the consumer sweep")
class ReleaseConsumerSweepGateTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    @Test
    @DisplayName("the release skill runs the consumer sweep before opening the release PR")
    void releaseSkillRunsTheConsumerSweep() throws IOException {
        Path skill = REPO_ROOT.resolve(".claude/skills/release/SKILL.md");
        Path script = REPO_ROOT.resolve("scripts/consumer-sweep.sh");

        // Deliberately not assumeTrue on a missing file. A skipped check reads exactly like a
        // passed one, and this exists because something that ran nowhere looked fine for months.
        assertTrue(Files.isRegularFile(script),
            "scripts/consumer-sweep.sh is gone, so the release gate below refers to a script that "
                + "does not exist. If the sweep moved, point this test and the release skill at "
                + "wherever it went; if it was deleted, #490 needs reopening rather than the test "
                + "deleting.");
        assertTrue(Files.isRegularFile(skill),
            "the release skill is missing at " + skill);

        String text = Files.readString(skill, StandardCharsets.UTF_8);

        assertTrue(text.contains("consumer-sweep.sh"),
            "the release skill no longer runs scripts/consumer-sweep.sh. Without it a release "
                + "goes out having been verified against this repository's fixtures and the "
                + "third-party corpus only, neither of which has committed VibeTags output to "
                + "move, and downstream maintainers find a diff they did not cause (#490).");

        int sweepAt = text.indexOf("consumer-sweep.sh");
        int prAt = text.indexOf("gh pr create");
        assertTrue(prAt < 0 || sweepAt < prAt,
            "the consumer sweep is described after the release PR is opened. The point of the "
                + "sweep is to decide what the CHANGELOG and release notes have to say about "
                + "downstream drift, which is too late once the PR body is written.");

        // The honest-reporting requirement is the half that is easy to lose: a sweep whose result
        // nobody has to act on is a step people run and then ignore.
        assertTrue(text.contains("not run") || text.contains("Could not run"),
            "the release skill runs the sweep but does not say what to do when a consumer could "
                + "not be swept. A skipped consumer is not a passing consumer, and the difference "
                + "has to be written down or it will be reported as success.");
    }
}
