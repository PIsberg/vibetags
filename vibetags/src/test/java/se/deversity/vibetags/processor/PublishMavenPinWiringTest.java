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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code publish.yml} deploys on the Maven that the {@code central-bundle} job in {@code build.yml}
 * tests.
 *
 * <p>Central validates a bundle only after {@code publish.yml} uploads it, which is after the
 * release is tagged. On 2026-10-08 the runner image's Maven had moved to 3.10, whose resolver
 * leaves files that central-publishing-maven-plugin 0.11.0 zips into the bundle, and Central
 * rejected the release with nothing published (#945). {@code publish.yml} now installs a pinned
 * Maven, and the {@code central-bundle} job builds every published module's bundle with it on each
 * pull request.
 *
 * <p>That job says something about a release only while both sides run the same Maven. Had
 * {@code publish.yml} lost its install step, it would deploy on the image's Maven again while the
 * job kept testing the pinned one and stayed green. These tests fail instead.
 */
@DisplayName("publish.yml deploys on the Maven that build.yml's bundle check tests")
class PublishMavenPinWiringTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final String INSTALL = "Install the pinned Maven";
    private static final String REFUSE = "Refuse to deploy on any Maven but the pinned one";

    /** A {@code deploy-to-central.sh} call, not a comment that mentions the script. */
    private static final Pattern DEPLOY_CALL =
        Pattern.compile("(?m)^\\s*bash \\.github/scripts/deploy-to-central\\.sh ");

    @Test
    @DisplayName("publish.yml pins a Maven version and the SHA-512 of its distribution")
    void publishPinsAVersionAndItsDigest() throws IOException {
        String publish = read(".github/workflows/publish.yml");

        assertTrue(Pattern.compile("(?m)^\\s+MAVEN_VERSION: '\\d+\\.\\d+\\.\\d+'\\s*$").matcher(publish).find(),
            "publish.yml no longer pins MAVEN_VERSION: '<x.y.z>'. Without it every deploy runs on "
                + "whatever Maven the runner image ships, which is how a release failed on Maven 3.10 (#945).");
        assertTrue(Pattern.compile("(?m)^\\s+MAVEN_SHA512: '[0-9a-f]{128}'\\s*$").matcher(publish).find(),
            "publish.yml must pin MAVEN_SHA512 as the 128 hex digits of the distribution's SHA-512, "
                + "or the install step runs whatever the download returns.");
    }

    @Test
    @DisplayName("publish.yml installs that Maven and refuses any other before its first deploy")
    void publishInstallsAndChecksBeforeItsFirstDeploy() throws IOException {
        String publish = read(".github/workflows/publish.yml");
        int install = publish.indexOf("- name: " + INSTALL);
        int refuse = publish.indexOf("- name: " + REFUSE);
        Matcher deploy = DEPLOY_CALL.matcher(publish);

        assertTrue(install >= 0, "publish.yml has no '" + INSTALL + "' step.");
        assertTrue(refuse > install,
            "publish.yml must check the Maven on PATH after installing it, in a step named '" + REFUSE + "'.");
        assertTrue(deploy.find() && deploy.start() > refuse,
            "the version check must come before the first deploy-to-central.sh call; a deploy ahead "
                + "of it can run on the image's Maven.");
        String check = runBlock(step(publish, REFUSE, "publish.yml"));
        assertTrue(check.contains("mvn --version") && check.contains("${MAVEN_VERSION}") && check.contains("exit 1"),
            "the version check must compare `mvn --version` with ${MAVEN_VERSION} and fail the job "
                + "when they differ:\n" + check);
    }

    @Test
    @DisplayName("the central-bundle job installs Maven with the same block, from publish.yml's pin")
    void bundleJobInstallsThePublishMaven() throws IOException {
        String publish = read(".github/workflows/publish.yml");
        String build = read(".github/workflows/build.yml");
        String job = job(build, "central-bundle");

        assertEquals(runBlock(step(publish, INSTALL, "publish.yml")),
            runBlock(step(job, INSTALL, "build.yml's central-bundle job")),
            "the central-bundle job must install Maven exactly as publish.yml does. A different "
                + "install tests a toolchain no release runs on.");
        assertTrue(job.contains(".github/workflows/publish.yml"),
            "the central-bundle job must read MAVEN_VERSION and MAVEN_SHA512 from publish.yml.");
        assertFalse(Pattern.compile("(?m)^\\s+MAVEN_(VERSION|SHA512):").matcher(build).find(),
            "build.yml pins a Maven of its own. Take it from publish.yml, so the two cannot disagree.");
        assertTrue(job.contains("bash .github/scripts/build-central-bundles.sh"),
            "the central-bundle job no longer builds and checks the bundles.");
    }

    /** The job {@code name} in a workflow, up to the next job. */
    private static String job(String workflow, String name) {
        Matcher start = Pattern.compile("(?m)^  " + Pattern.quote(name) + ":\\s*$").matcher(workflow);
        assertTrue(start.find(), "build.yml has no '" + name + "' job.");
        Matcher next = Pattern.compile("(?m)^  [A-Za-z0-9_-]+:\\s*$").matcher(workflow);
        int end = next.find(start.end()) ? next.start() : workflow.length();
        return workflow.substring(start.start(), end);
    }

    /** The step named {@code name}: its first line and everything up to the next step. */
    private static String step(String text, String name, String where) {
        int at = text.indexOf("- name: " + name);
        assertTrue(at >= 0, where + " has no '" + name + "' step.");
        int next = text.indexOf("\n      - ", at + 1);
        return text.substring(at, next < 0 ? text.length() : next);
    }

    /**
     * The body of a step's {@code run: |} block. It ends at the first line indented no deeper than
     * {@code run:}, which leaves out a comment that introduces the step after it.
     */
    private static String runBlock(String step) {
        StringBuilder body = new StringBuilder();
        int runIndent = -1;
        for (String line : step.split("\\R", -1)) {
            if (runIndent < 0) {
                if ("run: |".equals(line.trim())) {
                    runIndent = line.indexOf("run:");
                }
                continue;
            }
            if (!line.isBlank() && line.length() - line.stripLeading().length() <= runIndent) {
                break;
            }
            body.append(line).append('\n');
        }
        assertTrue(runIndent >= 0, "the step has no run: | block:\n" + step);
        return body.toString().stripTrailing();
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
