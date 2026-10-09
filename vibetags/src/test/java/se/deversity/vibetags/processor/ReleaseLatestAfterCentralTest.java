package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A release is marked Latest only once Maven Central serves every module (#946).
 *
 * <p>On 2026-10-08 a publish failed after {@code gh release create --latest}, and the
 * release page went on advertising a Latest release whose five poms answered 404 on
 * repo1.maven.org. The release is now created with {@code --latest=false}, and {@code publish.yml}
 * promotes it after polling repo1 for every module. Nothing else fails when either half is undone:
 * the next release would look normal until its publish failed.
 */
@DisplayName("A release is marked Latest only after Maven Central serves it")
class ReleaseLatestAfterCentralTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final String DEPLOY = "Sign and deploy every module to Maven Central as one deployment";
    private static final String WAIT = "Wait until Maven Central serves every module";
    private static final String MARK = "Mark the release Latest";
    private static final String ATTACH = "Attach signed artifacts to the GitHub release";

    /** A {@code gh release create} command, through to the end of its continued lines. */
    private static final Pattern RELEASE_CREATE =
        Pattern.compile("gh release create(?:[^\\n]*\\\\\\n)*[^\\n]*");

    @Test
    @DisplayName("every documented gh release create passes --latest=false")
    void releasesAreCreatedNotLatest() throws IOException {
        for (String doc : List.of(".claude/skills/release/SKILL.md", "docs/RELEASING.md")) {
            List<String> commands = new ArrayList<>();
            Matcher m = RELEASE_CREATE.matcher(read(doc));
            while (m.find()) {
                if (m.group().contains("--title")) {
                    commands.add(m.group());
                }
            }
            assertFalse(commands.isEmpty(), doc + " has no gh release create command with a --title; "
                + "this test no longer finds the release step and checks nothing");
            for (String command : commands) {
                assertTrue(command.contains("--latest=false") && !command.matches("(?s).*--latest(?!=false).*"),
                    doc + " creates the release as Latest before Central has it. Pass --latest=false; "
                        + "publish.yml marks it Latest once every module resolves (#946):\n" + command);
            }
        }
    }

    @Test
    @DisplayName("publish.yml waits for repo1, then marks Latest, then attaches")
    void publishMarksLatestAfterRepo1ServesEveryModule() throws IOException {
        String publish = read(".github/workflows/publish.yml");
        int deploy = stepIndex(publish, DEPLOY);
        int wait = stepIndex(publish, WAIT);
        int mark = stepIndex(publish, MARK);
        int attach = stepIndex(publish, ATTACH);
        assertTrue(deploy < wait && wait < mark && mark < attach,
            "publish.yml must deploy, then wait for repo1, then mark the release Latest, then attach "
                + "the signed artifacts (the attach action marks a release Latest on its own).");

        String waitBlock = publish.substring(wait, mark);
        assertTrue(waitBlock.contains("repo1.maven.org"), "'" + WAIT + "' must poll repo1.maven.org");
        for (String module : List.of("annotations", "processor", "ksp", "bom", "cli")) {
            assertTrue(waitBlock.contains(module),
                "'" + WAIT + "' does not poll vibetags-" + module + "; Latest would go up before it resolves.");
        }
        String markBlock = publish.substring(mark, attach);
        assertTrue(markBlock.contains("gh release edit") && markBlock.contains("--latest"),
            "'" + MARK + "' must run gh release edit --latest");
    }

    @Test
    @DisplayName("a resume deploys only the modules repo1 does not serve yet")
    void aResumeSkipsModulesCentralAlreadyServes() throws IOException {
        String publish = read(".github/workflows/publish.yml");
        String selection = publish.substring(stepIndex(publish, "Resolve which modules to deploy"),
            stepIndex(publish, DEPLOY));
        assertTrue(selection.contains("repo1.maven.org"),
            "the module selection must leave out modules repo1 already serves: a resume of a release "
                + "that published but was never marked Latest would otherwise deploy an empty bundle.");
        String deployStep = publish.substring(stepIndex(publish, DEPLOY));
        assertTrue(deployStep.substring(0, deployStep.indexOf("run:")).contains("steps.selection.outputs.deploy"),
            "the deploy step must be skipped when every module is already on Central");
    }

    private static int stepIndex(String workflow, String name) {
        int at = workflow.indexOf("- name: " + name);
        assertTrue(at >= 0, "publish.yml has no step named '" + name + "'");
        return at;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative), StandardCharsets.UTF_8);
    }
}
