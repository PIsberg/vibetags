package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProcessorTestHarness#mentions} is what keeps a negative assertion on an indexed aggregate
 * meaningful (issue #839). A grouped index writes {@code com.example.bare.Layered} as
 * {@code Layered} inside {@code <elements in="com.example.bare">}, so a plain
 * {@code contains("com.example.bare.Layered")} is false whether or not the element is indexed, and
 * an {@code assertFalse} on it can no longer fail.
 */
class ProcessorTestHarnessMentionsTest {

    @Test
    void findsANameInsideAnXmlGroup() {
        String index = "    <elements in=\"com.example.bare\">Watched, Layered</elements>\n";
        assertTrue(ProcessorTestHarness.mentions(index, "com.example.bare.Layered"));
        assertTrue(ProcessorTestHarness.mentions(index, "com.example.bare.Watched"));
        assertFalse(ProcessorTestHarness.mentions(index, "com.example.bare.Layer"),
            "a member is matched whole, not as a prefix of another member");
        assertFalse(ProcessorTestHarness.mentions(index, "com.example.Layered"),
            "the group's prefix must match exactly");
    }

    @Test
    void findsANameInsideAMarkdownGroup() {
        String index = "- `com.example.a`: `Alpha`, `Beta`\n";
        assertTrue(ProcessorTestHarness.mentions(index, "com.example.a.Beta"));
        assertFalse(ProcessorTestHarness.mentions(index, "com.example.a.Gamma"));
    }

    /** A group that names its role file (#931): the pointer is not one of the names. */
    @Test
    void findsANameInAGroupThatNamesItsFile() {
        String xml = "    <elements in=\"com.example.api\" rules=\".claude/rules/api.md\">Alpha, Beta</elements>\n";
        assertTrue(ProcessorTestHarness.mentions(xml, "com.example.api.Beta"));
        assertFalse(ProcessorTestHarness.mentions(xml, "com.example.api.Gamma"));

        String md = "- `com.example.api`: `Alpha`, `Beta` → `.gemini/rules/api.md`\n";
        assertTrue(ProcessorTestHarness.mentions(md, "com.example.api.Beta"),
            "the last name is not swallowed by the trailing file pointer");
        assertFalse(ProcessorTestHarness.mentions(md, "com.example.api.Gamma"));
    }

    @Test
    void stillFindsALiteralMention() {
        assertTrue(ProcessorTestHarness.mentions("<file path=\"com.example.cli.Cli\">", "com.example.cli.Cli"));
        assertFalse(ProcessorTestHarness.mentions("nothing here", "com.example.cli.Cli"));
    }
}
