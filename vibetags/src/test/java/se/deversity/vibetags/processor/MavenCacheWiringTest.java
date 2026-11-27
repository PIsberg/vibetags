package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CI jobs that resolve from Maven Central restore their local repository through one shared
 * action with restore-keys (#1041).
 *
 * <p>{@code setup-java}'s {@code cache: maven} is keyed on the exact pom hash and has no fallback,
 * so every pom change, a Dependabot bump among them, started about thirty jobs with an empty
 * repository at once, and Central answered some of them 429. The shared action restores the newest
 * cache for the OS when the exact key misses, so a bump downloads only what changed. It leaves out
 * {@code se/deversity/vibetags}: every job installs this repository's own artifacts, and a restored
 * copy of an older build of them is the one thing a cache must never hand back.
 */
@DisplayName("CI restores the Maven repository through the shared cache action")
class MavenCacheWiringTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();
    private static final String ACTION = ".github/actions/maven-cache/action.yml";
    private static final String STEP = "uses: $/.github/actions/maven-cache";
    private static final List<String> WORKFLOWS = List.of(".github/workflows/build.yml", ".github/workflows/fuzz.yml");

    @Test
    @DisplayName("the shared action falls back to the newest cache and never restores this repository's own artifacts")
    void theActionHasRestoreKeysAndExcludesOurOwnArtifacts() throws IOException {
        String action = read(ACTION);
        assertTrue(action.contains("actions/cache@"), ACTION + " no longer uses actions/cache");
        assertTrue(action.contains("restore-keys:"),
            "without restore-keys a pom change starts every job cold against Central again (#1041)");
        assertTrue(action.contains("!~/.m2/repository/se/deversity/vibetags"),
            "the cache must not restore an older build of this repository's own artifacts");
    }

    @Test
    @DisplayName("no job in build.yml or fuzz.yml uses setup-java's cache: maven, and each that resolved through it uses the action")
    void theJobsUseTheSharedAction() throws IOException {
        for (String workflow : WORKFLOWS) {
            String text = read(workflow);
            assertTrue(!text.contains("cache: maven"),
                workflow + " still uses setup-java's cache: maven, which has no restore-keys (#1041)");
            assertTrue(text.contains(STEP), workflow + " has no step using the shared Maven cache action");
        }
        assertEquals(10, count(read(WORKFLOWS.get(0)), STEP),
            "build.yml's ten jobs that resolve from Central each restore the repository through the action");
    }

    private static int count(String text, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
