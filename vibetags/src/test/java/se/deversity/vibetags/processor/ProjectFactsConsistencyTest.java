package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the two headline project counts so the documentation cannot silently drift from the code:
 *
 * <ul>
 *   <li><b>Annotation count</b> — the number of {@code @interface} files in {@code vibetags-annotations}
 *       must equal the "<b>N annotations</b>" figure stated in the README "project facts" line.</li>
 *   <li><b>Platform count</b> — the "<b>N AI platforms</b>" figure in the README must equal the number
 *       of distinct platforms actually enumerated in the README "Supported AI Platforms" list.</li>
 * </ul>
 *
 * <p>The README "At a glance" line is the single source of truth for these numbers; every other doc
 * links back to it. If you add an annotation or a platform, this test fails until the README is
 * updated, which keeps the rest of the docs honest by construction.
 *
 * <p>Skips gracefully if run from a working directory where the repo layout isn't reachable.
 */
class ProjectFactsConsistencyTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    @Test
    void readmeAnnotationCountMatchesTheNumberOfAnnotationInterfaces() throws IOException {
        Path annotationsDir = REPO_ROOT.resolve(
            "vibetags-annotations/src/main/java/se/deversity/vibetags/annotations");
        Path readme = REPO_ROOT.resolve("README.md");
        assumeTrue(Files.isDirectory(annotationsDir) && Files.isRegularFile(readme),
            "repo layout not reachable from the test working directory; skipping");

        long annotationFiles;
        try (Stream<Path> files = Files.list(annotationsDir)) {
            annotationFiles = files
                .filter(p -> p.toString().endsWith(".java"))
                .filter(ProjectFactsConsistencyTest::isAnnotationInterface)
                .count();
        }

        int documented = extractCount(Files.readString(readme, StandardCharsets.UTF_8),
            "\\*\\*(\\d+) annotations\\*\\*", "annotation");
        assertEquals(annotationFiles, documented,
            "README states **" + documented + " annotations** but vibetags-annotations defines "
                + annotationFiles + " @interface types. Update the README 'project facts' line "
                + "(and re-check the docs that reference it).");
    }

    @Test
    void readmePlatformCountMatchesTheSupportedPlatformList() throws IOException {
        Path readme = REPO_ROOT.resolve("README.md");
        assumeTrue(Files.isRegularFile(readme), "README not reachable; skipping");
        String md = Files.readString(readme, StandardCharsets.UTF_8);

        int documented = extractCount(md, "\\*\\*(\\d+) AI platforms\\*\\*", "platform");
        int listed = distinctPlatformsInList(md);
        assertEquals(listed, documented,
            "README states **" + documented + " AI platforms** but the 'Supported AI Platforms' "
                + "list enumerates " + listed + " distinct platforms. Keep the number and the list "
                + "in sync.");
    }

    /**
     * The README's dogfooding claim quotes two line counts as evidence that the scoped-rules index
     * keeps always-loaded context small. They were 46 and 115 when written; the generated block had
     * since drifted to 45 and nothing noticed, because prose does not fail a build.
     *
     * <p>Pinning them here is deliberately a little brittle: adding an annotation to this repo's own
     * source changes the block, and this test then fails with both numbers in the message, so
     * correcting the README is a one-line edit rather than an investigation. A number nobody checks
     * is a number that is eventually wrong, and this one is load-bearing — it is the evidence for
     * the feature's headline benefit.
     */
    @Test
    void readmeDogfoodingLineCountsMatchThisRepositorysOwnFiles() throws IOException {
        Path readme = REPO_ROOT.resolve("README.md");
        Path claudeMd = REPO_ROOT.resolve("CLAUDE.md");
        Path rulesDir = REPO_ROOT.resolve(".claude/rules");
        assumeTrue(Files.isRegularFile(readme) && Files.isRegularFile(claudeMd)
                && Files.isDirectory(rulesDir),
            "repo layout not reachable from the test working directory; skipping");

        String md = Files.readString(readme, StandardCharsets.UTF_8);
        int claimedBlock = extractCount(md,
            "generated block in its own `CLAUDE\\.md` is (\\d+) lines", "generated-block line");
        int claimedDetail = extractCount(md,
            "with (\\d+) lines of per-element detail", "per-element detail line");

        assertEquals(generatedBlockLines(claudeMd), claimedBlock,
            "README says the generated block in CLAUDE.md is " + claimedBlock + " lines, but it is "
                + generatedBlockLines(claudeMd) + " (counted between the VIBETAGS markers, "
                + "inclusive). Update the README sentence.");
        assertEquals(scopedRuleLines(rulesDir), claimedDetail,
            "README says .claude/rules/ holds " + claimedDetail + " lines of per-element detail, but "
                + "it holds " + scopedRuleLines(rulesDir) + ". Update the README sentence.");
    }

    // -----------------------------------------------------------------------

    /** Lines from {@code <!-- VIBETAGS-START -->} to {@code <!-- VIBETAGS-END -->}, inclusive. */
    private static int generatedBlockLines(Path claudeMd) throws IOException {
        List<String> lines = Files.readAllLines(claudeMd, StandardCharsets.UTF_8);
        int start = -1;
        int end = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            // Matched on the comment form, not the bare word: this file also *discusses* the
            // markers in prose, and those mentions come first.
            if (start < 0 && "<!-- VIBETAGS-START -->".equals(line)) start = i;
            if ("<!-- VIBETAGS-END -->".equals(line)) end = i;
        }
        assertTrue(start >= 0 && end > start,
            "CLAUDE.md has no VIBETAGS-START/END block — has this repo stopped dogfooding?");
        return end - start + 1;
    }

    private static int scopedRuleLines(Path rulesDir) throws IOException {
        int total = 0;
        try (Stream<Path> files = Files.list(rulesDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                total += Files.readAllLines(f, StandardCharsets.UTF_8).size();
            }
        }
        return total;
    }

    private static boolean isAnnotationInterface(Path javaFile) {
        try {
            return Files.readString(javaFile, StandardCharsets.UTF_8).contains("public @interface");
        } catch (IOException e) {
            return false;
        }
    }

    private static int extractCount(String md, String regex, String label) {
        Matcher m = Pattern.compile(regex).matcher(md);
        assertTrue(m.find(), "README is missing the canonical '**N " + label + "s**' project-facts figure");
        return Integer.parseInt(m.group(1));
    }

    /**
     * Counts distinct platforms in the README "Supported AI Platforms" section. Bold bullet names
     * are normalised (trailing " IDE"/" Editor"/" CLI" stripped, lower-cased) so a platform listed
     * under two formats — e.g. Cursor and Windsurf, which appear in both "Traditional" and
     * "Granular" — is counted once.
     */
    private static int distinctPlatformsInList(String md) {
        List<String> lines = md.lines().collect(java.util.stream.Collectors.toList());
        Pattern bullet = Pattern.compile("^- \\*\\*([^*]+)\\*\\*");
        Set<String> platforms = new LinkedHashSet<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("### Supported AI Platforms")) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("## ")) {
                break; // next top-level section ends the list
            }
            if (!inSection) {
                continue;
            }
            Matcher m = bullet.matcher(line);
            if (m.find()) {
                String name = m.group(1).trim()
                    .replaceAll(" (IDE|Editor|CLI)$", "")
                    .toLowerCase(java.util.Locale.ROOT);
                platforms.add(name);
            }
        }
        return platforms.size();
    }
}
