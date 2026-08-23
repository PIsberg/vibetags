package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks the shell commands the documentation tells a reader to run, rather than only the prose
 * around them.
 *
 * <p>{@link DocumentationLinksTest} is thorough about Markdown links and anchors, but a fenced code
 * block is opaque to it, so the instructions a new contributor actually types were the least
 * verified text in the repository. What lived there when this test was written:
 *
 * <ul>
 *   <li>{@code cd ../example} in the README's Maven and Gradle build sections, in
 *       {@code docs/CONTRIBUTING.md}, and three times in {@code examples/basic/README.md}. That
 *       directory is {@code examples/basic/} and has been for a long time.</li>
 *   <li>{@code cd ../vibetags-annotations} in {@code examples/basic/README.md}, which resolves to
 *       {@code examples/vibetags-annotations} from the directory that document lives in.</li>
 *   <li>A README build sequence that installed {@code vibetags-annotations} and {@code vibetags}
 *       and then compiled {@code examples/basic}, which imports a {@code vibetags-bom} that was
 *       never installed. It failed only on a machine whose local repository did not already hold
 *       the BOM, which is every machine except the maintainer's.</li>
 * </ul>
 *
 * <p>None of these fail a build, which is why they accumulated: the person best placed to notice is
 * a first-time contributor, and they assume the mistake is theirs.
 *
 * <p>Scope is deliberately the two checks that are cheap and deterministic. Actually executing the
 * documented sequence against an empty local repository would catch a further class of defect (the
 * missing BOM was a real directory and failed only at dependency resolution), but that costs a full
 * clean build and belongs in a scheduled job rather than here.
 */
