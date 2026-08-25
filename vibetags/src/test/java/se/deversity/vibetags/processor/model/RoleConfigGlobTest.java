package se.deversity.vibetags.processor.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glob→regex translation behind {@code .vibetags-roles} routing, exercised directly.
 *
 * <p>{@link RoleConfig#globToRegex} is where a routing mistake becomes silent: a glob that
 * translates too loosely pulls unrelated classes into a role's rule file, and one that translates
 * too tightly leaves an element with no rules at all. Neither shows up as a build failure, so the
 * translation is pinned here character class by character class rather than only through the
 * end-to-end paths that happen to use the common shapes.
 *
 * <p>{@link RoleConfigTest} covers routing through a loaded config; this covers the translator.
 */
class RoleConfigGlobTest {

    private static boolean matches(String glob, String path) {
        Pattern p = RoleConfig.globToRegex(glob);
        return p.matcher(path).matches();
    }

    @Test
    void questionMarkMatchesExactlyOneCharacterWithinASegment() {
        assertTrue(matches("com/example/V?.java", "com/example/V1.java"));
        assertFalse(matches("com/example/V?.java", "com/example/V12.java"),
            "'?' is one character, not one-or-more");
        assertFalse(matches("com/?.java", "com/a/b.java"),
            "'?' must not cross a path separator");
    }

    @Test
    void trailingDoubleStarMatchesAnyRemainingDepth() {
        assertTrue(matches("com/example/**", "com/example/a/b/C.java"));
        assertTrue(matches("com/example/**", "com/example/C.java"));
    }

    @Test
    void doubleStarNotFollowedBySeparatorStaysAWildcard() {
        // '**' only means "zero or more segments" when spelled '**/'. Elsewhere it is a plain
        // any-character wildcard, which is what makes 'Order**Test' work as a name fragment.
        assertTrue(matches("com/example/Order**Test.java", "com/example/OrderServiceTest.java"));
        assertTrue(matches("com/example/Order**Test.java", "com/example/Order/deep/Test.java"),
            "a bare '**' is not segment-bounded");
    }

    @Test
    void leadingDoubleStarSlashMatchesZeroSegmentsToo() {
        assertTrue(matches("**/*Controller.java", "OrderController.java"),
            "'**/' must match zero segments, or a root-level file never routes");
        assertTrue(matches("**/*Controller.java", "com/example/web/OrderController.java"));
    }

    @Test
    void singleStarDoesNotCrossASeparator() {
        assertFalse(matches("com/*.java", "com/example/A.java"));
        assertTrue(matches("com/*.java", "com/A.java"));
    }

    @Test
    void dotIsLiteralNotAnyCharacter() {
        assertTrue(matches("a/B.java", "a/B.java"));
        assertFalse(matches("a/B.java", "a/BXjava"),
            "'.' must be escaped, otherwise the glob would also match 'BXjava'");
    }

    @Test
    void unmatchedClosingBraceIsALiteral() {
        // A typo like 'a}b' must translate to something that compiles and matches the literal
        // text, rather than emitting a stray group close and throwing PatternSyntaxException.
        Pattern p = RoleConfig.globToRegex("com/ex}ample/A.java");
        assertNotNull(p);
        assertTrue(p.matcher("com/ex}ample/A.java").matches());
    }

    @Test
    void commaOutsideABraceGroupIsALiteral() {
        assertTrue(matches("com/a,b/C.java", "com/a,b/C.java"),
            "outside '{...}' a comma is part of the name, not an alternation");
    }

    @Test
    void braceAlternationBecomesAGroup() {
        assertTrue(matches("com/{web,api}/A.java", "com/web/A.java"));
        assertTrue(matches("com/{web,api}/A.java", "com/api/A.java"));
        assertFalse(matches("com/{web,api}/A.java", "com/dao/A.java"));
    }

    @Test
    void regexMetacharactersInAGlobAreEscapedNotInterpreted() {
        // Every one of these is legal in a Java identifier context or a directory name on some
        // filesystem, and every one is a regex operator. Left unescaped they would either throw
        // or match far too much.
        for (String meta : new String[]{"(", ")", "[", "]", "^", "$", "+", "|"}) {
            String path = "com/a" + meta + "b/C.java";
            assertTrue(matches(path, path), "metacharacter '" + meta + "' must be matched literally");
        }
    }

    @Test
    void backslashInAGlobIsEscaped() {
        assertTrue(matches("com/a\b/C.java", "com/a\b/C.java"));
    }

    @Test
    void aLineWithNoEqualsIsSkippedRatherThanFailingTheFile(@TempDir Path dir) throws IOException {
        RoleConfig cfg = write(dir,
            "this line has no assignment at all",
            "services = **/*Service.java");
        assertNotNull(cfg);
        assertFalse(cfg.isEmpty(), "a junk line must not take the rest of the file down with it");
        assertEquals(1, cfg.globsFor("services").size(),
            "the surviving line must still route");
    }

    @Test
    void emptyMatcherTokensAreSkipped(@TempDir Path dir) throws IOException {
        RoleConfig cfg = write(dir, "services = **/*Service.java, ,, **/*Repo.java");
        assertNotNull(cfg);
        assertEquals(2, cfg.globsFor("services").size(),
            "the two blank tokens must not become matchers");
    }

    private static RoleConfig write(Path dir, String... lines) throws IOException {
        Files.writeString(dir.resolve(RoleConfig.FILE_NAME),
            String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return RoleConfig.load(dir);
    }
}
