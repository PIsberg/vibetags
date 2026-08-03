package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every relative link and anchor in the repository's Markdown must resolve.
 *
 * <p>A dead link is the cheapest defect for a reader to find and among the most damaging to
 * credibility: the first thing a new user does with a README is click something. Six were live in
 * this repository at once — a table of contents pointing at two renamed headings, a "Context tiers"
 * fragment naming a section that has never existed, a {@code LICENSE} and a workflow linked as if
 * {@code docs/} were the repository root, and two benchmark plots whose relative path was one
 * directory short, so images that were committed never rendered.
 *
 * <p>None of them would fail a build, which is exactly why they accumulated. This test is the thing
 * that disagrees.
 *
 * <p>External {@code http(s)} links are deliberately not checked: that would make the build depend
 * on the network and on other people's uptime, and a red build caused by someone else's outage
 * teaches people to ignore red builds.
 */
@DisplayName("Documentation links resolve")
class DocumentationLinksTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final Set<String> SKIP_DIRS =
        Set.of(".git", "target", "node_modules", "build", ".mvn", ".gradle");

    /** {@code [label](target)}, with an optional title after the target. */
    private static final Pattern LINK =
        Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$", Pattern.MULTILINE);
    private static final Pattern ANCHOR_TAG = Pattern.compile("<a\\s+name=\"([^\"]+)\"");
    private static final Pattern INLINE_LINK_IN_HEADING =
        Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

    /** Anchors are expensive to compute and files are linked repeatedly; compute each once. */
    private final Map<Path, Set<String>> anchorCache = new HashMap<>();

    @Test
    @DisplayName("no relative link points at a missing file, and no fragment at a missing heading")
    void everyRelativeLinkAndAnchorResolves() throws IOException {
        assumeTrue(Files.isDirectory(REPO_ROOT.resolve("docs")),
            "repo layout not reachable from " + REPO_ROOT + "; skipping");

        List<Path> docs = markdownFiles();
        assumeTrue(docs.size() > 10, "found only " + docs.size() + " markdown files; layout wrong");

        List<String> broken = new ArrayList<>();
        for (Path doc : docs) {
            String text = read(doc);
            Matcher m = LINK.matcher(stripFencedCode(text));
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://") || target.startsWith("https://")
                    || target.startsWith("mailto:") || target.startsWith("tel:")) {
                    continue;
                }
                int hash = target.indexOf('#');
                String pathPart = hash < 0 ? target : target.substring(0, hash);
                String fragment = hash < 0 ? "" : target.substring(hash + 1);

                Path destination = doc;
                if (!pathPart.isEmpty()) {
                    destination = doc.getParent().resolve(pathPart).normalize();
                    if (!Files.exists(destination)) {
                        broken.add(rel(doc) + " -> " + target + "  (no such file)");
                        continue;
                    }
                }
                if (!fragment.isEmpty() && destination.toString().endsWith(".md")
                    && !anchorsOf(destination).contains(fragment.toLowerCase(Locale.ROOT))) {
                    broken.add(rel(doc) + " -> " + target + "  (no such anchor)");
                }
            }
        }

        assertTrue(broken.isEmpty(),
            "Broken documentation links (" + broken.size() + " of them, across " + docs.size()
                + " files):\n  " + String.join("\n  ", broken));
    }

    // -----------------------------------------------------------------------

    /**
     * GitHub's heading-to-anchor rule: lower-case, drop everything that is not alphanumeric, a
     * space, or a hyphen, then turn spaces into hyphens.
     *
     * <p>Two details decide whether this agrees with GitHub, and getting either wrong turns the
     * test into a generator of false alarms. Leading whitespace is <em>not</em> trimmed: a heading
     * that starts with an emoji keeps the space the emoji left behind, and that space becomes a
     * leading hyphen — which is why {@code ## 🎯 What is VibeTags?} is {@code #-what-is-vibetags}.
     * And U+FE0F, the variation selector, survives the filter, so {@code 🛡️} (which carries one)
     * leaves it behind where {@code 🎯} (which does not) leaves nothing.
     */
    static String slug(String heading) {
        String s = stripTrailing(heading).toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^\\w\\s\\-\\x{FE0F}]", "");
        return s.replaceAll("\\s", "-");
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private Set<String> anchorsOf(Path md) {
        return anchorCache.computeIfAbsent(md, path -> {
            Set<String> found = new LinkedHashSet<>();
            String text;
            try {
                text = read(path);
            } catch (IOException e) {
                return found;
            }
            Matcher tags = ANCHOR_TAG.matcher(text);
            while (tags.find()) {
                found.add(tags.group(1).toLowerCase(Locale.ROOT));
            }
            Matcher headings = HEADING.matcher(text);
            while (headings.find()) {
                String heading = headings.group(1);
                found.add(slug(heading).toLowerCase(Locale.ROOT));
                // A heading whose text is itself a link anchors on the label alone.
                found.add(slug(INLINE_LINK_IN_HEADING.matcher(heading).replaceAll("$1"))
                    .toLowerCase(Locale.ROOT));
            }
            return found;
        });
    }

    /**
     * Removes fenced code blocks. Links inside them illustrate generated output — the sample
     * {@code .mentatconfig.json} contains {@code [PaymentProcessor](com.example...)} — and are not
     * navigation. Checking them reports four failures that no reader can ever encounter.
     */
    private static String stripFencedCode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private List<Path> markdownFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(REPO_ROOT)) {
            return paths
                .filter(p -> p.toString().endsWith(".md"))
                .filter(DocumentationLinksTest::isNotInSkippedDirectory)
                .sorted()
                .toList();
        }
    }

    private static boolean isNotInSkippedDirectory(Path p) {
        for (Path part : p) {
            if (SKIP_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private static String read(Path p) throws IOException {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (UncheckedIOException e) {
            throw new IOException(e);
        }
    }

    private static String rel(Path p) {
        return REPO_ROOT.relativize(p).toString().replace('\\', '/');
    }
}
