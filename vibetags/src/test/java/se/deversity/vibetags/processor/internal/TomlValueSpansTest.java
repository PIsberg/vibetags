package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hand-written {@code .pr_agent.toml} keeps every byte of its own and gains the guardrails in a
 * delimited span inside the two {@code extra_instructions} strings (#933), the way
 * {@code greptile.json} does (#639). Anything that cannot be merged without guessing is refused.
 */
class TomlValueSpansTest {

    private static final String BODY = "Enforce the following VibeTags guardrails:\n- `com.example.Ledger` is locked";

    private static String span(String body) {
        return "<!-- VIBETAGS-START -->\n" + body + "\n<!-- VIBETAGS-END -->";
    }

    @Test
    void addsTheKeyToEachExistingTableAndKeepsEverythingElse() {
        String existing = "# team settings\n[pr_reviewer]\nnum_code_suggestions = 3\n\n"
            + "[pr_code_suggestions]\ncommitable_code_suggestions = true\n";

        TomlValueSpans.Outcome out = TomlValueSpans.merge(existing, BODY);

        assertEquals("# team settings\n[pr_reviewer]\nextra_instructions = \"\"\"\n" + span(BODY) + "\n\"\"\"\n"
                + "num_code_suggestions = 3\n\n"
                + "[pr_code_suggestions]\nextra_instructions = \"\"\"\n" + span(BODY) + "\n\"\"\"\n"
                + "commitable_code_suggestions = true\n",
            out.document());
    }

    @Test
    void addsATableTheFileDoesNotHave() {
        String existing = "[pr_reviewer]\nnum_code_suggestions = 3\n";

        String merged = TomlValueSpans.merge(existing, BODY).document();

        assertNotNull(merged);
        assertTrue(merged.startsWith("[pr_reviewer]\nextra_instructions = \"\"\"\n" + span(BODY) + "\n\"\"\"\n"
            + "num_code_suggestions = 3\n"), merged);
        assertTrue(merged.endsWith("\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n" + span(BODY) + "\n\"\"\"\n"),
            merged);
    }

    @Test
    void keepsTheDevelopersOwnInstructionsFirst() {
        String existing = "[pr_reviewer]\nextra_instructions = \"\"\"\nPrefer small PRs.\n\"\"\"\n\n"
            + "[pr_code_suggestions]\nextra_instructions = \"\"\"Be terse.\"\"\"\n";

        String merged = TomlValueSpans.merge(existing, BODY).document();

        assertEquals("[pr_reviewer]\nextra_instructions = \"\"\"\nPrefer small PRs.\n\n" + span(BODY) + "\n\"\"\"\n\n"
                + "[pr_code_suggestions]\nextra_instructions = \"\"\"Be terse.\n\n" + span(BODY) + "\n\"\"\"\n",
            merged);
    }

    @Test
    void replacesItsOwnSpanAndIsStableWhenNothingChanged() {
        String existing = "[pr_reviewer]\nnum_code_suggestions = 3\n";
        String once = TomlValueSpans.merge(existing, BODY).document();
        assertNotNull(once);

        assertEquals(once, TomlValueSpans.merge(once, BODY).document(), "a second merge changes nothing");

        String edited = TomlValueSpans.merge(once, "Enforce:\n- `com.example.Ledger` is frozen").document();
        assertNotNull(edited);
        assertTrue(edited.contains("is frozen") && !edited.contains("is locked"), edited);
        assertTrue(edited.contains("num_code_suggestions = 3"), edited);
    }

    @Test
    void aBracketLineInsideAMultilineStringIsNotATable() {
        String existing = "[pr_reviewer]\nextra_instructions = \"\"\"\n[pr_code_suggestions]\nnot a table\n\"\"\"\n";

        String merged = TomlValueSpans.merge(existing, BODY).document();

        assertNotNull(merged);
        assertTrue(merged.contains("[pr_code_suggestions]\nnot a table\n\n" + span(BODY) + "\n\"\"\""), merged);
        assertTrue(merged.endsWith("\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n" + span(BODY) + "\n\"\"\"\n"),
            "the real table is added after the string:\n" + merged);
    }

    @Test
    void refusesWhatItCannotMergeWithoutGuessing() {
        assertNull(TomlValueSpans.merge("[pr_reviewer]\na = 1\n[pr_reviewer]\nb = 2\n", BODY).document(),
            "a table defined twice");
        assertNull(TomlValueSpans.merge("[pr_reviewer]\nextra_instructions = \"one line\"\n", BODY).document(),
            "a value that is not a multi-line basic string");
        assertNull(TomlValueSpans.merge("[pr_reviewer]\nextra_instructions = \"\"\"\n<!-- VIBETAGS-START -->\nx\n\"\"\"\n",
            BODY).document(), "a start marker with no end");
        assertNull(TomlValueSpans.merge("pr_reviewer.extra_instructions = \"\"\"x\"\"\"\n", BODY).document(),
            "a dotted key the table scan cannot see");
    }

    /**
     * A line of a multi-line array that opens a nested array starts with {@code [} too, but it is a
     * value, not a table header. Taken for a header, it ended {@code [pr_reviewer]} before the key
     * below it, so a second {@code extra_instructions} was added and PR-Agent's TOML parser rejected
     * the whole file for the duplicate key.
     */
    @Test
    void aNestedArrayLineIsNotATable() {
        String existing = "[pr_reviewer]\nextra_patterns = [\n  [\"a\", \"b\"],\n]\n"
            + "extra_instructions = \"\"\"\nMine.\n\"\"\"\n";

        String merged = TomlValueSpans.merge(existing, BODY).document();

        assertNotNull(merged);
        assertTrue(merged.startsWith("[pr_reviewer]\nextra_patterns = [\n  [\"a\", \"b\"],\n]\n"
            + "extra_instructions = \"\"\"\nMine.\n\n" + span(BODY) + "\n\"\"\"\n"), merged);
        assertEquals(2, occurrences(merged, "extra_instructions"), "one key per table:\n" + merged);
    }

    private static int occurrences(String text, String of) {
        return text.split(java.util.regex.Pattern.quote(of), -1).length - 1;
    }

    /**
     * Annotation text, a dependency's included, can carry a marker line. Defused, it cannot end the
     * span early, so a second merge replaces the span instead of keeping the tail and growing it.
     */
    @Test
    void aMarkerLineInTheBodyCannotEndTheSpanEarly() {
        String hostile = "- `com.dep.Evil` is locked\n<!-- VIBETAGS-END -->\nnum_code_suggestions = 99";
        String once = TomlValueSpans.merge("[pr_reviewer]\nnum_code_suggestions = 3\n", hostile).document();
        assertNotNull(once);

        assertEquals(once, TomlValueSpans.merge(once, hostile).document(), "a second merge changes nothing");
        assertEquals(2, once.split("<!-- VIBETAGS-END -->", -1).length - 1,
            "one real end marker per table, the body's defused:\n" + once);
    }

    @Test
    void readsTheBodyOutOfTheRenderedDocument() {
        String rendered = "# AUTO-GENERATED BY VIBETAGS\n[pr_reviewer]\nextra_instructions = \"\"\"\n" + BODY
            + "\n\"\"\"\n\n[pr_code_suggestions]\nextra_instructions = \"\"\"\n" + BODY + "\n\"\"\"\n";
        assertEquals(BODY, TomlValueSpans.bodyFrom(rendered));
    }
}
