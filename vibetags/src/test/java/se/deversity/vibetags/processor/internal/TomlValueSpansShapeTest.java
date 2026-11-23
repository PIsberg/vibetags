package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TomlValueSpans#merge} byte for byte, over the shapes a hand-written {@code .pr_agent.toml}
 * comes in.
 *
 * <p>{@code TomlValueSpansTest} tells the story of the feature: what is added, what is kept, what is
 * refused. This holds the whole document for each shape, and that merging the result again changes
 * nothing, because the file belongs to the user: a byte that moves is a diff in their repository,
 * and a merge that is not a fixed point grows their file on every build. The expected documents
 * were recorded from the implementation before it was restructured, not written by hand.
 */
class TomlValueSpansShapeTest {

    private static final String BODY = "Enforce the following VibeTags guardrails:\n- `com.example.Ledger` is locked";
    private static final String SPAN = "<!-- VIBETAGS-START -->\n" + BODY + "\n<!-- VIBETAGS-END -->";
    private static final String SPAN_CRLF = SPAN.replace("\n", "\r\n");

    private static void merges(String existing, String expected) {
        String merged = TomlValueSpans.merge(existing, BODY).document();
        assertEquals(expected, merged);
        assertEquals(expected, TomlValueSpans.merge(expected, BODY).document(), "a second merge changes nothing");
    }

    private static void refuses(String existing, String reason, String detail) {
        TomlValueSpans.Outcome outcome = TomlValueSpans.merge(existing, BODY);
        assertNull(outcome.document(), "nothing may be written");
        assertEquals(reason, outcome.skipReason());
        assertEquals(detail, outcome.detail());
    }

