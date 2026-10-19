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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every version property in {@code vibetags-parent/pom.xml} has a row in the table of
 * {@code tools/bump-dependencies.sh}.
 *
 * <p>The script exits 2 on a property its table lacks, before it reports anything else, and it
 * runs only when someone asks for a dependency report. {@code build-helper-maven-plugin} (#686)
 * and the three KSP properties (#758) went in without rows, so from then on the report stopped
 * before it printed the Maven pin {@code publish.yml} deploys on, and nobody saw it fail.
 */
@DisplayName("tools/bump-dependencies.sh has a row for every version property in the parent pom")
class BumpDependenciesTableTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /** The same match as the script's own check: an element whose name ends in {@code .version}. */
    private static final Pattern PROPERTY = Pattern.compile("<([a-zA-Z0-9.-]*\\.version)>");

    /**
     * A table row: the property, then where its releases are listed (a Maven Central path, a
     * plugins.gradle.org path or a named feed), then optionally the release line it stays on.
     */
    private static final Pattern ROW =
        Pattern.compile("(?m)^([a-zA-Z0-9.-]+\\.version)\\s+[a-z0-9]+[:/][A-Za-z0-9._:/-]+(\\s+[0-9.]+)?$");

    @Test
    @DisplayName("no parent-pom version property is missing from the script's table")
    void everyPropertyHasARow() throws IOException {
        String pom = read("vibetags-parent/pom.xml");
        String script = read("tools/bump-dependencies.sh");
        int start = script.indexOf("PINS=\"");
        int end = script.indexOf("\n\"", start);
        assertTrue(start >= 0 && end > start, "tools/bump-dependencies.sh has no PINS=\"...\" table.");
        String table = script.substring(start, end);

        Set<String> rows = new TreeSet<>();
        for (Matcher m = ROW.matcher(table); m.find(); ) {
            rows.add(m.group(1));
        }
        Set<String> missing = new TreeSet<>();
        for (Matcher m = PROPERTY.matcher(pom); m.find(); ) {
            if (!rows.contains(m.group(1))) {
                missing.add(m.group(1));
            }
        }
        assertTrue(missing.isEmpty(),
            "vibetags-parent/pom.xml declares " + missing + " but tools/bump-dependencies.sh has no "
                + "row for them, so the script exits 2 before reporting anything. Add a row per "
                + "property with the source its releases are listed in.");
    }

    @Test
    @DisplayName("the stable-only filter drops every pre-release spelling Central carries, and nothing else")
    void stableFilterDropsDotQualifiedPreReleases() throws IOException {
        // MapStruct, Hibernate and others qualify a pre-release with a dot (1.7.0.Beta2, 6.0.0.CR1).
        // The filter matched only a hyphen, so the default "stable releases only" report offered
        // MapStruct's beta as the upgrade. Read the pattern out of the script, the way grep -viE
        // applies it, so this checks the filter that runs rather than a copy of it.
        String script = read("tools/bump-dependencies.sh");
        Matcher m = Pattern.compile("grep -viE -- '([^']+)'").matcher(script);
        assertTrue(m.find(), "tools/bump-dependencies.sh no longer filters pre-releases with grep -viE");
        Pattern preRelease = Pattern.compile(m.group(1), Pattern.CASE_INSENSITIVE);

        for (String pre : List.of("1.7.0.Beta2", "1.7.0.Beta1", "6.0.0.CR1", "2.0.0-M1", "1.0.0-rc1",
                "5.0.0-alpha-3", "1.2.0.Alpha1", "3.0.0-SNAPSHOT", "26-ea")) {
            assertTrue(preRelease.matcher(pre).find(), pre + " passed the stable-only filter");
        }
        for (String stable : List.of("1.6.3", "1.18.48", "7.1.4.Final", "47", "2.13.18", "3.16.0", "0.2.0")) {
            assertFalse(preRelease.matcher(stable).find(), stable + " was dropped as a pre-release");
        }
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
