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
 * <p>It had already drifted. {@code examples/all-tiers/pom.xml} was checked by
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
        // Feature history and worked examples. "New in v1.0.0" in USAGE.md names the release
        // an annotation shipped in, and proposed-annotations.md records the same fact;
        // RELEASING.md walks its steps with 1.0.0 as the example version; CONCEPT_PLUGIN.md
        // pitches hypothetical coordinates for a plugin that does not exist. None of them
        // track the current release.
        "USAGE.md",
        "docs/proposed-annotations.md",
        "docs/RELEASING.md",
        "docs/CONCEPT_PLUGIN.md",
        // The multi-module examples' own lineage. Each child pom names its example root
        // (groupId se.deversity.vibetags.example) at version 1.0.0, which is the sample
        // project's own version, not a VibeTags coordinate. The roots do carry one (the BOM
        // property), and set-version.sh rewrites the roots.
        "examples/all-tiers/billing/pom.xml",
        "examples/all-tiers/shipping/pom.xml",
        "examples/multimodule-indexed/app/pom.xml",
        "examples/multimodule-indexed/core/pom.xml",
        "examples/multimodule/cli/pom.xml",
        "examples/multimodule/core/pom.xml",
        "examples/multimodule/engine/pom.xml",
        "examples/multimodule/showcase/pom.xml",
        "examples/multimodule/tests/pom.xml",
        // Feature-wave references in code. The showcase demos say which release their five
        // annotations shipped in and carry a CATALOG_VERSION fixture that happens to be
        // 1.0.0; AIKeepInSync's javadoc quotes an illustrative VERSION constant; the
        // NewAnnotations test javadocs name the wave they cover; GranularRenderer groups a
        // formatter block by it; the fingerprint tests pass "1.0.0" as an arbitrary fixture
        // version; BuildVersionParityTest documents examples/basic/build.gradle's own version.
        // None of these are release coordinates.
        "examples/basic/src/main/java/com/example/service/EvidenceBasedShowcase.java",
        "examples/multimodule/showcase/src/main/java/com/example/service/EvidenceBasedShowcase.java",
        "vibetags-annotations/src/main/java/se/deversity/vibetags/annotations/AIKeepInSync.java",
        "vibetags/src/main/java/se/deversity/vibetags/processor/internal/content/platforms/GranularRenderer.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/BuildFingerprintUnitTest.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/BuildVersionParityTest.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/NewAnnotationsV5DefinitionTest.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/NewAnnotationsV5EndToEndTest.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/NewAnnotationsV5ValidationTest.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/NewAnnotationsV6DefinitionTest.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/NewAnnotationsV6EndToEndTest.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/NewAnnotationsV6ValidationTest.java",
        // Malformed-JSON fixtures. Json's javadoc and JsonTest both use "1.2.3" as an example of
        // a string a lenient number parser would wrongly accept, and the test asserts it is
        // rejected. It is a JSON literal, not a coordinate; rewriting it on the release that
        // happens to be numbered 1.2.3 would break the assertion it exists to make.
        "vibetags/src/main/java/se/deversity/vibetags/processor/internal/Json.java",
        "vibetags/src/test/java/se/deversity/vibetags/processor/internal/JsonTest.java",
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
        "vibetags/src/test/java/se/deversity/vibetags/processor/ReleaseScriptCoverageTest.java",
        // A design proposal, dated by the release it was written for. Its worked example shows a
        // manifest stamped "vibetags/1.2.0" because 1.2.0 is the release that shipped the feature;
        // rewriting it on every later release would make the document claim it proposed something
        // that already existed.
        "docs/proposals/transitive-guardrails.md",
        // Explanatory prose about pitest-junit5-plugin's own release history (its actual pin
        // lives in ${pitest-junit5-plugin.version} in the parent pom). Coincidentally equals a
        // VibeTags release number from time to time; it is not a VibeTags coordinate.
        "vibetags/pom.xml");

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
        Pattern statesVersion = wholeCoordinate(version);
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
            if (statesVersion.matcher(text).find() && !scriptText.contains(rel)) {
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

    /**
     * {@code version} stated as a whole coordinate. A bare {@code contains} would light up
     * every {@code 1.0.0-RC3} and {@code 1.0.0-SNAPSHOT} mention the moment the release
     * version becomes {@code 1.0.0}: those state a different version that merely starts with
     * this one. The guards also keep {@code 21.0.0} from matching a search for {@code 1.0.0}.
     */
    private static Pattern wholeCoordinate(String version) {
        return Pattern.compile("(?<![0-9.])" + Pattern.quote(version) + "(?![0-9A-Za-z.-])");
    }

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
