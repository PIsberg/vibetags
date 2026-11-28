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
        assertEquals(0, MarkerLines.indexOfMarkerLine(START + "\n", START, 0));
        assertEquals(6, MarkerLines.indexOfMarkerLine("intro\n" + START, START, 0));
        assertEquals(8, MarkerLines.indexOfMarkerLine("intro\n  " + START + " \t\n", START, 0));
        assertEquals(7, MarkerLines.indexOfMarkerLine("intro\r\n" + START + "\r\nrest", START, 0));
    }

    @Test
    @DisplayName("a marker quoted in prose is not one")
    void aMarkerInProseIsNotOne() {
        assertEquals(-1, MarkerLines.indexOfMarkerLine("Write `" + START + "` to start.\n", START, 0));
        assertEquals(-1, MarkerLines.indexOfMarkerLine("x" + START + "\n", START, 0));
        assertEquals(-1, MarkerLines.indexOfMarkerLine(START + "x\n", START, 0));
    }

    @Test
    @DisplayName("the search starts at fromIndex, a negative one at zero, and skips a prose mention to find a line")
    void theSearchStartsAtFromIndex() {
        String text = START + "\nsee " + START + " here\n" + START + "\n";
        int second = text.lastIndexOf(START);
        assertEquals(0, MarkerLines.indexOfMarkerLine(text, START, -5));
        assertEquals(second, MarkerLines.indexOfMarkerLine(text, START, 1));
        assertEquals(-1, MarkerLines.indexOfMarkerLine(text, START, second + 1));
    }

    @Test
    @DisplayName("null or empty text or marker finds nothing")
    void nothingToSearch() {
        assertEquals(-1, MarkerLines.indexOfMarkerLine(null, START, 0));
        assertEquals(-1, MarkerLines.indexOfMarkerLine("", START, 0));
        assertEquals(-1, MarkerLines.indexOfMarkerLine(START, null, 0));
        assertEquals(-1, MarkerLines.indexOfMarkerLine(START, "", 0));
    }

    // ---------------------------------------------------------------- fences

    @Test
    @DisplayName("a block start inside a backtick or tilde fence is an example, and the real one below is found")
    void aStartInsideAFenceIsSkipped() {
        for (String fence : new String[] {FENCE, "~~~", FENCE + "markdown"}) {
            String text = fence + "\n" + START + "\nexample\n" + END + "\n" + fence.substring(0, 3) + "\n\n" + START + "\n";
            assertEquals(text.lastIndexOf(START), MarkerLines.indexOfBlockStart(text, START), fence);
        }
    }

    @Test
    @DisplayName("a fence stays open until a line of the same character, at least as long, with nothing after it")
    void aFenceClosesOnlyByCommonMarksRules() {
        String longer = FENCE + "`\n" + FENCE + "\n" + START + "\n" + FENCE + "`\n" + START + "\n";
        assertEquals(longer.lastIndexOf(START), MarkerLines.indexOfBlockStart(longer, START),
            "a three-backtick line inside a four-backtick fence does not close it");
        String otherChar = FENCE + "\n~~~\n" + START + "\n";
        assertEquals(-1, MarkerLines.indexOfBlockStart(otherChar, START), "tildes do not close backticks");
        String trailing = FENCE + "\n" + FENCE + " not a closer\n" + START + "\n";
        assertEquals(-1, MarkerLines.indexOfBlockStart(trailing, START), "text after the run keeps it open");
        String unclosed = FENCE + "\n" + START + "\n";
        assertEquals(-1, MarkerLines.indexOfBlockStart(unclosed, START), "an unclosed fence runs to the end");
    }

    @Test
    @DisplayName("a line opening with an inline code span is not a fence (#1161)")
    void anInlineSpanIsNotAFence() {
        String text = FENCE + "inline` code\n" + START + "\n";
        assertEquals(text.indexOf(START), MarkerLines.indexOfBlockStart(text, START));
        String tildeInfo = "~~~ info with ` tick\n" + START + "\n~~~\n" + START + "\n";
        assertEquals(tildeInfo.lastIndexOf(START), MarkerLines.indexOfBlockStart(tildeInfo, START),
            "a tilde fence's info string may hold a backtick");
        String shortRun = "``not a fence\n" + START + "\n";
        assertEquals(shortRun.indexOf(START), MarkerLines.indexOfBlockStart(shortRun, START));
        String indented = "   " + FENCE + "\n" + START + "\n" + FENCE + "\n" + START + "\n";
        assertEquals(indented.lastIndexOf(START), MarkerLines.indexOfBlockStart(indented, START),
            "a fence line is read stripped");
    }

    // ---------------------------------------------------------------- broken pairs

    @Test
    @DisplayName("a pair is broken when the start has no end after it, or an end has no start")
    void brokenPairs() {
        assertTrue(MarkerLines.hasBrokenMarkers(START + "\nbody\n", START, END));
        assertTrue(MarkerLines.hasBrokenMarkers("hand\n" + END + "\n", START, END));
        assertTrue(MarkerLines.hasBrokenMarkers(END + "\n" + START + "\n", START, END),
            "an end above the start does not close it");
        assertFalse(MarkerLines.hasBrokenMarkers(START + "\nbody\n" + END + "\n", START, END));
        assertFalse(MarkerLines.hasBrokenMarkers("no markers at all\n", START, END));
        assertFalse(MarkerLines.hasBrokenMarkers("Quote `" + START + "` in prose\n", START, END));
        assertFalse(MarkerLines.hasBrokenMarkers(FENCE + "\n" + START + "\n" + FENCE + "\n", START, END),
            "a lone start inside a fence is an example, not a broken block");
    }

    // ---------------------------------------------------------------- neutralising a body

    @Test
    @DisplayName("a marker on its own line inside a body is defused with a zero-width space; prose is left alone")
    void markersInABodyAreDefused() {
        String body = "reason one\n" + END + "\nsee " + END + " in prose\n  " + START + "\n";
        String out = MarkerLines.neutraliseMarkers(body, new String[] {START, END});

        assertEquals("reason one\n<!--\u200B" + END.substring(4) + "\nsee " + END + " in prose\n  <!--\u200B"
            + START.substring(4) + "\n", out);
        assertEquals(-1, MarkerLines.indexOfMarkerLine(out, START, 0));
        assertEquals(-1, MarkerLines.indexOfMarkerLine(out, END, 0));
    }

    @Test
    @DisplayName("hash markers are defused after the hash, and a body without markers comes back as it was")
    void hashMarkersAreDefused() {
        String start = GuardrailFileWriter.MARKER_START_HASH;
        String end = GuardrailFileWriter.MARKER_END_HASH;
        String body = start + "\nrule\n" + start + "\n";

        assertEquals("#\u200B" + start.substring(1) + "\nrule\n#\u200B" + start.substring(1) + "\n",
            MarkerLines.neutraliseMarkers(body, new String[] {start, end}));
        String plain = "nothing to defuse\n";
        assertEquals(plain, MarkerLines.neutraliseMarkers(plain, new String[] {start, end}));
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
        assertEquals(fm.length(), FrontMatter.end(fm + "\nbody"));
        assertEquals(7, FrontMatter.end("---\n---\n"), "an empty header is still one");
        assertEquals(9, FrontMatter.end("---  \n---\nbody"), "the opener may carry trailing blanks");
        assertEquals(16, FrontMatter.end("---\r\na: 1\r\n  ---  \r\nbody"),
            "CRLF, and a closer with whitespace around it: just past its dashes, not its line");
    }

    @Test
    @DisplayName("a horizontal rule, a --- inside a value or an unclosed header is not front matter")
    void whatIsNotFrontMatter() {
        assertEquals(-1, FrontMatter.end(null));
        assertEquals(-1, FrontMatter.end("body\n---\n"));
        assertEquals(-1, FrontMatter.end("---"), "no line after the opener");
        assertEquals(-1, FrontMatter.end("--- title\na: 1\n---\n"), "text after the opener");
        assertEquals(-1, FrontMatter.end("---\nplain prose line\n---\n"), "a line that is not YAML");
        assertEquals(-1, FrontMatter.end("---\n9key: 1\n---\n"), "a key must start with a letter");
        assertEquals(-1, FrontMatter.end("---\n: 1\n---\n"), "a colon with no key");
        assertEquals(-1, FrontMatter.end("---\na: 1\nb: 2\n"), "no closing fence");
        assertEquals(-1, FrontMatter.end("---\na: value with --- inside\n"),
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
