package se.deversity.vibetags.processor.internal.validation;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.GuardrailFileWriter;
import se.deversity.vibetags.processor.internal.validation.DuplicateYamlKeyRule.Finding;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DuplicateYamlKeyRule#find} compared whole: where the block is, which YAML document each
 * occurrence sits in, and which occurrence a last-wins loader reads. {@code ValidationRuleUnitTest}
 * holds the parsing edges (quoted, nested and commented keys, CRLF); these hold who wins.
 *
 * <p>Each case is a file given line by line, so a line number in an expected {@link Finding} is the
 * position of that line in the argument list, counting from 1.
 */
class DuplicateYamlKeyRuleFindTest {

    private static final String START = GuardrailFileWriter.MARKER_START_HASH;
    private static final String END = GuardrailFileWriter.MARKER_END_HASH;
    private static final Set<String> OWNED = Set.of("read", "model");

    /** The findings for a file of {@code lines}, each ended by a newline. */
    private static List<Finding> find(String... lines) {
        return DuplicateYamlKeyRule.find(String.join("\n", lines) + "\n", OWNED, START, END);
    }

    @Test
    void aKeyWrittenBelowTheBlock_isTheOneRead() {
        assertEquals(List.of(new Finding("read", 4, 2, 4)),
            find(START, "read: [a]", END, "read: [b]"));
    }

    @Test
    void aKeyWrittenAboveTheBlock_isOverriddenByTheBlock() {
        assertEquals(List.of(new Finding("read", 1, 3, 3)),
            find("read: [b]", START, "read: [a]", END));
    }

    @Test
    void aKeyWrittenTwiceAboveTheBlock_isReportedAtItsLowerLine() {
        assertEquals(List.of(new Finding("read", 2, 4, 4)),
            find("read: [x]", "read: [y]", START, "read: [a]", END));
    }

    @Test
    void aKeyWrittenOnBothSidesOfTheBlock_isReportedBelowIt() {
        assertEquals(List.of(new Finding("read", 5, 3, 5)),
            find("read: [x]", START, "read: [a]", END, "read: [y]"));
    }

    @Test
    void findingsComeInTheOrderTheHandWrittenKeysFirstAppear() {
        assertEquals(List.of(new Finding("model", 1, 5, 5), new Finding("read", 2, 4, 4)),
            find("model: m", "read: [r]", START, "read: [a]", "model: z", END));
    }

    @Test
    void aKeyTheBlockDoesNotOwn_isNeverAFinding() {
        assertEquals(List.of(), find("other: 1", START, "other: 2", END));
    }

    @Test
    void aBlockThatWritesAKeyTwice_isPlacedAtItsFirst() {
        assertEquals(List.of(new Finding("read", 5, 2, 5)),
            find(START, "read: [a]", "read: [b]", END, "read: [c]"));
    }

    @Test
    void withNoBlock_aKeyInAnEarlierDocument_isNotADuplicate_butOneInTheLastIs() {
        assertEquals(List.of(new Finding("model", 3, 0, 0)),
            find("read: [x]", "---", "model: y"));
    }

    @Test
    void withNoBlock_aTrailingSeparatorPutsTheAppendedBlockInANewDocument() {
        assertEquals(List.of(), find("read: [x]", "---"));
    }

    @Test
    void aBlockInTheSecondDocument_doesNotClashWithAKeyInTheFirst() {
        assertEquals(List.of(), find("read: [x]", "---", START, "# old", END));
    }

    @Test
    void aSeparatorWithACommentStillStartsANewDocument() {
        assertEquals(List.of(), find("read: [x]", "--- # second", START, "read: [a]", END));
    }

    @Test
    void aKeyInTheBlocksDocument_isComparedAgainstThatDocumentOnly() {
        assertEquals(List.of(new Finding("read", 3, 5, 5)),
            find("read: [x]", "---", "read: [y]", START, "read: [a]", END));
    }

    @Test
    void aBlockThatLacksTheKey_overridesAHandKeyAboveIt_whenThisBuildWritesIt() {
        assertEquals(List.of(new Finding("read", 1, 0, 0)), find("read: [x]", START, "# old", END));
    }

    @Test
    void aBlockThatLacksTheKey_isWrittenInItsOwnDocument_notTheLastOne() {
        assertEquals(List.of(new Finding("read", 1, 0, 0)),
            find("read: [x]", START, "# old", END, "---", "model: y"));
    }

    @Test
    void anUnclosedBlockThatLacksTheKey_overridesAHandKeyAboveIt() {
        assertEquals(List.of(new Finding("read", 1, 0, 0)), find("read: [x]", START, "# old"));
    }

    @Test
    void anEndMarkerBeforeTheStart_doesNotCloseAnything() {
        assertEquals(List.of(new Finding("read", 2, 4, 4)),
            find(END, "read: [x]", START, "read: [a]", END));
    }

    @Test
    void markersWithTrailingWhitespace_stillDelimitTheBlock() {
        assertEquals(List.of(new Finding("read", 4, 2, 4)),
            find(START + "  ", "read: [a]", END + "\t", "read: [b]"));
    }

    @Test
    void aSecondStartMarker_isInsideTheFirstBlock() {
        assertEquals(List.of(), find(START, "read: [a]", START, "read: [b]", END));
    }
}
