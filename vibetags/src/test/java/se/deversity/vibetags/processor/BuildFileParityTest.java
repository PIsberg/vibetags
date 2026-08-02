package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code pom.xml} and {@code build.gradle} build the same module and are kept in agreement by
 * nothing but habit. CI builds both, so a dependency added to one and not the other compiles
 * locally, passes the whole Maven gate, and fails several minutes into the Gradle job — which is
 * exactly how the SnakeYAML test dependency arrived, green everywhere the author looked.
 *
 * <p>This compares the two test-dependency lists directly, so the mismatch is a failed assertion
 * naming the missing coordinate instead of a compiler error in a second build system.
 *
 * <p>Scope is deliberately narrow: test dependencies only. Compile dependencies differ legitimately
 * (Maven's `provided`/optional handling has no one-line Gradle equivalent here), and widening this
 * to cover them would trade a real guard for a stream of false alarms.
 */
class BuildFileParityTest {

    /** {@code testImplementation 'group:artifact:version'}, single or double quoted. */
    private static final Pattern GRADLE_TEST_DEP =
        Pattern.compile("^\\s*testImplementation\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    /** One {@code <dependency>} element, captured whole so its scope can be inspected. */
    private static final Pattern POM_DEPENDENCY =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

    @Test
    void mavenAndGradleDeclareTheSameTestDependencies() {
        Path moduleRoot = moduleRoot();
        Set<String> fromPom = pomTestDependencies(read(moduleRoot.resolve("pom.xml")));
        Set<String> fromGradle = gradleTestDependencies(read(moduleRoot.resolve("build.gradle")));

        assertTrue(fromPom.size() >= 5,
            "parsed only " + fromPom.size() + " test dependencies from pom.xml — the parser has "
                + "drifted from the file and this test is no longer checking anything: " + fromPom);

        assertEquals(fromPom, fromGradle,
            "pom.xml and build.gradle disagree on test dependencies. CI builds both, so whichever "
                + "one is missing an entry fails there and nowhere else."
                + "\n  only in pom.xml:      " + minus(fromPom, fromGradle)
                + "\n  only in build.gradle: " + minus(fromGradle, fromPom));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * The directory holding both build files. Surefire and Gradle both run from the module
     * directory, but resolving upward as well keeps the test honest under an IDE runner that
     * chooses the repository root instead.
     */
    private static Path moduleRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 4 && candidate != null; depth++) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isRegularFile(candidate.resolve("build.gradle"))) {
                return candidate;
            }
            Path nested = candidate.resolve("vibetags");
            if (Files.isRegularFile(nested.resolve("pom.xml"))
                && Files.isRegularFile(nested.resolve("build.gradle"))) {
                return nested;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("could not locate a directory holding both pom.xml and "
            + "build.gradle, starting from " + System.getProperty("user.dir"));
    }

    private static Set<String> pomTestDependencies(String pom) {
        Map<String, String> properties = pomProperties(pom);
        Set<String> found = new LinkedHashSet<>();
        Matcher dependencies = POM_DEPENDENCY.matcher(pom);
        while (dependencies.find()) {
            String block = dependencies.group(1);
            if (!block.contains("<scope>test</scope>")) continue;
            found.add(tag(block, "groupId") + ":" + tag(block, "artifactId") + ":"
                + resolve(tag(block, "version"), properties));
        }
        return found;
    }

    /**
     * The {@code <properties>} block, so a version declared as {@code ${junit.version}} compares
     * against the literal Gradle writes rather than reporting a difference that is only spelling.
     */
    private static Map<String, String> pomProperties(String pom) {
        Map<String, String> properties = new LinkedHashMap<>();
        Matcher block = Pattern.compile("<properties>(.*?)</properties>", Pattern.DOTALL).matcher(pom);
        if (!block.find()) return properties;
        Matcher entry = Pattern.compile("<([A-Za-z0-9_.\\-]+)>([^<]*)</\\1>").matcher(block.group(1));
        while (entry.find()) {
            properties.put(entry.group(1), entry.group(2).trim());
        }
        return properties;
    }

    private static String resolve(String value, Map<String, String> properties) {
        Matcher reference = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (reference.find()) {
            String replacement = properties.get(reference.group(1));
            reference.appendReplacement(resolved,
                Matcher.quoteReplacement(replacement == null ? reference.group(0) : replacement));
        }
        reference.appendTail(resolved);
        return resolved.toString();
    }

    private static Set<String> gradleTestDependencies(String gradle) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = GRADLE_TEST_DEP.matcher(gradle);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static String tag(String block, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(block);
        return matcher.find() ? matcher.group(1).trim() : "<no " + name + ">";
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> difference = new LinkedHashSet<>(a);
        difference.removeAll(b);
        return difference;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }
}
