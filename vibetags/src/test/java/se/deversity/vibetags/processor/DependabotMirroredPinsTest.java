package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A version that has copies outside the pom is not left to Dependabot.
 *
 * <p>Dependabot's Maven entry edits the property in {@code vibetags-parent/pom.xml} and nothing
 * else. When that property also has copies listed in {@code vibetags-parent/version-mirrors.txt}
 * (the KSP example build, README snippets, the example poms), the bump arrives with every copy
 * behind and {@link BuildVersionParityTest} fails in every Maven and Gradle job. #1024 and #1092,
 * both {@code ksp.version}, arrived red that way. The workflow that was to push the synced copies
 * back (#1038) needed a GitHub App nobody created, so it pushed nothing.
 *
 * <p>So a mirrored property is bumped by {@code tools/bump-dependencies.sh} and the sync script,
 * and the Maven entry in {@code .github/dependabot.yml} ignores every coordinate whose version is
 * one. This test derives that set from the poms Dependabot reads and the mirrors list, so a new
 * copy of a version Dependabot can see fails here, naming the ignore entry to add, instead of on
 * the next bump. The reverse holds too: a whole-dependency ignore must name a mirrored coordinate,
 * so an ignore left behind after its copies are gone cannot quietly stop updates.
 */
class DependabotMirroredPinsTest {

    /** {@code vibetags/} is the surefire and Gradle test working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /** groupId (absent for a plugin of the default group), artifactId, then a property-reference version. */
    private static final Pattern COORDINATE = Pattern.compile(
        "(?:<groupId>([^<]+)</groupId>\\s*)?<artifactId>([^<]+)</artifactId>\\s*"
            + "<version>\\$\\{([^}]+)}</version>");
    private static final Pattern PROPERTY_VERSION = Pattern.compile("<version>\\$\\{");
    private static final Pattern RELATIVE_PATH = Pattern.compile("<relativePath>([^<]+)</relativePath>");
    private static final String DEFAULT_PLUGIN_GROUP = "org.apache.maven.plugins";

    @Test
    void everyMirroredVersionDependabotCanSeeIsIgnoredAndEveryIgnoreIsAMirror() {
        assertTrue(Files.isRegularFile(REPO_ROOT.resolve("vibetags-parent/pom.xml")),
            "repository root not found at " + REPO_ROOT);
        Map<String, String> coordinates = dependabotCoordinates(REPO_ROOT);
        // The poms declare over thirty managed versions; far fewer means the scan stopped reading them.
        assertTrue(coordinates.size() >= 20,
            "found only " + coordinates.size() + " property-versioned coordinates in the poms "
                + "Dependabot reads, so the scan has drifted from them: " + coordinates);
        List<String> problems = problems(REPO_ROOT);
        assertTrue(problems.isEmpty(), String.join("\n  ", problems));
    }

    /** The check reports both directions, so it is known to fail, not only to pass. */
    @Test
    void anUnignoredMirrorAndAnIgnoreWithNoMirrorAreBothReported(@TempDir Path root) throws IOException {
        plant(root.resolve("vibetags/pom.xml"), """
            <project><parent><relativePath>../parent/pom.xml</relativePath></parent>
            <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>
            <version>${compiler.version}</version></plugin></plugins></build></project>
            """);
        plant(root.resolve("parent/pom.xml"), """
            <project><dependencyManagement><dependencies>
            <!-- <dependency><groupId>x</groupId><artifactId>y</artifactId><version>${gone.version}</version></dependency> -->
            <dependency><groupId>com.example</groupId><artifactId>lib</artifactId>
            <version>${lib.version}</version></dependency>
            </dependencies></dependencyManagement></project>
            """);
        plant(root.resolve("vibetags-parent/version-mirrors.txt"), """
            # comment
            compiler.version   examples/*/pom.xml   <version>([^<]+)</version>
            """);
        plant(root.resolve(".github/dependabot.yml"), """
            version: 2
            updates:
              - package-ecosystem: maven
                directory: /vibetags
                ignore:
                  - dependency-name: "com.example:*"
                  - dependency-name: "com.example:lib"
                    versions: [ "[2,)" ]
            """);

        List<String> problems = problems(root);

        assertEquals(2, problems.size(), String.join("\n", problems));
        assertTrue(problems.get(0).contains(DEFAULT_PLUGIN_GROUP + ":maven-compiler-plugin")
                && problems.get(0).contains("compiler.version"),
            "the unignored mirror is reported with its default plugin group: " + problems.get(0));
        assertTrue(problems.get(1).contains("com.example:*"),
            "the whole-dependency ignore naming no mirrored coordinate is reported: " + problems.get(1));
    }

    /**
     * One line per mirrored coordinate the Maven entry does not ignore, then one per
     * whole-dependency ignore that names no mirrored coordinate. An ignore narrowed by
     * {@code versions} or {@code update-types} is a different decision and is left alone.
     */
    static List<String> problems(Path root) {
        Set<String> mirrored = mirroredProperties(root);
        Map<String, String> coordinates = dependabotCoordinates(root);
        List<String> wholeIgnores = wholeDependencyIgnores(mavenEntry(root));

        List<String> problems = new ArrayList<>();
        coordinates.forEach((coordinate, property) -> {
            if (mirrored.contains(property)
                    && wholeIgnores.stream().noneMatch(p -> matches(p, coordinate))) {
                problems.add(coordinate + " takes its version from " + property + ", which "
                    + "vibetags-parent/version-mirrors.txt copies into files Dependabot does not edit, "
                    + "so its bump would arrive with BuildVersionParityTest red. Add "
                    + "'- dependency-name: \"" + coordinate + "\"' to the maven entry's ignore list in "
                    + ".github/dependabot.yml; tools/bump-dependencies.sh reports it instead.");
            }
        });
        for (String pattern : wholeIgnores) {
            boolean namesAMirror = coordinates.entrySet().stream()
                .anyMatch(e -> mirrored.contains(e.getValue()) && matches(pattern, e.getKey()));
            if (!namesAMirror) {
                problems.add("ignore '" + pattern + "' in .github/dependabot.yml names no coordinate "
                    + "whose version is mirrored, so it stops updates for no reason this file can "
                    + "state. Remove it, or narrow it with versions or update-types.");
            }
        }
        return problems;
    }

    /** {@code groupId:artifactId} to the property its version references, in the poms Dependabot reads. */
    static Map<String, String> dependabotCoordinates(Path root) {
        String directory = String.valueOf(mavenEntry(root).get("directory"));
        Path pom = root.resolve(directory.replaceFirst("^/", "")).resolve("pom.xml").normalize();
        List<Path> poms = new ArrayList<>(List.of(pom));
        Matcher parent = RELATIVE_PATH.matcher(stripComments(read(pom)));
        if (parent.find()) {
            poms.add(pom.getParent().resolve(parent.group(1).trim()).normalize());
        }
        Map<String, String> coordinates = new LinkedHashMap<>();
        for (Path file : poms) {
            String xml = stripComments(read(file));
            int references = 0;
            Matcher reference = PROPERTY_VERSION.matcher(xml);
            while (reference.find()) {
                references++;
            }
            int found = 0;
            Matcher m = COORDINATE.matcher(xml);
            while (m.find()) {
                found++;
                String group = m.group(1) == null ? DEFAULT_PLUGIN_GROUP : m.group(1).trim();
                coordinates.put(group + ":" + m.group(2).trim(), m.group(3).trim());
            }
            assertEquals(references, found, root.relativize(file) + " has " + references
                + " property-referenced versions but the scan read " + found + " coordinates; "
                + "one is declared in a shape this test does not parse");
        }
        return coordinates;
    }

    private static Set<String> mirroredProperties(Path root) {
        Set<String> properties = new LinkedHashSet<>();
        for (String line : read(root.resolve("vibetags-parent/version-mirrors.txt")).split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                properties.add(trimmed.split("\\s+", 2)[0]);
            }
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mavenEntry(Path root) {
        Map<String, Object> config = new Yaml().load(read(root.resolve(".github/dependabot.yml")));
        List<Map<String, Object>> updates = (List<Map<String, Object>>) config.get("updates");
        List<Map<String, Object>> maven = updates.stream()
            .filter(u -> "maven".equals(u.get("package-ecosystem")))
            .toList();
        assertEquals(1, maven.size(), "expected one maven entry in .github/dependabot.yml, found "
            + maven.size() + "; this test reads the poms of exactly one");
        return maven.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> wholeDependencyIgnores(Map<String, Object> entry) {
        Object ignore = entry.get("ignore");
        if (ignore == null) {
            return List.of();
        }
        return ((List<Map<String, Object>>) ignore).stream()
            .filter(i -> !i.containsKey("versions") && !i.containsKey("update-types"))
            .map(i -> String.valueOf(i.get("dependency-name")))
            .toList();
    }

    /** Dependabot's dependency-name syntax: {@code *} matches any run of characters. */
    static boolean matches(String pattern, String coordinate) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return coordinate.matches(regex.toString());
    }

    private static String stripComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    private static void plant(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
