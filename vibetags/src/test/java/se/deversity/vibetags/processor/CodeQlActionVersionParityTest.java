package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code github/codeql-action/*} reference in the workflows must pin one commit.
 *
 * <p>{@code init}, {@code analyze} and {@code upload-sarif} are three entry points into a single
 * action repository, and CodeQL will not run them at different versions: {@code init} stamps its
 * config file with its own version, and {@code analyze}'s post step refuses a config it did not
 * write — {@code "Loaded a configuration file for version '4.36.2', but running version '4.37.5'"}.
 *
 * <p>Dependabot bills each path as a separate dependency, so before the {@code codeql-action} group
 * in {@code .github/dependabot.yml} it opened one PR per entry point: #367 moved {@code init} and
 * #368 moved {@code analyze}, each leaving the other half of the pair behind. Both failed CI alone,
 * so neither could merge and the bump deadlocked. The group is the fix; this test is what keeps it
 * fixed, because a group is a convention on Dependabot's side and a hand-edit or a security update
 * can still land half a bump. The failure it prevents is otherwise a post-action error message that
 * names two versions and no file.
 */
@DisplayName("All codeql-action references pin the same commit")
class CodeQlActionVersionParityTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /** Matches {@code uses: github/codeql-action/<entry>@<sha> # <version>}. */
    private static final Pattern REFERENCE = Pattern.compile(
        "github/codeql-action/(\\S+?)@([0-9a-f]{40})\\s*#\\s*(\\S+)");

    @Test
    void everyCodeQlActionReferencePinsTheSameCommitAndVersion() throws IOException {
        Path workflows = REPO_ROOT.resolve(".github/workflows");
        // Deliberately not assumeTrue: a skipped parity check reads exactly like a passed one,
        // and this test exists because a silent half-bump already reached main once.
        assertTrue(Files.isDirectory(workflows), "workflows not found at " + workflows);

        Map<String, String> shaByRef = new TreeMap<>();
        Map<String, String> versionByRef = new TreeMap<>();
        try (Stream<Path> files = Files.list(workflows)) {
            List<Path> yml = files.filter(p -> p.toString().endsWith(".yml")
                                            || p.toString().endsWith(".yaml")).sorted().toList();
            for (Path file : yml) {
                Matcher matcher = REFERENCE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    String where = file.getFileName() + ":" + matcher.group(1);
                    shaByRef.put(where, matcher.group(2));
                    versionByRef.put(where, matcher.group(3));
                }
            }
        }

        assertTrue(shaByRef.size() >= 2,
            "expected several codeql-action references to compare, found " + shaByRef.keySet()
                + " — if the workflows stopped using CodeQL, delete this test rather than let it pass vacuously");

        assertEquals(1, new TreeSet<>(shaByRef.values()).size(),
            "codeql-action references pin different commits, which fails the analyze post step at "
                + "runtime with a version-mismatch error: " + shaByRef);

        assertEquals(1, new TreeSet<>(versionByRef.values()).size(),
            "codeql-action version comments disagree, so one of them is lying about the commit "
                + "beside it: " + versionByRef);
    }
}
