package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** A table row: the property, then the Maven Central path of its artifact. */
    private static final Pattern ROW = Pattern.compile("(?m)^([a-zA-Z0-9.-]+\\.version)\\s+[a-z0-9]+(/[A-Za-z0-9._-]+)+$");

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
                + "property with the Maven Central path of the artifact it pins.");
    }

    private static String read(String relative) throws IOException {
        Path file = REPO_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), relative + " is missing");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
