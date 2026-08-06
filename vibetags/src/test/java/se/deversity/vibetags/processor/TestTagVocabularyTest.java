package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fast/e2e test split is held together by one string, and nothing else checks it.
 *
 * <p>{@code mvn test} skips {@code @Tag("e2e")} classes so the local loop stays fast; {@code -Pe2e}
 * adds them back and CI runs that. The mechanism has two failure modes that both look green:
 *
 * <ul>
 *   <li>A misspelled tag — {@code @Tag("e2ee")} — is excluded by nothing and matched by nothing, so
 *       the class quietly runs in the fast tier forever and no one notices it got slower.
 *   <li>Renaming the tag in {@code pom.xml} but not {@code build.gradle} (or the reverse) leaves one
 *       build system running 52 classes the other skips. Both still report success.
 * </ul>
 *
 * <p>This is the previous gate's failure repeating: {@code -Drun.integration.tests=true} was dropped
 * in 2026-04 because nothing pinned it to anything, so it drifted into meaninglessness. The tag name
 * is asserted here, in the suite, rather than trusted to the two build files agreeing by habit.
 */
@DisplayName("The fast/e2e tag vocabulary is closed and both build files agree on it")
class TestTagVocabularyTest {

    /** Surefire's working directory is {@code vibetags/}, so every path here is module-relative. */
    private static final Path MODULE = Paths.get("").toAbsolutePath();

    /** The only tags this suite recognises. Adding one means teaching pom.xml and build.gradle. */
    private static final Set<String> KNOWN_TAGS = Set.of("e2e");

    /**
     * Anchored to the start of a line so the javadoc above — which names a misspelled tag on
     * purpose — is not itself read as a tag. Continuation lines of a javadoc block start with
     * {@code *}, real annotations start with {@code @}.
     */
    private static final Pattern TAG = Pattern.compile("(?m)^\\s*@Tag\\(\"([^\"]*)\"\\)");

    @Test
    void everyTagInTheSuiteIsOneWeRecognise() throws IOException {
        Path testRoot = MODULE.resolve("src/test/java");
        assertTrue(Files.isDirectory(testRoot), "test sources not found at " + testRoot);

        Set<String> unknown = new TreeSet<>();
        int tagged = 0;
        try (Stream<Path> sources = Files.walk(testRoot)) {
            List<Path> javaFiles = sources.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : javaFiles) {
                Matcher matcher = TAG.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    tagged++;
                    if (!KNOWN_TAGS.contains(matcher.group(1))) {
                        unknown.add(matcher.group(1) + " in " + testRoot.relativize(file));
                    }
                }
            }
        }

        assertEquals(Set.of(), unknown,
            "Unrecognised @Tag values. A tag no build file filters on runs in the fast tier and is "
                + "never excluded anywhere, so it silently stops being an e2e test. Either fix the "
                + "spelling or add the tag to KNOWN_TAGS, pom.xml and build.gradle together.");
        assertTrue(tagged > 0, "no @Tag found at all — the split has been removed without this test");
    }

    @Test
    void mavenAndGradleExcludeTheSameTag() throws IOException {
        String pom = Files.readString(MODULE.resolve("pom.xml"), StandardCharsets.UTF_8);
        String gradle = Files.readString(MODULE.resolve("build.gradle"), StandardCharsets.UTF_8);

        for (String tag : KNOWN_TAGS) {
            assertTrue(pom.contains("<vibetags.test.excludedGroups>" + tag + "</vibetags.test.excludedGroups>"),
                "pom.xml no longer excludes '" + tag + "' by default — `mvn test` is running the "
                    + "full suite, or the property was renamed without updating this test");
            assertTrue(gradle.contains("excludeTags \"" + tag + "\""),
                "build.gradle no longer excludes '" + tag + "' — the Gradle legs are running "
                    + "everything while Maven skips it, and the two builds no longer agree");
        }

        assertTrue(pom.contains("<id>e2e</id>"),
            "the -Pe2e profile is gone from pom.xml; CI's `mvn test -Pe2e` would silently run the "
                + "fast tier only and merge changes no full run ever saw");
    }
}
