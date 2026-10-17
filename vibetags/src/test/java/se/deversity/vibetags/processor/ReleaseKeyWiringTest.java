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
 * The key {@code publish.yml} signs with is the key {@code build.yml}'s {@code release-key} job
 * looks up on the keyservers (#956).
 *
 * <p>Central rejects a bundle whose signatures it cannot verify against a key on a keyserver, and
 * pull requests sign with a throwaway key, so only the {@code release-key} job says anything about
 * the release key before a tag. It says something only while both sides name the same key: without
 * the guard in {@code publish.yml}'s import step, a replaced {@code GPG_PRIVATE_KEY} would sign
 * releases while the job kept checking the old fingerprint and stayed green.
 */
@DisplayName("publish.yml signs only with the key build.yml's release-key job watches")
class ReleaseKeyWiringTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    @Test
    @DisplayName("publish.yml pins the release key's fingerprint and refuses any other key")
    void publishPinsTheFingerprintAndRefusesOtherKeys() throws IOException {
        String publish = read(".github/workflows/publish.yml");
        assertTrue(Pattern.compile("(?m)^\\s+RELEASE_KEY_FINGERPRINT: '[0-9A-F]{40}'\\s*(#.*)?$").matcher(publish).find(),
            "publish.yml must pin RELEASE_KEY_FINGERPRINT: '<40 upper-case hex digits>', the line "
                + "check-release-key.sh reads.");

        int start = publish.indexOf("- name: Import GPG key");
        assertTrue(start >= 0, "publish.yml has no 'Import GPG key' step.");
        int end = publish.indexOf("\n      - ", start + 1);
        String step = publish.substring(start, end < 0 ? publish.length() : end);
        assertTrue(step.contains("--list-secret-keys") && step.contains("$RELEASE_KEY_FINGERPRINT")
                && step.contains("exit 1"),
            "the import step must compare the imported secret key with $RELEASE_KEY_FINGERPRINT and "
                + "fail when they differ:\n" + step);
    }

    @Test
    @DisplayName("build.yml's release-key job tests the checker, then runs it")
    void buildRunsTheCheckerAndItsTests() throws IOException {
        String build = read(".github/workflows/build.yml");
        Matcher job = Pattern.compile("(?m)^  release-key:\\s*$").matcher(build);
        assertTrue(job.find(), "build.yml has no release-key job.");
        Matcher next = Pattern.compile("(?m)^  [A-Za-z0-9_-]+:\\s*$").matcher(build);
        String body = build.substring(job.start(), next.find(job.end()) ? next.start() : build.length());

        int test = body.indexOf("bash .github/scripts/check-release-key.test.sh");
        int check = body.indexOf("bash .github/scripts/check-release-key.sh");
        assertTrue(test >= 0 && check > test,
            "the release-key job must run check-release-key.test.sh and then check-release-key.sh:\n" + body);
        assertTrue(!body.contains("continue-on-error") && !body.contains("if:"),
            "the release-key job must not be skippable or allowed to fail; a skipped key check reads "
                + "as a passed one.");
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
