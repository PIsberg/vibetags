package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code scripts/set-version.sh} must rewrite every file that states the release version.
 *
 * <p>The version lives once, in {@code <revision>}, and everything that can inherit it does. What
 * is left is the set of files that cannot: the Gradle builds, the standalone example poms a user
 * is meant to copy, and the install snippets in the documentation. That set grows every time an
 * example or a doc is added, and the script's list is a hand-maintained copy of it.
 *
 * <p>It had already drifted. {@code example-all-tiers/pom.xml} was checked by
 * {@link BuildVersionParityTest} but never written by the script, so the documented release
 * procedure failed partway through. {@code README.md} and the usage skill were checked by nothing
 * at all — a GA cut with that gap would have shipped eleven install snippets telling every new
 * user to depend on a release candidate.
 *
 * <p>So the list is derived here instead of trusted: any tracked file that mentions the current
 * version and is not explicitly recorded as historical must appear in the script.
 */
@DisplayName("The release script rewrites every version it needs to")
class ReleaseScriptCoverageTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final Set<String> SKIP_DIRS =
        Set.of(".git", "target", "node_modules", "build", ".gradle", "results");

    /**
     * Files that state a version on purpose and must survive a bump untouched.
     *
     * <p>A blanket search-and-replace across the repository would corrupt every one of these: a
     * changelog rewritten to the new version claims this release shipped every past change, and a
     * benchmark's provenance rewritten to the new version claims measurements that were never
     * taken.
     */
    private static final Set<String> HISTORICAL = Set.of(
        "docs/CHANGELOG.md",          // a record of what each version did
        "load-tests/README.md",       // "since 1.0.0-RCn" is provenance, not a coordinate
        "load-tests/results/README.md",
        // The benchmark plotters. Two default --version to the last release that actually has
        // measurements under load-tests/results/; bumping that on release would aim them at a
        // directory nobody has generated yet. The other two name versions inside a comment
        // explaining the sort order (0.9.7 < 1.0.0-RC1 < 1.0.0-RC9 < 1.0.0) — the whole point of
        // that example is the relationship between the versions, which a rewrite destroys.
        "tools/plot-release-comparison.py",
        "tools/plot-processor-tax.py",
        "tools/plot-cache-hit.py",
        "tools/plot-results.py",
        // This file, which quotes the same sort-order example a few lines above while explaining
        // why those tools are exempt. It flagged itself the moment it was committed, which is the
        // scan working: it reads `git ls-files`, so a file becomes visible when it becomes real.
        "vibetags/src/test/java/se/deversity/vibetags/processor/ReleaseScriptCoverageTest.java");

    /** Build outputs and local scratch that are not part of the release. */
    private static final Set<String> IGNORED_SUFFIXES =
        Set.of(".flattened-pom.xml", "dependency-reduced-pom.xml");

    @Test
    @DisplayName("no tracked file states the version without the script knowing about it")
    void everyVersionCarryingFileIsInTheScript() throws IOException, InterruptedException {
        Path script = REPO_ROOT.resolve("scripts/set-version.sh");
        assumeTrue(Files.isRegularFile(script), "set-version.sh not reachable; skipping");

        String version = revision();
        assumeTrue(version != null && !version.isBlank(), "could not read <revision>; skipping");

        Set<String> tracked = trackedFiles();
        assumeTrue(!tracked.isEmpty(), "git did not list any tracked files; skipping");

        String scriptText = Files.readString(script, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();

        for (String rel : tracked) {
            if (HISTORICAL.contains(rel) || isIgnored(rel) || isInSkippedDirectory(rel)) {
                continue;
            }
            Path file = REPO_ROOT.resolve(rel);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | java.io.UncheckedIOException notText) {
                continue; // binary or unreadable; it cannot carry a version string
            }
            if (text.contains(version) && !scriptText.contains(rel)) {
                missing.add(rel + " (states " + version + ")");
            }
        }

        assertTrue(missing.isEmpty(),
            "These files state the release version but scripts/set-version.sh does not rewrite "
                + "them, so the next release leaves them pointing at " + version + ". Add them to "
                + "the script, or record them in HISTORICAL here with the reason they must not "
                + "change:\n  " + String.join("\n  ", missing));
    }

    // -----------------------------------------------------------------------

    private static boolean isIgnored(String rel) {
        return IGNORED_SUFFIXES.stream().anyMatch(rel::endsWith);
    }

    private static boolean isInSkippedDirectory(String rel) {
        for (String part : rel.split("/")) {
            if (SKIP_DIRS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** {@code <revision>} from the parent pom: the one place the version is declared. */
    private static String revision() throws IOException {
        Path parent = REPO_ROOT.resolve("vibetags-parent/pom.xml");
        if (!Files.isRegularFile(parent)) {
            return null;
        }
        Matcher m = Pattern.compile("<revision>([^<]+)</revision>")
            .matcher(Files.readString(parent, StandardCharsets.UTF_8));
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Tracked files only. Untracked build output routinely contains the version — the flattened
     * poms and every surefire report — and none of it is released.
     */
    private static Set<String> trackedFiles() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "ls-files");
        pb.directory(REPO_ROOT.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Set<String> files = new LinkedHashSet<>();
        try (Stream<String> lines = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).lines()) {
            lines.map(String::trim).filter(s -> !s.isEmpty()).forEach(files::add);
        }
        p.waitFor();
        return files;
    }
}
