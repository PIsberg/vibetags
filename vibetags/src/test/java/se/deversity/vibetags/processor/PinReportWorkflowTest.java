package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The weekly pin report (#1135) is the one workflow that writes to the repository's issues, so its
 * trust boundary is pinned here rather than left to review.
 *
 * <p>It runs {@code tools/bump-dependencies.sh}, which fetches release metadata from Maven Central,
 * plugins.gradle.org, nodejs.org, services.gradle.org and the GitHub API, and turns the answer into
 * an issue. Two things keep that safe: it starts only on a schedule or by hand, never from a pull
 * request, whose code could otherwise run with the token; and the token can write issues and
 * nothing else. The workflow also reads the script's {@code <- update} marker, so the two are a
 * pair that can drift: the script's wording changing would leave every report saying there is
 * nothing to update.
 */
@DisplayName("The pin report workflow keeps its trust boundary")
class PinReportWorkflowTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();
    private static final String WORKFLOW = ".github/workflows/pin-report.yml";
    private static final String SCRIPT = "tools/bump-dependencies.sh";
    private static final String UPDATE_MARKER = "<- update";

    @Test
    @DisplayName("it starts only on a schedule or by hand")
    void itStartsOnlyOnAScheduleOrByHand() throws IOException {
        Map<String, Object> workflow = workflow();
        // YAML 1.1 reads a bare on: key as the boolean true.
        Object on = workflow.containsKey("on") ? workflow.get("on") : workflow.get(true);
        assertTrue(on instanceof Map, WORKFLOW + " has no on: block");
        assertEquals(Set.of("schedule", "workflow_dispatch"), ((Map<?, ?>) on).keySet(),
            "a pull_request or push trigger would run code from a branch with a token that can write issues");
    }

    @Test
    @DisplayName("its token reads contents and writes issues, nothing more")
    void itsTokenCanWriteIssuesAndNothingElse() throws IOException {
        Map<String, Object> workflow = workflow();
        assertEquals(Map.of("contents", "read"), workflow.get("permissions"),
            "the workflow default must stay contents: read, as for every workflow here (docs/WORKFLOW.md)");
        boolean writesIssues = false;
        for (Map.Entry<String, Map<String, Object>> job : jobs(workflow).entrySet()) {
            Object permissions = job.getValue().get("permissions");
            assertTrue(permissions instanceof Map, "job " + job.getKey() + " must declare its permissions");
            for (Map.Entry<?, ?> grant : ((Map<?, ?>) permissions).entrySet()) {
                boolean allowed = "contents".equals(grant.getKey()) && "read".equals(grant.getValue())
                    || "issues".equals(grant.getKey()) && "write".equals(grant.getValue());
                assertTrue(allowed, "job " + job.getKey() + " grants " + grant.getKey() + ": " + grant.getValue());
                writesIssues |= "issues".equals(grant.getKey());
            }
        }
        assertTrue(writesIssues, "no job can write issues, so the report has nowhere to go");
    }

    @Test
    @DisplayName("it reports what the script reports, by the script's own marker")
    void itReadsTheScriptsUpdateMarker() throws IOException {
        String workflow = read(WORKFLOW);
        assertTrue(workflow.contains(SCRIPT), WORKFLOW + " no longer runs " + SCRIPT);
        assertTrue(workflow.contains(UPDATE_MARKER), WORKFLOW + " no longer looks for '" + UPDATE_MARKER + "'");
        assertTrue(read(SCRIPT).contains(UPDATE_MARKER),
            SCRIPT + " no longer prints '" + UPDATE_MARKER + "', so the weekly report would always be empty");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workflow() throws IOException {
        return new Yaml().load(read(WORKFLOW));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> jobs(Map<String, Object> workflow) {
        Object jobs = workflow.get("jobs");
        assertTrue(jobs instanceof Map && !((Map<?, ?>) jobs).isEmpty(), WORKFLOW + " has no jobs");
        return (Map<String, Map<String, Object>>) jobs;
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
