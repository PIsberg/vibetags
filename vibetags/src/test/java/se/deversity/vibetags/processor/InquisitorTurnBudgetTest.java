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
 * The Inquisitor has turns enough to reach a verdict on a multi-issue pull request.
 *
 * <p>At {@code --max-turns 40} the review of PR #1000 (four issues, five commits, 32 files) ran out
 * of turns twice, on two heads, at about $3.25 to $3.56 a run, and posted nothing. The action
 * reports that as a failed check, which reads like a finding and is not one: the reviewer never
 * wrote its report. A small pull request finishes well inside the budget and pays nothing for the
 * headroom.
 */
@DisplayName("The Inquisitor's turn budget covers a multi-issue pull request")
class InquisitorTurnBudgetTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /** Twice the budget that ran out on PR #1000, twice. */
    private static final int MINIMUM_TURNS = 80;

    @Test
    @DisplayName("--max-turns is at least 80")
    void maxTurnsCoversAMultiIssuePullRequest() throws IOException {
        Path workflow = REPO_ROOT.resolve(".github/workflows/inquisitor.yml");
        assertTrue(Files.isRegularFile(workflow), "inquisitor.yml is missing");
        Matcher turns = Pattern.compile("--max-turns\\s+(\\d+)")
            .matcher(Files.readString(workflow, StandardCharsets.UTF_8));

        assertTrue(turns.find(), "inquisitor.yml no longer passes --max-turns; if the action changed how "
            + "the budget is set, update this test");
        int budget = Integer.parseInt(turns.group(1));
        assertTrue(budget >= MINIMUM_TURNS, "the Inquisitor's --max-turns is " + budget + ". At 40 the "
            + "review of a four-issue pull request (#1000) ran out of turns before writing a report, "
            + "twice, and the check failed with no finding");
    }
}