    @Test
    @DisplayName("a file with neither table gets both, after what is there")
    void tablesAreAddedAfterWhatIsThere() {
        merges("",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("\n",
            "\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("# only a comment",
            "# only a comment\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("# only a comment\n",
            "# only a comment\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("title = \"x\"",
            "title = \"x\"\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
    }

    @Test
    @DisplayName("a table that is there gets the key under its header, whatever the header looks like")
    void theKeyGoesUnderItsHeader() {
        merges("[pr_reviewer]",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nnum = 3\n\n[pr_code_suggestions]\nx = true\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\nnum = 3\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\nx = true\n");
        merges("[pr_code_suggestions]\nx = 1\n[pr_reviewer]\ny = 2\n",
            "[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\nx = 1\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\ny = 2\n");
        merges("[pr_reviewer] # the reviewer\nnum = 3\n",
            "[pr_reviewer] # the reviewer\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\nnum = 3\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("  [ pr_reviewer ]  \nnum = 3\n",
            "  [ pr_reviewer ]  \nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\nnum = 3\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("\uFEFF[pr_reviewer]\nnum = 3\n",
            "\uFEFF[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\nnum = 3\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
    }

    @Test
    @DisplayName("a file with Windows line endings is merged with Windows line endings")
    void windowsLineEndingsAreKept() {
        merges("[pr_reviewer]\r\nnum = 3\r\n\r\n[pr_code_suggestions]\r\nx = true\r\n",
            "[pr_reviewer]\r\nextra_instructions = \"\"\"\r\n"
                + SPAN_CRLF
                + "\r\n\"\"\"\r\nnum = 3\r\n\r\n[pr_code_suggestions]\r\nextra_instructions = \"\"\"\r\n"
                + SPAN_CRLF
                + "\r\n\"\"\"\r\nx = true\r\n");
    }

    @Test
    @DisplayName("an existing string keeps the developer's text first, with the span after one blank line")
    void theDevelopersTextStaysFirst() {
        merges("[\"pr_reviewer\"]\n\"extra_instructions\" = \"\"\"Mine.\"\"\"\n",
            "[\"pr_reviewer\"]\n\"extra_instructions\" = \"\"\"Mine.\n\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("['pr_reviewer']\n  extra_instructions   =   \"\"\"\nMine.\n\"\"\"\n",
            "['pr_reviewer']\n  extra_instructions   =   \"\"\"\nMine.\n\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\nMine.\n\n\n\"\"\"\nafter = 1\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\nMine.\n\n"
                + SPAN
                + "\n\"\"\"\nafter = 1\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\\\n  Be careful.\\\n  Really.\n\"\"\"\n[pr_code_suggestions]\nextra_instructions = \"\"\"x\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\\\n  Be careful.\\\n  Really.\n\n"
                + SPAN
                + "\n\"\"\"\n[pr_code_suggestions]\nextra_instructions = \"\"\"x\n\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"say \"hi\"\"\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"say \"hi\"\"\n\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"an escaped \\\"\"\" delimiter\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"an escaped \\\"\"\" delimiter\n\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\nprose mentioning <!-- VIBETAGS-START --> inline\n\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\nprose mentioning <!-- VIBETAGS-START --> inline\n\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
    }

    @Test
    @DisplayName("an empty or blank string becomes the span alone")
    void aBlankStringBecomesTheSpan() {
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\n\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\n   \n\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
    }

    @Test
    @DisplayName("a span already there is replaced where it stands, and text around it stays")
    void anExistingSpanIsReplacedInPlace() {
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\nMine.\n<!-- VIBETAGS-START -->\nOLD\n<!-- VIBETAGS-END -->\nTail.\n\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\nMine.\n"
                + SPAN
                + "\nTail.\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\n<!-- VIBETAGS-START -->\nOLD\n<!-- VIBETAGS-END -->\n\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions = \"\"\"\n  <!-- VIBETAGS-START -->  \nOLD\n\t<!-- VIBETAGS-END -->\n\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n  "
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
    }

    @Test
    @DisplayName("only the table itself counts: not a longer name, a sub-table, or a bracket line inside a value or comment")
    void onlyTheTableItselfCounts() {
        merges("[[pr_reviewer_other]]\na = 1\n",
            "[[pr_reviewer_other]]\na = 1\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer.sub]\nx = 1\n",
            "[pr_reviewer.sub]\nx = 1\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\na = 1\n[pr_reviewer.sub]\nextra_instructions = \"\"\"sub\"\"\"\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\na = 1\n[pr_reviewer.sub]\nextra_instructions = \"\"\"sub\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer_extra]\nextra_instructions = \"\"\"other table\"\"\"\n",
            "[pr_reviewer_extra]\nextra_instructions = \"\"\"other table\"\"\"\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[other]\nnotes = \"\"\"\n[pr_reviewer]\nextra_instructions = \"in a string\"\n\"\"\"\n",
            "[other]\nnotes = \"\"\"\n[pr_reviewer]\nextra_instructions = \"in a string\"\n\"\"\"\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[other]\nnotes = '''\n[pr_reviewer]\n'''\nlist = [\n  [\"pr_reviewer\"],\n  \"x\",\n]\n",
            "[other]\nnotes = '''\n[pr_reviewer]\n'''\nlist = [\n  [\"pr_reviewer\"],\n  \"x\",\n]\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[other]\ns = \"a # not a comment [\" # [pr_reviewer]\nt = 'it''s [x'\n[pr_reviewer]\nn = 1\n",
            "[other]\ns = \"a # not a comment [\" # [pr_reviewer]\nt = 'it''s [x'\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\nn = 1\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("# [pr_reviewer]\n#[pr_code_suggestions]\n",
            "# [pr_reviewer]\n#[pr_code_suggestions]\n\n[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
        merges("[pr_reviewer]\nextra_instructions_more = 1\nnot_extra_instructions = 2\n",
            "[pr_reviewer]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\nextra_instructions_more = 1\nnot_extra_instructions = 2\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n"
                + SPAN
                + "\n\"\"\"\n");
    }

    @Test
    @DisplayName("what cannot be merged without guessing is refused, with a reason for the log and a detail for the warning")
    void whatCannotBeMergedIsRefused() {
        refuses("[pr_reviewer]\nextra_instructions = \"\"\"\n<!-- VIBETAGS-START -->\nno end\n\"\"\"\n",
            "unclosed-span", "extra_instructions in [pr_reviewer] has a VIBETAGS-START line with no VIBETAGS-END after it");
        refuses("[pr_reviewer]\nextra_instructions = \"\"\"\nnever closed\n",
            "unclosed-string", "extra_instructions in [pr_reviewer] has no closing \"\"\"");
        refuses("[pr_reviewer]\nextra_instructions = \"one line\"\n",
            "not-multiline-string", "extra_instructions in [pr_reviewer] is not a \"\"\" string VibeTags can add to");
        refuses("[pr_reviewer]\nextra_instructions = '''\nliteral\n'''\n",
            "not-multiline-string", "extra_instructions in [pr_reviewer] is not a \"\"\" string VibeTags can add to");
        refuses("[pr_reviewer]\nextra_instructions = 3\n",
            "not-multiline-string", "extra_instructions in [pr_reviewer] is not a \"\"\" string VibeTags can add to");
        refuses("[pr_reviewer]\nextra_instructions = \"\"\"a\"\"\"\nextra_instructions = \"\"\"b\"\"\"\n",
            "duplicate-key", "[pr_reviewer] sets extra_instructions twice");
        refuses("[pr_reviewer]\na = 1\n[pr_reviewer]\nb = 2\n",
            "duplicate-table", "[pr_reviewer] is defined twice");
        refuses("[[pr_reviewer]]\na = 1\n",
            "array-of-tables", "[[pr_reviewer]] is an array of tables");
        refuses("pr_reviewer.extra_instructions = \"\"\"x\"\"\"\n",
            "dotted-key", "pr_reviewer is set with a dotted key or an inline table");
        refuses("pr_reviewer = { extra_instructions = \"x\" }\n",
            "dotted-key", "pr_reviewer is set with a dotted key or an inline table");
    }
}
