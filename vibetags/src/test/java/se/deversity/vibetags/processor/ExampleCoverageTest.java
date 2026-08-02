package se.deversity.vibetags.processor;

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
 * The example projects are the documentation people actually read: someone deciding whether an
 * annotation does what they need opens the example before the reference. An annotation with no
 * example is one that has to be understood from its Javadoc alone.
 *
 * <p>Nothing enforced that. {@code example/README.md} claimed to show "all fifteen VibeTags
 * annotations" while the project had grown to 44 and the example itself used every one of them —
 * the prose had been wrong through twenty-nine additions, because prose does not fail a build.
 *
 * <p>So three things are checked here:
 *
 * <ul>
 *   <li>every annotation is used in {@code example/} and in both multi-module demos;</li>
 *   <li>every annotation appears in {@code example/README.md}'s coverage table;</li>
 *   <li>the file each table row names actually uses that annotation — the row is a promise about
 *       where to look, and a stale path sends the reader somewhere it is not.</li>
 * </ul>
 *
 * <p>{@code tools/demo/} is deliberately excluded: it is the fixture for the animated README demo,
 * and forty-four annotations would make that GIF unreadable.
 */
class ExampleCoverageTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Example projects that must demonstrate the whole annotation set. */
    private static final List<String> EXHAUSTIVE_EXAMPLES = List.of(
        "example/src", "example-multimodule", "example-multimodule-indexed");

    @Test
    void everyAnnotationIsDemonstratedInEveryExhaustiveExample() throws IOException {
        Set<String> annotations = annotationNames();
        assumeTrue(!annotations.isEmpty(), "annotations module not reachable; skipping");

        List<String> problems = new ArrayList<>();
        for (String project : EXHAUSTIVE_EXAMPLES) {
            Path dir = REPO_ROOT.resolve(project);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Set<String> used = annotationsUsedUnder(dir, annotations);
            List<String> missing = annotations.stream().filter(a -> !used.contains(a)).sorted().toList();
            if (!missing.isEmpty()) {
                problems.add(project + " is missing " + missing.size() + ": " + missing);
            }
        }
        assertTrue(problems.isEmpty(),
            "An annotation with no worked example is one a user has to infer from its Javadoc:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void exampleReadmeCoverageTableListsEveryAnnotation() throws IOException {
        Set<String> annotations = annotationNames();
        Path readme = REPO_ROOT.resolve("example/README.md");
        assumeTrue(!annotations.isEmpty() && Files.isRegularFile(readme),
            "repo layout not reachable; skipping");

        String md = Files.readString(readme, StandardCharsets.UTF_8);
        List<String> missing = annotations.stream()
            .filter(a -> !Pattern.compile("\\*\\*@" + a + "\\*\\*").matcher(md).find())
            .sorted()
            .toList();
        assertTrue(missing.isEmpty(),
            "example/README.md's coverage table does not list: " + missing
                + ". Regenerate the table when adding an annotation.");
    }

    @Test
    void everyCoverageTableRowPointsAtAFileThatUsesThatAnnotation() throws IOException {
        Path readme = REPO_ROOT.resolve("example/README.md");
        Path src = REPO_ROOT.resolve("example/src/main/java");
        assumeTrue(Files.isRegularFile(readme) && Files.isDirectory(src),
            "repo layout not reachable; skipping");

        // | **@AILocked** | `com/example/payment/PaymentProcessor.java` (+2 more) |
        Matcher row = Pattern.compile(
            "\\|\\s*\\*\\*@(AI\\w+)\\*\\*\\s*\\|\\s*`([^`]+)`").matcher(
            Files.readString(readme, StandardCharsets.UTF_8));

        List<String> problems = new ArrayList<>();
        int checked = 0;
        while (row.find()) {
            String annotation = row.group(1);
            Path file = src.resolve(row.group(2));
            checked++;
            if (!Files.isRegularFile(file)) {
                problems.add(annotation + " -> " + row.group(2) + " (no such file)");
                continue;
            }
            if (!usesAnnotation(file, annotation)) {
                problems.add(annotation + " -> " + row.group(2) + " (file does not use it)");
            }
        }
        assertTrue(checked >= 40,
            "parsed only " + checked + " coverage-table rows — the table format changed and this "
                + "test is no longer reading it");
        assertTrue(problems.isEmpty(),
            "example/README.md points readers at files that do not demonstrate the annotation:\n  "
                + String.join("\n  ", problems));
    }

    // -----------------------------------------------------------------------

    private static Set<String> annotationNames() throws IOException {
        Path dir = REPO_ROOT.resolve(
            "vibetags-annotations/src/main/java/se/deversity/vibetags/annotations");
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString().replace(".java", "");
                if (name.startsWith("AI")
                    && Files.readString(f, StandardCharsets.UTF_8).contains("public @interface")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static Set<String> annotationsUsedUnder(Path dir, Set<String> annotations)
            throws IOException {
        Set<String> used = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.toString().contains("target") || f.toString().contains("build")) {
                    continue;
                }
                String code = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                for (String a : annotations) {
                    if (Pattern.compile("@" + a + "\\b(?!\\w)").matcher(code).find()) {
                        used.add(a);
                    }
                }
            }
        }
        return used;
    }

    private static boolean usesAnnotation(Path file, String annotation) throws IOException {
        String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
        return Pattern.compile("@" + annotation + "\\b(?!\\w)").matcher(code).find();
    }

    /** Comments are stripped so a mention in Javadoc is not mistaken for a usage. */
    private static String stripComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
    }
}
