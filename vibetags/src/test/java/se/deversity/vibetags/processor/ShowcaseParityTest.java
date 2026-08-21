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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Maven and Gradle reactors carry the same 44-annotation showcase, and it must stay the same.
 *
 * <p>Both reactors gate on "every declared annotation has an element section in the generated
 * output", so an annotation added to one showcase and not the other turns a build red either way.
 * What that failure does not say is which of the two files to edit, or that a second copy exists at
 * all: the count is off by one and the message names a directory. Somebody then annotates the
 * reactor they were already looking at and pushes again.
 *
 * <p>Duplicating the sources was a deliberate trade. Sharing them is not available: Gradle would
 * have to point a source set outside its own project directory, which puts the sources outside the
 * VibeTags root and turns every module id into a path hash, the exact failure
 * {@code examples/gradle-flat} exists to prevent. So the copy stays, and this makes keeping it in
 * step a copy rather than a judgement call.
 */
@DisplayName("The Maven and Gradle showcases are the same sources")
class ShowcaseParityTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final String MAVEN = "examples/multimodule/showcase/src/main/java";
    private static final String GRADLE = "examples/gradle-multimodule/annotations-showcase/src/main/java";

    @Test
    @DisplayName("neither showcase has a file, or a byte, the other does not")
    void bothShowcasesAreIdentical() {
        Map<String, String> maven = read(REPO_ROOT.resolve(MAVEN));
        Map<String, String> gradle = read(REPO_ROOT.resolve(GRADLE));

        assertTrue(maven.size() >= 25,
            "found only " + maven.size() + " sources under " + MAVEN + ", so this test is "
                + "comparing almost nothing. The showcase moved or the path is wrong.");

        List<String> problems = new ArrayList<>();
        for (String rel : maven.keySet()) {
            if (!gradle.containsKey(rel)) {
                problems.add(rel + " exists in " + MAVEN + " but not in " + GRADLE);
            } else if (!maven.get(rel).equals(gradle.get(rel))) {
                problems.add(rel + " differs between the two showcases");
            }
        }
        for (String rel : gradle.keySet()) {
            if (!maven.containsKey(rel)) {
                problems.add(rel + " exists in " + GRADLE + " but not in " + MAVEN);
            }
        }

        assertTrue(problems.isEmpty(),
            "The two reactors' showcases have drifted. Copy one over the other rather than "
                + "editing both by hand:\n  cp -r " + MAVEN + "/. " + GRADLE + "/\n\n  "
                + String.join("\n  ", problems));
    }

    private static Map<String, String> read(Path root) {
        Map<String, String> byRelativePath = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return byRelativePath;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        // Line endings are normalised by .gitattributes, but read as text and
                        // strip anyway: a CRLF checkout must not read as a content difference.
                        String body = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
                        byRelativePath.put(root.relativize(p).toString().replace('\\', '/'), body);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byRelativePath;
    }
}
