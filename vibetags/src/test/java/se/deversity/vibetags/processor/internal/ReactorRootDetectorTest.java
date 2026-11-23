package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(Optional.of(root), ReactorRootDetector.findReactorRootAbove(module));
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

        assertEquals(Optional.empty(), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aMaven4SubprojectEntryIsRecognised(@TempDir Path root) throws IOException {
        // Maven 4 spells a module <subproject>; the root round's lock signal already read it, the
        // detached-module warning did not (#1096).
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"), """
            <project>
              <subprojects>
                <subproject>payments-core</subproject>
              </subprojects>
            </project>
            """);

        assertEquals(Optional.of(root), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aWhitespacePaddedModuleEntryIsRecognised(@TempDir Path root) throws IOException {
        // Maven trims the entry, so this is a declaration of payments-core like any other (#1096).
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"), "<project><modules><module> payments-core </module></modules></project>\n");

        assertEquals(Optional.of(root), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aGradleIncludeOfALongerNameDoesNotDeclareTheModule(@TempDir Path root) throws IOException {
        // 'payments-core-extra' contains 'payments-core' as text; it is a different project, and a
        // warning for it would fire on a standalone directory that happens to share a prefix.
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("settings.gradle"), "include ':payments-core-extra'\n");

        assertEquals(Optional.empty(), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aGradleSettingsIncludeIsRecognised(@TempDir Path root) throws IOException {
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("settings.gradle"), "include ':payments-core'\n");

        assertEquals(Optional.of(root), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aKotlinDslSettingsIncludeIsRecognised(@TempDir Path root) throws IOException {
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("settings.gradle.kts"), "include(\"payments-core\")\n");

        assertEquals(Optional.of(root), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aNestedModuleIsMatchedByItsColonSeparatedGradlePath(@TempDir Path root) throws IOException {
        // Gradle spells 'group/module' as 'group:module'; matching on the filesystem form would
        // miss every nested module in a Gradle build.
        Path module = root.resolve("group").resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("settings.gradle.kts"), "include(\"group:payments-core\")\n");

        assertEquals(Optional.of(root), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aNestedModuleIsMatchedByItsSlashSeparatedMavenPath(@TempDir Path root) throws IOException {
        Path module = root.resolve("group").resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"), "<module>group/payments-core</module>\n");

        assertEquals(Optional.of(root), ReactorRootDetector.findReactorRootAbove(module));
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

        assertEquals(Optional.of(group), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aModuleWithNoBuildFileAboveItHasNoReactorRoot(@TempDir Path root) throws IOException {
        Path module = root.resolve("standalone");
        Files.createDirectories(module);

        assertEquals(Optional.empty(), ReactorRootDetector.findReactorRootAbove(module),
            "the ordinary standalone project must not be told it is part of a reactor");
    }

    @Test
    void anUnreadableBuildFileProducesNoSignalRatherThanAnError(@TempDir Path root) throws IOException {
        // This runs inside somebody else's compile and only ever decides whether to print one
        // diagnostic. A build file it cannot read must cost the diagnostic, not the build.
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        Files.createDirectories(root.resolve("pom.xml")); // a directory where a file is expected

        assertEquals(Optional.empty(), ReactorRootDetector.findReactorRootAbove(module));
    }

    @Test
    void aBuildFileWithNonUtf8BytesProducesNoSignalRatherThanAnError(@TempDir Path root) throws IOException {
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        Files.write(root.resolve("pom.xml"), new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA9});

        assertEquals(Optional.empty(), ReactorRootDetector.findReactorRootAbove(module),
            "a build file this cannot decode is not evidence of anything");
    }

    @Test
    void aModuleAtTheFilesystemRootIsNotWalkedPast(@TempDir Path root) {
        Path filesystemRoot = root.getRoot();
        assertEquals(Optional.empty(), ReactorRootDetector.findReactorRootAbove(filesystemRoot),
            "the walk terminates rather than running off the top of the tree");
    }

    @Test
    void aDeclarationOnALineWithOtherContentStillCounts(@TempDir Path root) throws IOException {
        // Entries are found by pattern rather than by parsing the XML, so several on one line, or a
        // layout a parser would reject, still count instead of silently answering "no".
        Path module = root.resolve("payments-core");
        Files.createDirectories(module);
        write(root.resolve("pom.xml"),
            "  <modules><module>payments-core</module><module>payments-api</module></modules>\n");

        assertEquals(Optional.of(root), ReactorRootDetector.findReactorRootAbove(module));
    }

    // declaredModules: the root round's "do I have siblings?" signal (#1081). A false positive costs
    // one lock file in an opted-in project; a false negative is the first-build race itself.

    @Test
    void declaredModulesListsAMavenParentsModulesAcrossLines(@TempDir Path root) throws IOException {
        write(root.resolve("pom.xml"), """
            <project>
              <modules>
                <module>payments-core</module>
                <module> payments/api </module>
              </modules>
            </project>
            """);

        assertEquals(List.of("payments-core", "payments/api"), ReactorRootDetector.declaredModules(root));
    }

    @Test
    void declaredModulesListsMaven4Subprojects(@TempDir Path root) throws IOException {
        write(root.resolve("pom.xml"),
            "<project><subprojects><subproject>payments-core</subproject></subprojects></project>");

        assertEquals(List.of("payments-core"), ReactorRootDetector.declaredModules(root));
    }

    @Test
    void declaredModulesListsGradleIncludesFromBothSettingsDialects(@TempDir Path root) throws IOException {
        write(root.resolve("settings.gradle"), "rootProject.name = 'app'\ninclude ':payments:core', 'cli'\n");
        write(root.resolve("settings.gradle.kts"), "include(\"api\")\n");

        assertEquals(List.of("payments/core", "cli", "api"), ReactorRootDetector.declaredModules(root));
    }

    /**
     * Gradle's own templates and most multi-project builds spread {@code include} over lines, and
     * a line-by-line read saw only {@code include(} and none of the names (#1120). The root's
     * lock decision and the detached-module warning then took a reactor for a single project.
     */
    @Test
    void declaredModulesListsAMultiLineGradleInclude(@TempDir Path root) throws IOException {
        write(root.resolve("settings.gradle.kts"),
            "rootProject.name = \"app\"\ninclude(\n    \":core\",\n    \":app\" // the CLI\n)\n");
        write(root.resolve("settings.gradle"),
            "include ':api',\n        ':payments:core'\n");

        assertEquals(List.of("api", "payments/core", "core", "app"), ReactorRootDetector.declaredModules(root));
    }

    /** {@code includeBuild} names a composite build, not a subproject, and a comment names nothing. */
    @Test
    void anIncludedBuildOrACommentedIncludeIsNotASubproject(@TempDir Path root) throws IOException {
        write(root.resolve("settings.gradle.kts"),
            "pluginManagement { repositories { maven(\"https://repo.example/m2\") } }\n"
                + "includeBuild(\"../plugins\")\n// include(\":old\")\n/* include(\":older\") */\ninclude(\":kept\")\n");

        assertEquals(List.of("kept"), ReactorRootDetector.declaredModules(root));
    }

    /**
     * A settings file being edited, or written by hand, need not be well formed: the scan keeps the
     * names it can read and stops at what it cannot, rather than reading past a broken literal into
     * the next line or past an unclosed comment.
     */
    @Test
    void anUnfinishedIncludeYieldsTheNamesItSpellsOut(@TempDir Path root) throws IOException {
        assertEquals(List.of("a", "b"), includes(root, "include(\":a\", \":b\""));
        assertEquals(List.of("c", "d"), includes(root, "include 'c',\n        'd',"));
        assertEquals(List.of("f"), includes(root, "include 'e\ninclude 'f'\n"));
        assertEquals(List.of(), includes(root, "include 'z"));
        assertEquals(List.of("g"), includes(root, "include 'g', /* never closed\ninclude 'h'\n"));
        assertEquals(List.of(), includes(root, "rootProject.name = 'app'\ninclude"));
    }

    /**
     * The word {@code include} inside a string literal is text, not a declaration: a description or
     * a println that mentions it named a subproject that does not exist. An escaped quote does not
     * end the literal it sits in.
     */
    @Test
    void anIncludeInsideAStringLiteralIsNotASubproject(@TempDir Path root) throws IOException {
        assertEquals(List.of("real"), includes(root,
            "println \"say include ':fake' to add one\"\ndef s = \"x \\\" include 'bogus' \"\ninclude ':real'\n"));
    }

    /**
     * Where a comment or a literal ends decides which {@code include} is read. The cases above each
     * show one way to get that wrong; these are the rest of the scanner's rules, one line each, so
     * that rewriting it cannot move a boundary unnoticed.
     */
    @Test
    void commentsAndLiteralsEndWhereTheLanguageEndsThem(@TempDir Path root) throws IOException {
        assertEquals(List.of("a"), includes(root,
            "println \"see http://example.com//x\" // a real comment\ninclude 'a'\n"),
            "a // inside a literal is not a comment, and the one after the literal is");
        assertEquals(List.of("yes"), includes(root, "def s = 'it\\'s include \"not\"'\ninclude 'yes'\n"),
            "the other kind of quote does not end a literal");
        assertEquals(List.of("after"), includes(root, "def s = 'a\\\\'\ninclude 'after'\n"),
            "an escaped backslash does not escape the quote after it");
        assertEquals(List.of("kept", "next"), includes(root,
            "/* include 'gone' */ include 'kept' /* x\n y */ include 'next'\n"));
        assertEquals(List.of(), includes(root, "/* never closed include 'gone'\ninclude 'also gone'\n"),
            "a block comment that never closes runs to the end of the file");
        assertEquals(List.of("a", "b", "e"), includes(root,
            "include 'a'\r\ninclude 'b' // c\r\n/* d */include 'e'\r\n"), "Windows line endings");
        assertEquals(List.of("a"), includes(root, "x = 1 / 2 // half\ny = 'q' /*c*/ + \"r\"\ninclude 'a'\n"),
            "a lone slash is division");
        assertEquals(List.of("app", "libs/core"), includes(root,
            "include(\n  \":app\",   // the app\n  \":libs:core\" /* core */,\n)\n"),
            "comments between the arguments of a call are not arguments");
        assertEquals(List.of("a/b", "c"), includes(root, "include ':a:b', \"::c\", ':'\n"),
            "leading colons go, inner ones are directories, and a path of colons alone names nothing");
        assertEquals(List.of("z"), includes(root,
            "includeFlat 'y'\nsettings.include 'z'\nrootProject.name = 'include'\n"));
    }

    /** An include written inside a multi-line string is part of that string (#1167). */
    @Test
    void anIncludeInsideATripleQuotedStringIsNotASubproject(@TempDir Path root) throws IOException {
        assertEquals(List.of("real"), includes(root,
            "def notes = \"\"\"\ninclude 'in-a-string'\n\"\"\"\ninclude 'real'\n"));
        assertEquals(List.of("real"), includes(root,
            "val notes = '''\ninclude(\"in-a-string\")\n'''\ninclude(\"real\")\n"));
    }

    /**
     * A module taken out of the build by commenting its entry out is not declared (#1165). Read
     * as declared, a directory compiled on its own drew the "you are a module of a larger reactor"
     * warning from a parent that no longer builds it.
     */
    @Test
    void aCommentedOutMavenModuleIsNotDeclared(@TempDir Path root) throws IOException {
        Path retired = root.resolve("retired");
        Files.createDirectories(retired);
        write(root.resolve("pom.xml"), """
            <project>
              <modules>
                <!-- <module>retired</module> -->
                <module>kept</module>
                <!--
                <module>also-retired</module>
                -->
              </modules>
            </project>
            """);

        assertEquals(List.of("kept"), ReactorRootDetector.declaredModules(root));
        assertEquals(Optional.empty(), ReactorRootDetector.findReactorRootAbove(retired));
    }

    private static List<String> includes(Path root, String settings) throws IOException {
        Path file = root.resolve("settings.gradle");
        write(file, settings);
        return ReactorRootDetector.gradleIncludes(file);
    }

    @Test
    void declaredModulesIsEmptyForASingleProjectBuild(@TempDir Path root) throws IOException {
        write(root.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        write(root.resolve("settings.gradle"), "rootProject.name = 'app'\n");

        assertEquals(List.of(), ReactorRootDetector.declaredModules(root));
    }

    @Test
    void declaredModulesOfAnUndecodablePomIsEmptyRatherThanAnError(@TempDir Path root) throws IOException {
        Files.write(root.resolve("pom.xml"), new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA9});

        assertEquals(List.of(), ReactorRootDetector.declaredModules(root));
    }
}
