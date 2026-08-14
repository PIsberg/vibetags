package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import se.deversity.vibetags.processor.internal.ServiceRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *   <li><b>Output counts</b> — the "<b>N config files</b>" and "<b>N scoped-rule directories</b>"
 *       figures must equal what {@code ServiceRegistry} actually declares. These are a different
 *       count from the platform figure and must not be conflated with it: a platform is a tool,
 *       and one tool routinely owns several files. The README claimed "37 config formats" for a
 *       long time — the platform number wearing the file number's label, understating the real
 *       figure by a third, and nothing disagreed because nothing was checking.</li>
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

    /**
     * Docs that state a version's own historical scope, which must keep their original numbers.
     * A changelog entry saying a release "extends the set to 39 annotations" was true when written
     * and stays true; rewriting it would claim that release shipped something it did not.
     */
    private static final Set<String> HISTORICAL_DOCS = Set.of(
        "docs/CHANGELOG.md", "docs/proposed-annotations.md", "docs/PLAN.md",
        "docs/ARCHITECTURE.md",       // its "Last updated" note quotes the counts it removed
        "USAGE.md");                  // "v0.9.9 extends the set to 39" is a statement about v0.9.9

    /**
     * Prose that claims to state the <em>whole</em> set: "all N annotations", "the N annotations",
     * or "N annotation ... in total".
     *
     * <p>Deliberately not every "N annotations". A sentence like "7 annotations have zero
     * real-world traction" is a finding about a subset and is true; only a claim about the total
     * can go stale. Matching both would force whole documents onto an exemption list, which would
     * then hide the next real drift inside them.
     *
     * <p>Version strings are excluded by the leading guard: the 8 in "v0.9.8 annotations" is not a
     * count.
     */
    private static final Pattern PROSE_COUNT = Pattern.compile(
        "(?:all|the)\\s+(\\d+)\\s+(?:Java\\s+|VibeTags\\s+)?annotations?\\b"
            + "|(?<![\\d.])(\\d+)\\s+(?:Java\\s+|VibeTags\\s+)?annotation\\b[^.\\n]*?\\bin total\\b");

    /**
     * Every {@code .md} file under {@code root}, tolerating a file that disappears mid-walk.
     *
     * <p>{@link Files#walk} throws {@link java.io.UncheckedIOException} out of its iterator when a
     * path it has listed can no longer be stat'ed, which fails the test with a
     * {@code NoSuchFileException} for a file nobody cares about. The repository is a live directory
     * while the suite runs: the JVM writes {@code .attach_pid<n>} into the working directory when an
     * agent self-attaches — Mockito's inline mock maker does exactly that, from tests running in
     * parallel with this one — and deletes it again immediately. On Linux CI the race is reliable
     * enough to fail every job; on Windows it is invisible, which is why it reached CI unnoticed.
     *
     * <p>Skipping unreadable entries is right rather than merely convenient: this test is about
     * what the committed documents claim, and a path that vanished during the walk is not one of
     * them.
     */
    private static List<Path> markdownFilesUnder(Path root) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".md")) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    @Test
    void noDocRestatesADifferentAnnotationCount() throws IOException {
        Path annotationsDir = REPO_ROOT.resolve(
            "vibetags-annotations/src/main/java/se/deversity/vibetags/annotations");
        assumeTrue(Files.isDirectory(annotationsDir), "annotations module not reachable; skipping");
        long actual;
        try (Stream<Path> files = Files.list(annotationsDir)) {
            actual = files
                .filter(p -> p.toString().endsWith(".java"))
                .filter(ProjectFactsConsistencyTest::isAnnotationInterface)
                .count();
        }

        List<String> drifted = new ArrayList<>();
        for (Path doc : markdownFilesUnder(REPO_ROOT)) {
            String rel = REPO_ROOT.relativize(doc).toString().replace('\\', '/');
            if (rel.contains("/target/") || rel.contains("/node_modules/")
                || rel.startsWith("target/") || HISTORICAL_DOCS.contains(rel)) {
                continue;
            }
            String text;
            try {
                text = Files.readString(doc, StandardCharsets.UTF_8);
            } catch (IOException notText) {
                continue;
            }
            Matcher m = PROSE_COUNT.matcher(text);
            while (m.find()) {
                String captured = m.group(1) != null ? m.group(1) : m.group(2);
                int stated = Integer.parseInt(captured);
                if (stated != actual) {
                    int line = (int) text.substring(0, m.start()).chars()
                        .filter(c -> c == '\n').count() + 1;
                    drifted.add(rel + ":" + line + " says \"" + m.group() + "\"");
                }
            }
        }

        assertTrue(drifted.isEmpty(),
            "There are " + actual + " annotations, but these docs state a different count. The "
                + "README project-facts line is the single source of truth and other docs are "
                + "meant to link to it rather than restate it — restating is how \"all 15 Java "
                + "annotations\" survived six lines below the pinned figure:\n  "
                + String.join("\n  ", drifted));
    }

    @Test
    void readmeOutputCountsMatchWhatTheServiceRegistryDeclares() throws IOException {
        Path readme = REPO_ROOT.resolve("README.md");
        if (!Files.isRegularFile(readme)) {
            return; // repo layout not reachable; the other tests document this skip
        }
        String md = Files.readString(readme, StandardCharsets.UTF_8);

        Map<String, Path> services = ServiceRegistry.buildServiceFileMap(Paths.get("."));
        // A scoped-rules entry is a directory of per-element rule files; everything else is a
        // single config file. The file name is the only thing that distinguishes them without
        // touching the disk, and rule directories are the ones with no extension.
        long directories = services.values().stream()
            .map(Path::getFileName)
            .filter(java.util.Objects::nonNull)
            .filter(name -> !name.toString().contains("."))
            .count();
        long files = services.size() - directories;

        assertEquals(files,
            extractCount(md, "\\*\\*(\\d+) config files\\*\\*", "config file"),
            "The README's config-file count disagrees with ServiceRegistry. This is the number a "
                + "reader uses to judge whether VibeTags is worth adopting, so it has to be the "
                + "number the code actually writes.");
        assertEquals(directories,
            extractCount(md, "\\*\\*(\\d+) scoped-rule directories\\*\\*", "scoped-rule directory"),
            "The README's scoped-rule-directory count disagrees with ServiceRegistry.");
    }

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
