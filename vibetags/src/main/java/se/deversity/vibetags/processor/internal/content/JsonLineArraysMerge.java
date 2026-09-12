package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges a flat object of string arrays across modules by unioning the lines inside each key.
 *
 * <p>The document is the fixed shape {@code GreptileRenderer} produces, which is not the file on disk
 * but the part of it VibeTags owns:
 *
 * <pre>
 * {
 *   "instructions": [
 *     "Enforce the following VibeTags guardrails. Flag any change that violates them:",
 *     "- ..."
 *   ],
 *   "ignorePatterns": []
 * }
 * </pre>
 *
 * <p>Each entry is one already-escaped JSON string literal on its own line, and an escaped literal
 * can never contain a raw newline, so the document is read line by line without decoding anything.
 * The union compares the escaped text, which is exact because escaping is deterministic: the same
 * guardrail rendered by two modules is the same bytes, and collapses to one line.
 *
 * <p>A module with nothing to say renders the placeholder line. It is dropped when any other module
 * contributed, and kept when none did, the same rule {@link TomlInstructionsMerge} applies.
 *
 * <p>Re-emission reproduces the renderer's layout byte for byte, so a single-module build is
 * unchanged by this class. {@code MultiModuleWholeFileMergeTest} asserts that equality.
 */
final class JsonLineArraysMerge implements WholeFileMerge {

    private final String placeholderLiteral;

    /**
     * @param placeholder the line a module with nothing to say renders. A body's opening sentence
     *                    needs no parameter of its own: every populated module renders the same one
     *                    first, so first-seen order keeps it at the top and de-duplication keeps it
     *                    once.
     */
    JsonLineArraysMerge(String placeholder) {
        this.placeholderLiteral = '"' + Escape.json(placeholder) + '"';
    }

    @Override
    public @Nullable String merge(List<Map.Entry<String, String>> contributions) {
        Map<String, Set<String>> byKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> contribution : contributions) {
            Map<String, List<String>> arrays = parse(contribution.getValue());
            if (arrays == null) {
                return null; // not the shape this understands; caller keeps the old behaviour
            }
            arrays.forEach((key, lines) ->
                byKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(lines));
        }
        if (byKey.isEmpty()) {
            return null;
        }
        byKey.values().forEach(lines -> {
            if (lines.size() > 1) {
                lines.remove(placeholderLiteral);
            }
        });

        StringBuilder out = new StringBuilder(1024).append("{\n");
        List<String> keys = new ArrayList<>(byKey.keySet());
        for (int k = 0; k < keys.size(); k++) {
            out.append("  \"").append(keys.get(k)).append("\": [");
            List<String> lines = new ArrayList<>(byKey.get(keys.get(k)));
            if (lines.isEmpty()) {
                out.append(']');
            } else {
                out.append('\n');
                for (int i = 0; i < lines.size(); i++) {
                    out.append("    ").append(lines.get(i)).append(i == lines.size() - 1 ? "\n" : ",\n");
                }
                out.append("  ]");
            }
            out.append(k == keys.size() - 1 ? "\n" : ",\n");
        }
        return out.append("}\n").toString();
    }

    /**
     * Reads key → escaped string literals (quotes included, separator commas removed).
     *
     * @return {@code null} when the document is not the expected shape
     */
    private static @Nullable Map<String, List<String>> parse(String json) {
        String[] rows = json.split("\n", -1);
        if (rows.length == 0 || !"{".equals(rows[0])) {
            return null;
        }
        Map<String, List<String>> arrays = new LinkedHashMap<>();
        String current = null;
        List<String> lines = new ArrayList<>();
        boolean closed = false;
        for (int r = 1; r < rows.length; r++) {
            String row = rows[r];
            if (closed) {
                if (!row.isEmpty()) {
                    return null;
                }
                continue;
            }
            if (current == null) {
                if ("}".equals(row)) {
                    closed = true;
                    continue;
                }
                String key = keyOf(row);
                if (key == null) {
                    return null;
                }
                String rest = row.substring(("  \"" + key + "\": [").length());
                if ("]".equals(rest) || "],".equals(rest)) {
                    arrays.put(key, new ArrayList<>());
                } else if (rest.isEmpty()) {
                    current = key;
                    lines = new ArrayList<>();
                } else {
                    return null;
                }
                continue;
            }
            if ("  ]".equals(row) || "  ],".equals(row)) {
                arrays.put(current, lines);
                current = null;
                continue;
            }
            if (!row.startsWith("    \"")) {
                return null;
            }
            String literal = row.substring(4);
            if (literal.endsWith(",")) {
                literal = literal.substring(0, literal.length() - 1);
            }
            if (literal.length() < 2 || !literal.endsWith("\"")) {
                return null;
            }
            lines.add(literal);
        }
        return closed && current == null ? arrays : null;
    }

    /** The key of a {@code   "key": [} row, or {@code null} if the row is not one. */
    private static @Nullable String keyOf(String row) {
        if (!row.startsWith("  \"")) {
            return null;
        }
        int end = row.indexOf("\": [", 3);
        if (end < 0) {
            return null;
        }
        String key = row.substring(3, end);
        for (int i = 0; i < key.length(); i++) {
            if (!Character.isLetterOrDigit(key.charAt(i)) && key.charAt(i) != '_') {
                return null;
            }
        }
        return key.isEmpty() ? null : key;
    }
}
