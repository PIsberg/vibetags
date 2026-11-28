package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text functions that decide where a generated block is, called directly: a marker counts
 * only on a line of its own and outside a CommonMark code fence, a broken pair is one the writer
 * would repair or refuse, a marker inside a body is defused, and front matter ends where its
 * closing fence owns a line and every line above it reads as YAML.
 *
 * <p>{@code GuardrailFileWriterMarkerShapeTest} and the front-matter tests reach these through whole
 * files. These pin each rule on its own, so a refactor that moves them can be held to the same
 * answers case by case.
 */
@DisplayName("Marker lines, fences and front matter")
class MarkerLinesTest {

    private static final String START = GuardrailFileWriter.MARKER_START_MD;
    private static final String END = GuardrailFileWriter.MARKER_END_MD;
    private static final String FENCE = "```";

    // ---------------------------------------------------------------- a marker owns its line

    @Test
    @DisplayName("a marker counts on a line of its own, whitespace around it allowed, CRLF included")
    void aMarkerOnItsOwnLineCounts() {
        assertEquals(0, GuardrailFileWriter.indexOfMarkerLine(START + "\n", START, 0));
        assertEquals(6, GuardrailFileWriter.indexOfMarkerLine("intro\n" + START, START, 0));
        assertEquals(8, GuardrailFileWriter.indexOfMarkerLine("intro\n  " + START + " \t\n", START, 0));
        assertEquals(7, GuardrailFileWriter.indexOfMarkerLine("intro\r\n" + START + "\r\nrest", START, 0));
    }

