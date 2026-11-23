package se.deversity.vibetags.processor.internal.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The reactor merge of {@code greptile.json}'s owned arrays, compared whole: the union, the
 * placeholder rule, the layout, and every shape the line reader refuses so the caller keeps one
 * module's rendering. Until this test the reader's refusals were reached only through whole
 * multi-module builds, which only ever hand it the renderer's own shape. Written before the reader
 * was split into steps.
 */
class JsonLineArraysMergeTest {

    private static final String EMPTY = "No VibeTags guardrails are currently declared.";
    private static final WholeFileMerge MERGE = WholeFileMerge.jsonLineArrays(EMPTY);

    private static String doc(List<String> instructions, List<String> ignores) {
        return "{\n" + array("instructions", instructions) + ",\n" + array("ignorePatterns", ignores) + "\n}\n";
    }

    private static String array(String key, List<String> lines) {
        if (lines.isEmpty()) {
            return "  \"" + key + "\": []";
        }
        StringBuilder sb = new StringBuilder("  \"" + key + "\": [\n");
        for (int i = 0; i < lines.size(); i++) {
            sb.append("    \"").append(lines.get(i)).append('\"').append(i == lines.size() - 1 ? "\n" : ",\n");
        }
        return sb.append("  ]").toString();
    }

    private static String merge(String... documents) {
        List<Map.Entry<String, String>> contributions = new ArrayList<>();
        for (int i = 0; i < documents.length; i++) {
            contributions.add(new AbstractMap.SimpleEntry<>("m" + i, documents[i]));
        }
        return MERGE.merge(contributions);
    }

    @Test
    void oneDocumentIsWrittenBackByteForByte() {
        String one = doc(List.of("Enforce:", "- a"), List.of("**/Gen.java"));
        assertEquals(one, merge(one));
    }

    @Test
    void linesAreUnionedPerKeyInFirstSeenOrder() {
        assertEquals(doc(List.of("Enforce:", "- a", "- b"), List.of("**/A.java", "**/B.java")),
            merge(doc(List.of("Enforce:", "- a"), List.of("**/A.java")),
                doc(List.of("Enforce:", "- b", "- a"), List.of("**/B.java", "**/A.java"))));
    }

    @Test
    void thePlaceholderGoesWhenAnyModuleSaysSomething() {
        assertEquals(doc(List.of("Enforce:", "- b"), List.of()),
            merge(doc(List.of(EMPTY), List.of()), doc(List.of("Enforce:", "- b"), List.of())));
    }

    @Test
    void thePlaceholderStaysOnceWhenNoModuleSaysAnything() {
        assertEquals(doc(List.of(EMPTY), List.of()), merge(doc(List.of(EMPTY), List.of()), doc(List.of(EMPTY), List.of())));
    }

    @Test
    void anArrayEveryModuleLeftEmptyStaysEmpty() {
        String empty = doc(List.of("Enforce:", "- a"), List.of());
        assertEquals(empty, merge(empty, empty));
    }

    @Test
    void blankLinesAfterTheClosingBraceAreAccepted() {
        String one = doc(List.of("- a"), List.of());
        assertEquals(one, merge(one + "\n\n"));
    }

    @Test
    void aTrailingCommaAfterTheLastArrayIsAccepted() {
        String withComma = "{\n  \"instructions\": [\n    \"- a\"\n  ],\n}\n";
        assertEquals("{\n  \"instructions\": [\n    \"- a\"\n  ]\n}\n", merge(withComma));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "not an object\n",
        "{\n  \"in-structions\": []\n}\n",
        "{\n  \"\": []\n}\n",
        "{\n  \"instructions\": [1]\n}\n",
        "{\n  \"instructions\": [\n    1\n  ]\n}\n",
        "{\n  \"instructions\": [\n    \"unclosed\n  ]\n}\n",
        "{\n  \"instructions\": [\n    \"- a\"\n}\n",
        "{\n  \"instructions\": []\n}\ntrailing\n",
        "{\n  \"instructions\": []\n",
        "{\n  instructions: []\n}\n",
        "",
    })
    void aShapeTheRendererNeverWritesIsRefused(String document) {
        assertNull(merge(document, doc(List.of("- a"), List.of())), document);
    }

    @Test
    void aDocumentWithNoKeysIsRefused() {
        assertNull(merge("{\n}\n"));
    }
}
