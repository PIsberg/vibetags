package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import se.deversity.vibetags.processor.model.ElementTag;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ElementExclusions} without a compiler: pattern parsing, normalization, and glob matching
 * against {@link TaggedElement#path()}.
 */
@DisplayName("Element exclusions option parsing and glob matching")
class ElementExclusionsTest {

    private static TaggedElement element(String path) {
        return TaggedElement.builder(path)
            .kind(ElementTag.CLASS)
            .names(path, "Simple", "Simple", "Simple")
            .build();
    }

    private static TaggedElement methodElement(String path) {
        return TaggedElement.builder(path)
            .kind(ElementTag.METHOD)
            .names(path, "method", "method", "method")
            .build();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n  ", ",,,", "  ,  ,  "})
    void parse_emptyOrBlankInput_returnsNone(String input) {
        ElementExclusions parsed = ElementExclusions.parse(input);
        assertSame(ElementExclusions.NONE, parsed, "empty or blank input must return NONE instance");
        assertTrue(parsed.isEmpty(), "NONE must be empty");
        assertEquals(List.of(), parsed.patterns(), "NONE must carry no patterns");
    }

    @Test
    void parse_trimsAndPreservesPatterns() {
        ElementExclusions exclusions = ElementExclusions.parse("  com.example.Foo  ,  com.example.bar.*  ");
        assertFalse(exclusions.isEmpty(), "exclusions with patterns must not be empty");
        assertEquals(List.of("com.example.Foo", "com.example.bar.*"), exclusions.patterns(),
            "patterns must be trimmed and kept in written order");
    }

    @Test
    void none_excludesNothing() {
        assertFalse(ElementExclusions.NONE.excludes(element("com.example.Foo")),
            "NONE must not exclude any element");
    }

    @Test
    void exactPath_matchesOnlyExactTarget() {
        ElementExclusions exclusions = ElementExclusions.parse("com.example.Foo");
        assertTrue(exclusions.excludes(element("com.example.Foo")), "exact path must match");
        assertFalse(exclusions.excludes(element("com.example.FooBar")), "prefix match must not match");
        assertFalse(exclusions.excludes(element("com.example.other.Foo")), "different package must not match");
    }

    @Test
    void starWildcard_matchesAcrossPackageBoundaries() {
        ElementExclusions exclusions = ElementExclusions.parse("com.example.*");
        assertTrue(exclusions.excludes(element("com.example.Foo")), "single segment after star matches");
        assertTrue(exclusions.excludes(element("com.example.sub.Bar")), "star matches across dots");
        assertFalse(exclusions.excludes(element("com.other.Foo")), "unmatched prefix must not match");
    }

    @Test
    void questionMarkWildcard_matchesExactlyOneCharacter() {
        ElementExclusions exclusions = ElementExclusions.parse("com.example.Fo?");
        assertTrue(exclusions.excludes(element("com.example.Foo")), "matching single char matches");
        assertTrue(exclusions.excludes(element("com.example.Fox")), "matching single char matches");
        assertFalse(exclusions.excludes(element("com.example.Fo")), "too short path must not match");
        assertFalse(exclusions.excludes(element("com.example.Fooo")), "too long path must not match");
    }

    @Test
    void slashAndDoubleStar_areNormalised() {
        ElementExclusions slash = ElementExclusions.parse("com/example/*");
        assertTrue(slash.excludes(element("com.example.Foo")), "slash must be normalised to dot");

        ElementExclusions doubleStar = ElementExclusions.parse("**/*Fixture*");
        assertTrue(doubleStar.excludes(element("com.example.TestFixtureClass")),
            "double star must be normalised to single star");
    }

    @Test
    void regexMetacharacters_areTreatedAsLiterals() {
        // Dot is not regex wildcard '.'
        ElementExclusions dot = ElementExclusions.parse("com.example.Foo");
        assertFalse(dot.excludes(element("comXexampleXFoo")),
            "dots in patterns must be quoted literally, not act as any-character");

        // A literal ahead of a wildcard is quoted too, not only the tail after the last one
        ElementExclusions dotBeforeStar = ElementExclusions.parse("com.example.*");
        assertFalse(dotBeforeStar.excludes(element("comXexampleXFoo")),
            "dots before a wildcard must be quoted literally, not act as any-character");

        // Inner class '$' is literal
        ElementExclusions inner = ElementExclusions.parse("com.example.Foo$Bar");
        assertTrue(inner.excludes(element("com.example.Foo$Bar")), "dollar sign must match inner class");
        assertFalse(inner.excludes(element("com.example.FooXBar")), "dollar sign must not match other character");

        // Method signature parentheses and array brackets
        ElementExclusions method = ElementExclusions.parse("com.example.Foo.bar(java.lang.String)");
        assertTrue(method.excludes(methodElement("com.example.Foo.bar(java.lang.String)")),
            "parentheses must be quoted literally");
        assertFalse(method.excludes(methodElement("com.example.Foo.bar(int)")),
            "mismatched parameters must not match");

        ElementExclusions arrayMethod = ElementExclusions.parse("com.example.Foo.bar(int[])");
        assertTrue(arrayMethod.excludes(methodElement("com.example.Foo.bar(int[])")),
            "array brackets must be quoted literally");
        assertFalse(arrayMethod.excludes(methodElement("com.example.Foo.bar(long[])")),
            "mismatched array type must not match");

        // Other regex metacharacters
        ElementExclusions special = ElementExclusions.parse("com.example.A+B^C{D}");
        assertTrue(special.excludes(element("com.example.A+B^C{D}")),
            "metacharacters +, ^, {, } must be quoted literally");
    }

    @Test
    void matchesOnPathNotQualifiedName() {
        // Method elements carry enclosing type in path() (e.g. com.example.Foo.testMethod())
        // but not in qualifiedName() (e.g. testMethod). Pattern naming enclosing class must match.
        ElementExclusions exclusions = ElementExclusions.parse("com.example.Foo.*");
        assertTrue(exclusions.excludes(methodElement("com.example.Foo.testMethod()")),
            "pattern targeting enclosing class must match method path");
    }

    @Test
    void multiplePatterns_anyMatchExcludes() {
        ElementExclusions exclusions = ElementExclusions.parse("com.foo.*, com.bar.*");
        assertTrue(exclusions.excludes(element("com.foo.Alpha")), "first pattern matches");
        assertTrue(exclusions.excludes(element("com.bar.Beta")), "second pattern matches");
        assertFalse(exclusions.excludes(element("com.baz.Gamma")), "unmatched pattern must not exclude");
    }
}
