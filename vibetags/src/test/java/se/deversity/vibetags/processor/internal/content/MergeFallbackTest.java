package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the structured merges do when a module's document is not the shape they were written for.
 *
 * <p>These merges exist because concatenating two modules' YAML or TOML produces a
 * document with duplicate keys — legal text, unusable configuration. Each therefore takes the
 * document apart and reassembles it, which means each has to answer the question "and if it does
 * not take apart the way I expect?".
 *
 * <p>The required answer is {@code null}, meaning <em>fall back to the caller's plain
 * concatenation</em>. That is the load-bearing part: the merge is an improvement on concatenation,
 * not a replacement for it, so a shape it cannot parse must leave the caller with the old behaviour
 * rather than with a document this code guessed at. A merge that returned a partial result instead
 * would silently drop whichever module it failed to place, which is the failure mode nobody sees
 * until an agent acts on the half of the guardrails that survived.
 */
class MergeFallbackTest {

    private static final UnaryOperator<String> START = id -> "# <<< " + id;
    private static final UnaryOperator<String> END = id -> "# >>> " + id;

    private static List<Map.Entry<String, String>> docs(String... moduleAndDocument) {
        List<Map.Entry<String, String>> out = new java.util.ArrayList<>();
        for (int i = 0; i < moduleAndDocument.length; i += 2) {
            out.add(Map.entry(moduleAndDocument[i], moduleAndDocument[i + 1]));
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // YamlMergeShape
    // -----------------------------------------------------------------------

    @Test
    void yamlAppendsEveryModulesEntriesUnderOneAnchor() {
        YamlMergeShape shape = YamlMergeShape.appended("rules:", 2, "[]");
        String merged = shape.merge(docs(
            "core", "version: 1\nrules:\n  - core rule\n",
            "app", "version: 1\nrules:\n  - app rule\n"), START, END);

        assertNotNull(merged);
        assertEquals(1, countOf(merged, "rules:"),
            "the anchor must appear once, not once per module: " + merged);
        assertTrue(merged.contains("core rule") && merged.contains("app rule"), merged);
        assertTrue(merged.contains("# <<< core") && merged.contains("# >>> app"),
            "each module's contribution must be wrapped in its own sub-markers: " + merged);
    }

    @Test
    void yamlFallsBackWhenAContributionLacksTheAnchor() {
        // The shape is a description of what the renderer emits. If a renderer changes and the
        // anchor no longer appears, this merge is describing a document that no longer exists.
        YamlMergeShape shape = YamlMergeShape.appended("rules:", 2, "[]");
        assertNull(shape.merge(docs(
            "core", "version: 1\nrules:\n  - core rule\n",
            "app", "version: 1\nguidelines:\n  - app rule\n"), START, END));
    }

    @Test
    void yamlFallsBackWhenThereAreNoContributionsAtAll() {
        assertNull(YamlMergeShape.appended("rules:", 2, "[]").merge(List.of(), START, END));
    }

    @Test
    void yamlEmitsThePlaceholderAloneWhenNoModuleContributed() {
        // 'rules:' cannot hold both '[]' and a block sequence, so an empty-body contribution is
        // dropped rather than appended — but the document still has to be valid YAML on its own.
        YamlMergeShape shape = YamlMergeShape.appended("rules:", 2, "[]");
        String merged = shape.merge(docs(
            "core", "version: 1\nrules:\n[]\n",
            "app", "version: 1\nrules:\n[]\n"), START, END);

        assertEquals("version: 1\nrules:\n[]", merged);
    }

    @Test
    void yamlWithNoPlaceholderEmitsBareScaffoldWhenNoModuleContributed() {
        YamlMergeShape shape = YamlMergeShape.appended("rules:", 2, "");
        assertEquals("version: 1\nrules:",
            shape.merge(docs("core", "version: 1\nrules:\n\n"), START, END));
    }

    @Test
    void yamlDropsTheModulesThatSaidNothingAndKeepsTheOneThatDid() {
        YamlMergeShape shape = YamlMergeShape.appended("rules:", 2, "[]");
        String merged = shape.merge(docs(
            "quiet", "version: 1\nrules:\n[]\n",
            "loud", "version: 1\nrules:\n  - a real rule\n"), START, END);

        assertNotNull(merged);
        assertTrue(merged.contains("a real rule"), merged);
        assertTrue(!merged.contains("quiet"),
            "a module with nothing to say must not leave an empty marker pair behind: " + merged);
    }

    // -----------------------------------------------------------------------
    // TomlInstructionsMerge
    // -----------------------------------------------------------------------

    @Test
    void tomlFallsBackOnADocumentWithNoBodyDelimiters() {
        assertNull(TomlInstructionsMerge.INSTANCE.merge(docs(
            "core", "not a toml document at all\n")));
    }

    @Test
    void tomlFallsBackWhenThereAreNoContributions() {
        assertNull(TomlInstructionsMerge.INSTANCE.merge(List.of()));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
