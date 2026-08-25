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
 * <p>These three merges exist because concatenating two modules' YAML, TOML or JSON produces a
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

    @Test
    void keyedYamlMergesBucketByBucketRatherThanAppendingWholeBlocks() {
        // Plandex's guardrails hold locked:/audit:/privacy:. Appending two modules' blocks would
        // repeat every key they share, which is the duplicate-key defect one level further in.
        YamlMergeShape shape = YamlMergeShape.keyed("guardrails:", 2);
        String merged = shape.merge(docs(
            "core", "guardrails:\n  locked:\n    - core.A\n  audit:\n    - core.B\n",
            "app", "guardrails:\n  locked:\n    - app.A\n"), START, END);

        assertNotNull(merged);
        assertEquals(1, countOf(merged, "locked:"), "locked: must appear once: " + merged);
        assertEquals(1, countOf(merged, "audit:"), "audit: must appear once: " + merged);
        assertTrue(merged.indexOf("core.A") < merged.indexOf("core.B"),
            "buckets keep their first-seen order: " + merged);
        assertTrue(merged.contains("app.A"), merged);
    }

    @Test
    void keyedYamlFallsBackWhenAChunkDoesNotDecomposeIntoBuckets() {
        // Writing the part it could place and dropping the rest is the one outcome not allowed.
        YamlMergeShape shape = YamlMergeShape.keyed("guardrails:", 2);
        assertNull(shape.merge(docs(
            "core", "guardrails:\n  locked:\n    - core.A\n",
            "app", "guardrails:\n    - a bare sequence, not a keyed block\n"), START, END));
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

    // -----------------------------------------------------------------------
    // JsonRulesMerge
    // -----------------------------------------------------------------------

    @Test
    void jsonFallsBackOnADocumentItCannotDecompose() {
        assertNull(JsonRulesMerge.INSTANCE.merge(docs(
            "core", "{\"rules\": \"not an array of sections\"}")));
    }

    @Test
    void jsonWithNothingToMergeEmitsTheEmptyDocumentRatherThanFallingBack() {
        // The empty rules object is a valid document and is exactly what the renderer emits for an
        // empty model, so there is nothing here for a fallback to improve on.
        String merged = JsonRulesMerge.INSTANCE.merge(List.of());
        assertNotNull(merged);
        assertTrue(merged.contains("\"rules\""), merged);
        assertTrue(merged.strip().endsWith("}"), "must still be a closed JSON document: " + merged);
    }

    @Test
    void jsonMergesTheSameKeyFromTwoModulesIntoOneArrayWithoutDuplicates() {
        // Two modules that both lock the same shared class would otherwise produce the key twice —
        // an object with a duplicate key, which most readers resolve by keeping only the last one.
        String core = """
            {
              "_generated_by": "VibeTags",
              "rules": {
                  "locked": [
                    "com.example.Shared",
                    "com.example.Core"
                  ]
              }
            }
            """;
        String app = """
            {
              "_generated_by": "VibeTags",
              "rules": {
                  "locked": [
                    "com.example.Shared"
                  ],
                  "audit": [
                    "com.example.App"
                  ]
              }
            }
            """;

        String merged = JsonRulesMerge.INSTANCE.merge(docs("core", core, "app", app));
        assertNotNull(merged, "both documents are the shape the renderer emits, so this must merge");
        assertEquals(1, countOf(merged, "\"locked\""), "locked must appear once: " + merged);
        assertEquals(1, countOf(merged, "\"audit\""), "audit must appear once: " + merged);
        assertEquals(1, countOf(merged, "com.example.Shared"),
            "a class locked by both modules must be listed once: " + merged);
        assertTrue(merged.contains("com.example.Core") && merged.contains("com.example.App"), merged);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
