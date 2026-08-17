package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges {@code .mentatconfig.json} across modules by unioning each rules array inside its own key.
 *
 * <p>The document is a fixed shape produced by {@code MentatRenderer} a few lines from here:
 *
 * <pre>
 * {
 *   "_generated_by": "VibeTags",
 *   "rules": {
 *     "locked_files": [
 *     {"path": "...", "reason": "..."}
 *     ],
 *     "audit": [
 *     {"path": "..."}
 *     ]
 *   }
 * }
 * </pre>
 *
 * <p>Concatenating two of those is not JSON, so the merge works on the arrays: keys in first-seen
 * order, entries in first-seen order, exact duplicates collapsed. Two modules that both lock
 * {@code com.example.Shared} — which happens when a type is annotated in a module every other one
 * depends on — should produce one entry, not two.
 *
 * <p>Re-emission reproduces the renderer's layout byte for byte, so a single-module build is
 * unchanged by the existence of this class. {@code MultiModuleWholeFileMergeTest} asserts that
 * equality rather than trusting it.
 */
final class JsonRulesMerge implements WholeFileMerge {

    /** Stateless, so one instance serves every call — see {@link WholeFileMerge#jsonRules()}. */
    static final JsonRulesMerge INSTANCE = new JsonRulesMerge();

    private static final String HEADER = "{\n  \"_generated_by\": \"VibeTags\",\n  \"rules\": {\n";
    private static final String FOOTER = "  }\n}\n";

    @Override
    public @Nullable String merge(List<Map.Entry<String, String>> contributions) {
        // Key -> entries, both in first-seen order. A LinkedHashSet per key does the de-duplication
        // and the ordering in one structure.
        Map<String, Set<String>> byKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> contribution : contributions) {
            Map<String, List<String>> sections = parseRuleArrays(contribution.getValue());
            if (sections == null) {
                return null; // not the shape this understands; caller keeps the old behaviour
            }
            sections.forEach((key, entries) ->
                byKey.computeIfAbsent(key, k -> new LinkedHashSet<>()).addAll(entries));
        }
        if (byKey.isEmpty()) {
            // Every module rendered an empty rules object. That is a valid document and the
            // renderer's own output for an empty model, so emit exactly that.
            return HEADER + FOOTER;
        }

        StringBuilder out = new StringBuilder(1024).append(HEADER);
        List<String> keys = new ArrayList<>(byKey.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            out.append("    \"").append(key).append("\": [\n");
            List<String> entries = new ArrayList<>(byKey.get(key));
            for (int e = 0; e < entries.size(); e++) {
                out.append(entries.get(e))
                    .append(e == entries.size() - 1 ? "\n" : ",\n");
            }
            out.append("    ]").append(i == keys.size() - 1 ? "\n" : ",\n");
        }
        return out.append(FOOTER).toString();
    }

    /**
     * Reads the {@code "rules"} object into key → entry lines, with the separator commas removed so
     * re-emission can put them back where they belong.
     *
     * @return {@code null} when the document is not the expected shape
     */
    private static @Nullable Map<String, List<String>> parseRuleArrays(String json) {
        if (!json.startsWith(HEADER)) {
            return null;
        }
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = null;
        List<String> entries = new ArrayList<>();

        for (String line : json.substring(HEADER.length()).split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || "}".equals(trimmed)) {
                continue;
            }
            if (current == null) {
                if ("}".equals(trimmed) || "  }".equals(line)) {
                    continue;
                }
                int colon = trimmed.indexOf("\": [");
                if (!trimmed.startsWith("\"") || colon < 0) {
                    // Only the closing brace of "rules" may appear outside a section.
                    return trimmed.equals("}") ? sections : null;
                }
                current = trimmed.substring(1, colon);
                entries = new ArrayList<>();
                continue;
            }
            if ("]".equals(trimmed) || "],".equals(trimmed)) {
                sections.put(current, entries);
                current = null;
                continue;
            }
            // An entry line; the trailing separator comma is re-added on re-emission.
            String entry = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
            entries.add(entry);
        }
        return current == null ? sections : null;
    }
}
