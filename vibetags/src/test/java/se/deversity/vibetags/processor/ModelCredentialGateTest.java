package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two model-backed workflows accept either credential, and each one they accept reaches the model.
 *
 * <p>Instruction Evals and the Inquisitor are gated by a {@code preflight} job, because
 * {@code jobs.<id>.if} cannot read secrets: no credential means the job reads Skipped instead of
 * a green tick it did not earn (#632, #697). The preflight used to look only for
 * {@code ANTHROPIC_API_KEY}, so a repository holding a Claude subscription token
 * ({@code claude setup-token}) skipped both gates for good while looking configured.
 *
 * <p>The two halves have to agree. A preflight that accepts the token while the model step is
 * handed only the API key starts a job with no credential; a step handed the token behind a
 * preflight that never looks for it is dead code. Both drift silently, since nothing else in the
 * build reads these files.
 */
@DisplayName("Model-backed workflows accept either credential and pass it to the model")
class ModelCredentialGateTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final String API_KEY = "secrets.ANTHROPIC_API_KEY";
    private static final String OAUTH_TOKEN = "secrets.CLAUDE_CODE_OAUTH_TOKEN";

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "instruction-evals.yml, '- name: Run the task bank'",
        "inquisitor.yml,        '- name: Run the Inquisitor'",
    })
    @DisplayName("the preflight accepts either secret and the model step receives both")
    void eitherCredentialReachesTheModel(String workflow, String modelStep) throws IOException {
        String text = read(".github/workflows/" + workflow);

        String preflight = section(text, "  preflight:", "\n  [a-z]", workflow);
        int have = preflight.indexOf("HAVE_KEY:");
        assertTrue(have >= 0, workflow + "'s preflight no longer sets HAVE_KEY:\n" + preflight);
        String condition = preflight.substring(have, preflight.indexOf('\n', have));
        assertTrue(condition.contains(API_KEY) && condition.contains(OAUTH_TOKEN),
            workflow + "'s preflight does not accept both credentials, so a repository holding only "
                + "the other one skips this gate while looking configured: " + condition);

        String step = section(text, modelStep, "\n      - ", workflow);
        assertTrue(step.contains(API_KEY) && step.contains(OAUTH_TOKEN),
            "the step that runs the model in " + workflow + " is not handed both credentials, so "
                + "the preflight can start a job that has none:\n" + step);
    }

    /** From {@code start} to the next match of {@code endRegex}, or the end of the file. */
    private static String section(String text, String start, String endRegex, String workflow) {
        int at = text.indexOf(start);
        assertTrue(at >= 0, workflow + " has no '" + start.strip() + "'; if it was renamed, update this test");
        Matcher end = Pattern.compile(endRegex).matcher(text);
        int stop = end.find(at + start.length()) ? end.start() : text.length();
        return text.substring(at, stop);
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
