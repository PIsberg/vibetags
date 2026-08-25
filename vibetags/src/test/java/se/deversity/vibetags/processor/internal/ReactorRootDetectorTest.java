package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Whether an ancestor directory is really this module's reactor root, or merely a directory that
 * happens to contain a build file.
 *
 * <p>The answer decides whether the build prints a "you are compiling a module of a larger reactor"
 * diagnostic, so both errors cost something real. A false positive prints that warning on every
 * standalone project that lives inside a checkout with a stray {@code pom.xml} above it, and a
 * warning that fires on correct setups is one people learn to ignore. A false negative is issue
 * #296 itself: a module whose output silently goes to the wrong root with nothing said.
 *
 * <p>Hence the stronger question this asks — not "is there a build file above?" but "does that
 * build file name <em>this</em> directory as one of its modules?". These cases are the ways a build
 * file can name, or fail to name, a directory.
 */
class ReactorRootDetectorTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void aMavenParentThatListsTheModuleIsTheReactorRoot(@TempDir Path root) throws IOException {
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"), """
            <project>
              <modules>
                <module>payments-core</module>
                <module>payments-api</module>
              </modules>
            </project>
            """);

        assertEquals(root, ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aMavenParentThatDoesNotListTheModuleIsNotTheReactorRoot(@TempDir Path root) throws IOException {
        // The whole point: a pom.xml above you is not by itself a relationship. Plenty of projects
        // sit inside a checkout that has one.
        Path module = root.resolve("unrelated-project");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"), """
            <project>
              <modules>
                <module>payments-core</module>
              </modules>
            </project>
            """);

        assertNull(ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aGradleSettingsIncludeIsRecognised(@TempDir Path root) throws IOException {
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("settings.gradle"), "include ':payments-core'\n");

        assertEquals(root, ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aKotlinDslSettingsIncludeIsRecognised(@TempDir Path root) throws IOException {
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("settings.gradle.kts"), "include(\"payments-core\")\n");

        assertEquals(root, ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aNestedModuleIsMatchedByItsColonSeparatedGradlePath(@TempDir Path root) throws IOException {
        // Gradle spells 'group/module' as 'group:module'; matching on the filesystem form would
        // miss every nested module in a Gradle build.
        Path module = root.resolve("group").resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("settings.gradle.kts"), "include(\"group:payments-core\")\n");

        assertEquals(root, ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aNestedModuleIsMatchedByItsSlashSeparatedMavenPath(@TempDir Path root) throws IOException {
        Path module = root.resolve("group").resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"), "<module>group/payments-core</module>\n");

        assertEquals(root, ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void theNearestDeclaringAncestorWins(@TempDir Path root) throws IOException {
        // A reactor inside a reactor: the module belongs to the one that names it, and if both do,
        // to the nearer one. Reporting the outer root would send output past the real aggregator.
        Path group = root.resolve("group");
        Path module = group.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"), "<module>group/payments-core</module>\n");
        write(group.resolve("pom.xml"), "<module>payments-core</module>\n");

        assertEquals(group, ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aModuleWithNoBuildFileAboveItHasNoReactorRoot(@TempDir Path root) throws IOException {
        Path module = root.resolve("standalone");
        Files.createDirectories(module);

        assertNull(ReactorRootDetector.findReactorRootAbove(module),
            "the ordinary standalone project must not be told it is part of a reactor");
    }

    @Test
    void anUnreadableBuildFileProducesNoSignalRatherThanAnError(@TempDir Path root) throws IOException {
        // This runs inside somebody else's compile and only ever decides whether to print one
        // diagnostic. A build file it cannot read must cost the diagnostic, not the build.
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        Files.createDirectories(root.resolve("pom.xml")); // a directory where a file is expected

        assertNull(ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aBuildFileWithNonUtf8BytesProducesNoSignalRatherThanAnError(@TempDir Path root) throws IOException {
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        Files.write(root.resolve("pom.xml"), new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA9});

        assertNull(ReactorRootDetector.findReactorRootAbove(module),
            "a build file this cannot decode is not evidence of anything");
    }

    @Test
    void aModuleAtTheFilesystemRootIsNotWalkedPast(@TempDir Path root) {
        Path filesystemRoot = root.getRoot();
        assertNull(ReactorRootDetector.findReactorRootAbove(filesystemRoot),
            "the walk terminates rather than running off the top of the tree");
    }

    @Test
    void aDeclarationOnALineWithOtherContentStillCounts(@TempDir Path root) throws IOException {
        // Build files are matched by substring rather than parsed, precisely so a formatting the
        // detector does not recognise cannot make it silently answer "no".
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"),
            "  <modules><module>payments-core</module><module>payments-api</module></modules>\n");

        assertEquals(root, ReactorRootDetector.findReactorRootAbove(module));
    }
}