    @Test
    @DisplayName("a marker quoted in prose is not one")
    void aMarkerInProseIsNotOne() {
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine("Write `" + START + "` to start.\n", START, 0));
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine("x" + START + "\n", START, 0));
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine(START + "x\n", START, 0));
    }

    @Test
    @DisplayName("the search starts at fromIndex, a negative one at zero, and skips a prose mention to find a line")
    void theSearchStartsAtFromIndex() {
        String text = START + "\nsee " + START + " here\n" + START + "\n";
        int second = text.lastIndexOf(START);
        assertEquals(0, GuardrailFileWriter.indexOfMarkerLine(text, START, -5));
        assertEquals(second, GuardrailFileWriter.indexOfMarkerLine(text, START, 1));
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine(text, START, second + 1));
    }

    @Test
    @DisplayName("null or empty text or marker finds nothing")
    void nothingToSearch() {
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine(null, START, 0));
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine("", START, 0));
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine(START, null, 0));
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine(START, "", 0));
    }

    // ---------------------------------------------------------------- fences

    @Test
    @DisplayName("a block start inside a backtick or tilde fence is an example, and the real one below is found")
    void aStartInsideAFenceIsSkipped() {
        for (String fence : new String[] {FENCE, "~~~", FENCE + "markdown"}) {
            String text = fence + "\n" + START + "\nexample\n" + END + "\n" + fence.substring(0, 3) + "\n\n" + START + "\n";
            assertEquals(text.lastIndexOf(START), GuardrailFileWriter.indexOfBlockStart(text, START), fence);
        }
    }

    @Test
    @DisplayName("a fence stays open until a line of the same character, at least as long, with nothing after it")
    void aFenceClosesOnlyByCommonMarksRules() {
        String longer = FENCE + "`\n" + FENCE + "\n" + START + "\n" + FENCE + "`\n" + START + "\n";
        assertEquals(longer.lastIndexOf(START), GuardrailFileWriter.indexOfBlockStart(longer, START),
            "a three-backtick line inside a four-backtick fence does not close it");
        String otherChar = FENCE + "\n~~~\n" + START + "\n";
        assertEquals(-1, GuardrailFileWriter.indexOfBlockStart(otherChar, START), "tildes do not close backticks");
        String trailing = FENCE + "\n" + FENCE + " not a closer\n" + START + "\n";
        assertEquals(-1, GuardrailFileWriter.indexOfBlockStart(trailing, START), "text after the run keeps it open");
        String unclosed = FENCE + "\n" + START + "\n";
        assertEquals(-1, GuardrailFileWriter.indexOfBlockStart(unclosed, START), "an unclosed fence runs to the end");
    }

    @Test
    @DisplayName("a line opening with an inline code span is not a fence (#1161)")
    void anInlineSpanIsNotAFence() {
        String text = FENCE + "inline` code\n" + START + "\n";
        assertEquals(text.indexOf(START), GuardrailFileWriter.indexOfBlockStart(text, START));
        String tildeInfo = "~~~ info with ` tick\n" + START + "\n~~~\n" + START + "\n";
        assertEquals(tildeInfo.lastIndexOf(START), GuardrailFileWriter.indexOfBlockStart(tildeInfo, START),
            "a tilde fence's info string may hold a backtick");
        String shortRun = "``not a fence\n" + START + "\n";
        assertEquals(shortRun.indexOf(START), GuardrailFileWriter.indexOfBlockStart(shortRun, START));
        String indented = "   " + FENCE + "\n" + START + "\n" + FENCE + "\n" + START + "\n";
        assertEquals(indented.lastIndexOf(START), GuardrailFileWriter.indexOfBlockStart(indented, START),
            "a fence line is read stripped");
    }

    // ---------------------------------------------------------------- broken pairs

    @Test
    @DisplayName("a pair is broken when the start has no end after it, or an end has no start")
    void brokenPairs() {
        assertTrue(GuardrailFileWriter.hasBrokenMarkers(START + "\nbody\n", START, END));
        assertTrue(GuardrailFileWriter.hasBrokenMarkers("hand\n" + END + "\n", START, END));
        assertTrue(GuardrailFileWriter.hasBrokenMarkers(END + "\n" + START + "\n", START, END),
            "an end above the start does not close it");
        assertFalse(GuardrailFileWriter.hasBrokenMarkers(START + "\nbody\n" + END + "\n", START, END));
        assertFalse(GuardrailFileWriter.hasBrokenMarkers("no markers at all\n", START, END));
        assertFalse(GuardrailFileWriter.hasBrokenMarkers("Quote `" + START + "` in prose\n", START, END));
        assertFalse(GuardrailFileWriter.hasBrokenMarkers(FENCE + "\n" + START + "\n" + FENCE + "\n", START, END),
            "a lone start inside a fence is an example, not a broken block");
    }

    // ---------------------------------------------------------------- neutralising a body

    @Test
    @DisplayName("a marker on its own line inside a body is defused with a zero-width space; prose is left alone")
    void markersInABodyAreDefused() {
        String body = "reason one\n" + END + "\nsee " + END + " in prose\n  " + START + "\n";
        String out = GuardrailFileWriter.neutraliseMarkers(body, new String[] {START, END});

        assertEquals("reason one\n<!--\u200B" + END.substring(4) + "\nsee " + END + " in prose\n  <!--\u200B"
            + START.substring(4) + "\n", out);
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine(out, START, 0));
        assertEquals(-1, GuardrailFileWriter.indexOfMarkerLine(out, END, 0));
    }

    @Test
    @DisplayName("hash markers are defused after the hash, and a body without markers comes back as it was")
    void hashMarkersAreDefused() {
        String start = GuardrailFileWriter.MARKER_START_HASH;
        String end = GuardrailFileWriter.MARKER_END_HASH;
        String body = start + "\nrule\n" + start + "\n";

        assertEquals("#\u200B" + start.substring(1) + "\nrule\n#\u200B" + start.substring(1) + "\n",
            GuardrailFileWriter.neutraliseMarkers(body, new String[] {start, end}));
        String plain = "nothing to defuse\n";
        assertEquals(plain, GuardrailFileWriter.neutraliseMarkers(plain, new String[] {start, end}));
    }

    // ---------------------------------------------------------------- front matter

    @Test
    @DisplayName("front matter ends just past its closing fence when every line between reads as YAML")
    void frontMatterEndsAtItsClosingFence() {
        String fm = "---\n"
            + "description: \"x\"\n"
            + "'quoted-key': 1\n"
            + "globs:\n"
            + "  - src/**\n"
            + "- top level item\n"
            + "-\n"
            + "# a comment\n"
            + "\n"
            + "    indented continuation\n"
            + "---";
        assertEquals(fm.length(), GuardrailFileWriter.frontMatterEnd(fm + "\nbody"));
        assertEquals(7, GuardrailFileWriter.frontMatterEnd("---\n---\n"), "an empty header is still one");
        assertEquals(9, GuardrailFileWriter.frontMatterEnd("---  \n---\nbody"), "the opener may carry trailing blanks");
        assertEquals(16, GuardrailFileWriter.frontMatterEnd("---\r\na: 1\r\n  ---  \r\nbody"),
            "CRLF, and a closer with whitespace around it: just past its dashes, not its line");
    }

    @Test
    @DisplayName("a horizontal rule, a --- inside a value or an unclosed header is not front matter")
    void whatIsNotFrontMatter() {
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd(null));
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd("body\n---\n"));
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd("---"), "no line after the opener");
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd("--- title\na: 1\n---\n"), "text after the opener");
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd("---\nplain prose line\n---\n"), "a line that is not YAML");
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd("---\n9key: 1\n---\n"), "a key must start with a letter");
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd("---\n: 1\n---\n"), "a colon with no key");
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd("---\na: 1\nb: 2\n"), "no closing fence");
        assertEquals(-1, GuardrailFileWriter.frontMatterEnd("---\na: value with --- inside\n"),
            "a --- inside a value does not close it");
    }

    // ---------------------------------------------------------------- equality

    @Test
    @DisplayName("documents match across CRLF, lone CR and outer whitespace, and differ on anything else")
    void contentMatchesIgnoresLineEndingsOnly() {
        assertTrue(GuardrailFileWriter.contentMatches("a\r\nb\r\n", "a\nb"));
        assertTrue(GuardrailFileWriter.contentMatches("a\rb", "  a\nb\n\n"));
        assertFalse(GuardrailFileWriter.contentMatches("a\nb", "a\n\nb"));
        assertFalse(GuardrailFileWriter.contentMatches("a b", "a\nb"));
    }
}