@DisplayName("Documented shell commands resolve")
class DocumentedCommandsTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final Set<String> SKIP_DIRS =
        Set.of(".git", "target", "node_modules", "build", ".mvn", ".gradle");

    @Test
    @DisplayName("every cd in a documented command reaches a directory that exists")
    void everyDocumentedCdTargetResolves() throws IOException {
        assumeTrue(Files.isDirectory(REPO_ROOT.resolve("docs")),
            "repo layout not reachable from " + REPO_ROOT + "; skipping");

        List<Path> docs = markdownFiles();
        assumeTrue(docs.size() > 10, "found only " + docs.size() + " markdown files; layout wrong");

        List<String> broken = new ArrayList<>();
        for (Path doc : docs) {
            Path docDir = doc.getParent();
            for (List<String> block : shellBlocks(doc)) {
                // A block is read top to bottom, so a later cd may build on an earlier one.
                Path cursor = REPO_ROOT;
                for (String target : block) {
                    if (isPlaceholder(target)) {
                        continue;
                    }
                    Path resolved = firstThatExists(target, cursor, docDir);
                    if (resolved == null) {
                        broken.add(rel(doc) + " -> cd " + target);
                    } else {
                        cursor = resolved;
                    }
                }
            }
        }

        assertTrue(broken.isEmpty(),
            "Documented commands cd into directories that do not exist (" + broken.size()
                + " of them). A reader following these instructions hits the error, not us:\n  "
                + String.join("\n  ", broken));
    }

    /**
     * The README's Maven build sequence has to name every module {@code CLAUDE.md} declares, in the
     * order it declares them.
     *
     * <p>This is the check that would have caught the missing {@code vibetags-bom} step. The
     * directory existed, so no amount of path linting would have said anything; the only thing that
     * disagreed was {@code CLAUDE.md}, which had the order right all along and was never compared
     * against.
     */
    @Test
    @DisplayName("the README's Maven build sequence matches the build order CLAUDE.md declares")
    void readmeMavenBuildFollowsTheDeclaredBuildOrder() throws IOException {
        Path readme = REPO_ROOT.resolve("README.md");
        Path claudeMd = REPO_ROOT.resolve("CLAUDE.md");
        assumeTrue(Files.isRegularFile(readme) && Files.isRegularFile(claudeMd),
            "repo layout not reachable; skipping");

        List<String> declared = declaredBuildOrder(claudeMd);
        assertTrue(declared.size() >= 2,
            "Could not read a build order out of CLAUDE.md. This test expects its Build order line "
                + "to name the modules in backticks; if that line moved, update this test rather "
                + "than deleting it.");

        List<String> documented = new ArrayList<>();
        for (String target : mavenBuildBlock(readme)) {
            Path name = Paths.get(target).getFileName();
            if (name != null) {
                documented.add(name.toString());
            }
        }
        assertTrue(!documented.isEmpty(),
            "Could not find the README Maven build block. It is located by the Build Everything "
                + "with Maven heading; if that heading changed, update this test rather than "
                + "deleting it.");

        int next = 0;
        List<String> missing = new ArrayList<>();
        for (String module : declared) {
            int at = documented.subList(next, documented.size()).indexOf(module);
            if (at < 0) {
                missing.add(module);
            } else {
                next += at + 1;
            }
        }

        assertTrue(missing.isEmpty(),
            "CLAUDE.md declares the build order " + declared + ", but the README Maven build "
                + "section runs " + documented + ", which omits " + missing + " or runs it out of "
                + "order. Following the README then fails on any machine whose local repository "
                + "does not already hold the missing artifact.");
    }

    /**
     * The modules named in the {@code CLAUDE.md} build-order line, in order. Trailing prose such as
     * the word consumers is not in backticks and is therefore not picked up.
     */
    private static List<String> declaredBuildOrder(Path claudeMd) throws IOException {
        for (String line : Files.readAllLines(claudeMd, StandardCharsets.UTF_8)) {
            int at = line.indexOf("Build order:");
            if (at < 0) {
                continue;
            }
            List<String> modules = new ArrayList<>();
            for (String token : backtickedTokens(line.substring(at))) {
                if (!token.isBlank()) {
                    modules.add(token);
                }
            }
            return modules;
        }
        return List.of();
    }

    /** The backtick-quoted spans of a line, in order. */
    private static List<String> backtickedTokens(String line) {
        final char tick = 96;
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (true) {
            int open = line.indexOf(tick, i);
            if (open < 0) {
                return tokens;
            }
            int close = line.indexOf(tick, open + 1);
            if (close < 0) {
                return tokens;
            }
            tokens.add(line.substring(open + 1, close));
            i = close + 1;
        }
    }

    /** The cd targets of the fenced block under the Build Everything with Maven heading. */
    private static List<String> mavenBuildBlock(Path readme) throws IOException {
        List<String> lines = Files.readAllLines(readme, StandardCharsets.UTF_8);
        int heading = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("#") && line.contains("Build Everything with Maven")) {
                heading = i;
                break;
            }
        }
        if (heading < 0) {
            return List.of();
        }
        List<String> targets = new ArrayList<>();
        boolean inFence = false;
        for (int i = heading + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isFence(line)) {
                if (inFence) {
                    break;
                }
                inFence = true;
                continue;
            }
            if (inFence) {
                targets.addAll(cdTargetsIn(line));
            }
        }
        return targets;
    }

    /** Every fenced block in {@code doc}, as its list of cd targets in source order. */
    private static List<List<String>> shellBlocks(Path doc) throws IOException {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            if (isFence(line)) {
                if (current == null) {
                    current = new ArrayList<>();
                } else {
                    blocks.add(current);
                    current = null;
                }
                continue;
            }
            if (current != null) {
                current.addAll(cdTargetsIn(line));
            }
        }
        if (current != null) {
            blocks.add(current);
        }
        return blocks;
    }

    private static boolean isFence(String line) {
        final char tick = 96;
        String stripped = line.stripLeading();
        return stripped.length() >= 3
            && stripped.charAt(0) == tick && stripped.charAt(1) == tick && stripped.charAt(2) == tick;
    }

    /**
     * The cd targets in one shell line, honouring the and-and, or-or and semicolon separators so a
     * chained cd is seen the same way as a bare cd on its own line.
     */
    private static List<String> cdTargetsIn(String line) {
        List<String> targets = new ArrayList<>();
        if (line.stripLeading().startsWith("#")) {
            return targets;
        }
        for (String part : splitOnSeparators(line)) {
            String command = part.strip();
            if (!command.startsWith("cd ")) {
                continue;
            }
            String target = command.substring(3).strip();
            int space = target.indexOf(' ');
            if (space >= 0) {
                target = target.substring(0, space);
            }
            if (!target.isEmpty() && !"-".equals(target)) {
                targets.add(target);
            }
        }
        return targets;
    }

    /** Splits on the shell separators without needing an escaped regex. */
    private static List<String> splitOnSeparators(String line) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean doubled = i + 1 < line.length() && line.charAt(i + 1) == c;
            int width = 0;
            if (c == ';') {
                width = 1;
            } else if (doubled && (c == '&' || c == '|')) {
                width = 2;
            }
            if (width > 0) {
                parts.add(line.substring(start, i));
                i += width - 1;
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts;
    }

    /**
     * A target the reader is meant to substitute, expand or glob, rather than a real directory in
     * this repository. Checking these would report failures nobody can fix.
     */
    private static boolean isPlaceholder(String target) {
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '<' || c == '>' || c == 36 || c == '{' || c == '}'
                || c == '~' || c == '*' || c == '"' || c == 39 || c == 96) {
                return true;
            }
        }
        // Absolute paths describe the reader's machine, not this repository.
        return target.startsWith("/") || (target.length() > 1 && target.charAt(1) == ':');
    }

    /**
     * Resolves {@code target} against the running directory of the block, the directory the
     * document lives in, and the repository root.
     *
     * <p>All three bases are legitimate and the docs use all three: a root document positions the
     * reader at the repository root, a skill file assumes the same, and a README inside
     * {@code examples/} assumes the reader is standing in that example. Accepting any of them keeps
     * the check free of false positives while still catching a target that resolves under none,
     * which is what every real defect here turned out to be.
     */
    private static Path firstThatExists(String target, Path cursor, Path docDir) {
        for (Path base : new Path[] {cursor, docDir, REPO_ROOT}) {
            if (base == null) {
                continue;
            }
            Path candidate = base.resolve(target).normalize();
            if (candidate.startsWith(REPO_ROOT) && Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> markdownFiles() throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(REPO_ROOT, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                return name != null && SKIP_DIRS.contains(name.toString())
                    ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
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

    private static String rel(Path doc) {
        return REPO_ROOT.relativize(doc).toString().replace(File.separatorChar, '/');
    }
}
