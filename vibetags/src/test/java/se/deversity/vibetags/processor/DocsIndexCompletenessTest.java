package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every reference document must be reachable from the repository's entry documents.
 *
 * <p>{@link DocumentationLinksTest} proves that no link points at a missing file. This test
 * proves the inverse: no reference file exists that nothing points at. An orphaned document is
 * worse than a missing one, because it keeps getting edited, keeps asserting things, and no
 * reader can find it; the routing index in {@code CLAUDE.md} only works as an index if it is
 * complete, and completeness is exactly the property a human editor silently loses when adding
 * a file.
 *
 * <p>Scope: every {@code *.md} under {@code docs/}, plus {@code SPEC.md} at the root. Module
 * READMEs (example projects, load-tests, the action) travel with their module and are excluded;
 * {@code .claude/rules/} files are generated and routed by glob, not by Markdown links.
 * Reachability starts from the documents a reader or agent actually lands in: {@code README.md},
 * {@code CLAUDE.md}, {@code AGENTS.md}, {@code GEMINI.md}, {@code USAGE.md}.
 */
@DisplayName("Documentation index completeness")
class DocsIndexCompletenessTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final List<String> ENTRYPOINTS =
        List.of("README.md", "CLAUDE.md", "AGENTS.md", "GEMINI.md", "USAGE.md");

    /** {@code [label](target)}, with an optional title after the target. */
    private static final Pattern LINK =
        Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    @Test
    @DisplayName("every reference doc under docs/ is reachable from the entry documents")
    void everyReferenceDocIsReachable() throws IOException {
        assumeTrue(Files.isDirectory(REPO_ROOT.resolve("docs")),
            "repo layout not reachable from " + REPO_ROOT + "; skipping");

        Set<Path> scope = referenceDocs();
        assumeTrue(scope.size() > 5, "found only " + scope.size() + " docs; layout wrong");

        Set<Path> reached = new LinkedHashSet<>();
        Deque<Path> queue = new ArrayDeque<>();
        for (String entry : ENTRYPOINTS) {
            Path p = REPO_ROOT.resolve(entry);
            if (Files.isRegularFile(p)) {
                queue.add(p.normalize());
            }
        }
        Set<Path> visited = new LinkedHashSet<>(queue);
        while (!queue.isEmpty()) {
            Path doc = queue.removeFirst();
            Matcher m = LINK.matcher(stripFencedCode(read(doc)));
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://") || target.startsWith("https://")
                        || target.startsWith("mailto:") || target.startsWith("#")) {
                    continue;
                }
                int fragment = target.indexOf('#');
                if (fragment >= 0) {
                    target = target.substring(0, fragment);
                }
                if (target.isEmpty()) {
                    continue;
                }
                Path resolved = doc.getParent().resolve(target).normalize();
                if (!Files.isRegularFile(resolved)) {
                    continue; // dead links are DocumentationLinksTest's finding, not this one's
                }
                if (scope.contains(resolved)) {
                    reached.add(resolved);
                }
                if (resolved.toString().endsWith(".md") && visited.add(resolved)) {
                    queue.addLast(resolved);
                }
            }
        }

        Set<Path> orphans = new TreeSet<>(scope);
        orphans.removeAll(reached);
        List<String> report = new ArrayList<>();
        for (Path orphan : orphans) {
            report.add(REPO_ROOT.relativize(orphan).toString().replace('\\', '/'));
        }
        assertTrue(report.isEmpty(),
            "reference docs no entry document routes to (link each from the doc that owns its "
                + "topic, or move it out of docs/ if it is not reference material): " + report);
    }

    private Set<Path> referenceDocs() throws IOException {
        Set<Path> scope = new LinkedHashSet<>();
        Path docs = REPO_ROOT.resolve("docs");
        Files.walkFileTree(docs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".md")) {
                    scope.add(file.normalize());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Path spec = REPO_ROOT.resolve("SPEC.md");
        if (Files.isRegularFile(spec)) {
            scope.add(spec.normalize());
        }
        return scope;
    }

    private static String stripFencedCode(String text) {
        return text.replaceAll("(?s)```.*?```", "");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
