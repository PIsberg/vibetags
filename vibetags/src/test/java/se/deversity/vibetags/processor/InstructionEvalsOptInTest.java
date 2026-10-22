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
 * Instruction Evals run when someone asks for them, not on every PR that edits an instruction file.
 *
 * <p>A default run is 18 paid headless sessions. The path trigger started that bill on every PR
 * touching {@code CLAUDE.md} or {@code evals/**}, including PRs where nobody wanted a measurement.
 * The opt-in is the {@value #LABEL} label on the pull request, or a manual dispatch. The label is
 * checked in the preflight job, so an unlabelled PR never reaches the evals job.
 */
@DisplayName("Instruction evals run on request: a label or a manual dispatch, never a path match")
class InstructionEvalsOptInTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final String WORKFLOW = ".github/workflows/instruction-evals.yml";
    private static final String LABEL = "run-instruction-evals";

    @Test
    @DisplayName("touching an instruction file does not start a pull request run")
    void pullRequestTriggerHasNoPathFilter() throws IOException {
        String pullRequest = section(triggers(), "  pull_request:", "\n  [a-z]");
        assertFalse(pullRequest.contains("paths:"),
            WORKFLOW + " still starts on a path match, so every PR editing an instruction file "
                + "pays for 18 sessions:\n" + pullRequest);
    }

    @Test
    @DisplayName("a pull request run starts when the opt-in label is added, and again on each push")
    void pullRequestTriggerListensForTheLabel() throws IOException {
        String pullRequest = section(triggers(), "  pull_request:", "\n  [a-z]");
        assertTrue(pullRequest.contains("labeled") && pullRequest.contains("synchronize"),
            WORKFLOW + " does not run on the label event, so the label cannot start the evals:\n"
                + pullRequest);
    }

    @Test
    @DisplayName("the preflight, and so every job after it, runs only for the label or a dispatch")
    void preflightIsGatedOnTheLabelOrADispatch() throws IOException {
        String preflight = section(read(), "  preflight:", "\n  [a-z]");
        String condition = preflight.lines()
            .filter(line -> line.strip().startsWith("if:"))
            .findFirst()
            .orElse("");
        assertTrue(condition.contains("workflow_dispatch") && condition.contains(LABEL),
            WORKFLOW + "'s preflight has no opt-in condition (want workflow_dispatch or the '"
                + LABEL + "' label), so an unlabelled PR still runs the evals:\n" + preflight);
    }

    /** The {@code on:} block, from its key to the top-level {@code permissions:} key. */
    private static String triggers() throws IOException {
        return section(read(), "\non:", "\npermissions:");
    }

    /** From {@code start} to the next match of {@code endRegex}, or the end of the text. */
    private static String section(String text, String start, String endRegex) {
        int at = text.indexOf(start);
        assertTrue(at >= 0, WORKFLOW + " has no '" + start.strip() + "'; if it was renamed, update this test");
        Matcher end = Pattern.compile(endRegex).matcher(text);
        int stop = end.find(at + start.length()) ? end.start() : text.length();
        return text.substring(at, stop);
    }

    private static String read() throws IOException {
        Path file = REPO_ROOT.resolve(WORKFLOW);
        assertTrue(Files.isRegularFile(file), WORKFLOW + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
