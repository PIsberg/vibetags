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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The mutation score has a floor, and docs/WORKFLOW.md states the same number.
 *
 * <p>`mutation.yml` published a score and nothing could fail on it: the badge said 86% while a
 * change that dropped it to 70 would have gone green (issue #558). A threshold that can be deleted
 * without anything noticing is the same absence one commit later, and a threshold the documentation
 * disagrees with is worse than none — the number people plan around is the one they read.
 *
 * <p>This pins that both exist and agree. It does not run PIT: the score itself is measured by the
 * dispatched job, which is where a real regression is caught.
 */
@DisplayName("the PIT profile declares a floor and the docs state it")
class MutationThresholdTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final Pattern POM_MUTATION =
        Pattern.compile("<mutationThreshold>(\\d+)</mutationThreshold>");
    private static final Pattern POM_COVERAGE =
        Pattern.compile("<coverageThreshold>(\\d+)</coverageThreshold>");
    private static final Pattern DOC_MUTATION =
        Pattern.compile("<mutationThreshold>(\\d+)</mutationThreshold>");
    private static final Pattern DOC_COVERAGE =
        Pattern.compile("<coverageThreshold>(\\d+)</coverageThreshold>");

    @Test
    void theMutationProfileDeclaresAScoreFloorThatTheWorkflowDocRepeats() throws IOException {
        Path pom = REPO_ROOT.resolve("vibetags/pom.xml");
        Path doc = REPO_ROOT.resolve("docs/WORKFLOW.md");
        assumeTrue(Files.isRegularFile(pom) && Files.isRegularFile(doc),
            "repo layout not reachable from the test working directory; skipping");

        String pomText = Files.readString(pom, StandardCharsets.UTF_8);
        String docText = Files.readString(doc, StandardCharsets.UTF_8);

        String pomMutation = firstGroup(POM_MUTATION, pomText,
            "vibetags/pom.xml declares no <mutationThreshold>: PIT would publish a score that "
                + "nothing can fail on, which is what #558 was about");
        String pomCoverage = firstGroup(POM_COVERAGE, pomText,
            "vibetags/pom.xml declares no <coverageThreshold>");

        assertEquals(pomMutation, firstGroup(DOC_MUTATION, docText,
                "docs/WORKFLOW.md no longer states the mutation floor"),
            "the pom and docs/WORKFLOW.md must state the same mutation floor; the number people "
                + "plan around is the one they read");
        assertEquals(pomCoverage, firstGroup(DOC_COVERAGE, docText,
                "docs/WORKFLOW.md no longer states the line-coverage floor"),
            "the pom and docs/WORKFLOW.md must state the same line-coverage floor");
    }

    private static String firstGroup(Pattern pattern, String text, String message) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), message);
        return m.group(1);
    }
}
