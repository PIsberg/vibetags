package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * How one platform's YAML document is combined across the modules of a reactor.
 *
 * <p>Most generated files are Markdown or ignore-file lists, and merging those is concatenation:
 * two modules' sections stacked between {@code VIBETAGS-MODULE} sub-markers read exactly as
 * intended. A YAML document is not like that. It has one {@code rules:}, one {@code reviews:}, one
 * {@code customModes:}, and stacking whole documents produces a file with the key repeated once per
 * module. A strict parser rejects that outright; a lenient one keeps only the last occurrence, so
 * every module but one loses its guardrails with nothing in the build log to say so.
 *
 * <p>A renderer that emits YAML therefore declares where its shared scaffold ends. The merge writes
 * that scaffold once and appends each module's contribution underneath it, which is the same
 * document the single-module build produces, only with more entries in it.
 *
 * <p>The declaration is deliberately the renderer's own ({@link PlatformRenderer#mergeShape()})
 * rather than a lookup table somewhere else: a table would be a twin of the renderer's output
 * format, free to drift the moment someone edits the scaffold. It is still a twin — but
 * {@code YamlMergeShapeContractTest} renders each platform and fails if the declared anchor is not
 * in the output, so the drift is caught by the build rather than by a consumer's parser.
 *
 * @param anchor     the exact line, indentation included, that ends the shared scaffold. Everything
 *                   up to and including its first occurrence is emitted once; everything after it
 *                   is a module's contribution.
 * @param indent     the column the contributions sit at. Sub-marker comments are indented to match,
 *                   which matters for the block-scalar platforms: a {@code #} line dedented below a
 *                   block scalar's indentation terminates the scalar and breaks the document.
 * @param emptyBody  what the renderer emits after {@code anchor} when it has nothing to say — an
 *                   empty collection or a placeholder sentence. Contributions equal to it are
 *                   dropped, because {@code rules:} cannot hold both {@code []} and a block
 *                   sequence; it is re-emitted alone when no module contributed anything.
 * @param keyedBuckets {@code true} when contributions are themselves keyed blocks that have to be
 *                   merged per key rather than appended (Plandex's {@code locked:} / {@code audit:}
 *                   / {@code privacy:}), because those keys would otherwise repeat in turn.
 */
public record YamlMergeShape(String anchor, int indent, String emptyBody, boolean keyedBuckets) {

    /** A document whose contributions are sequence entries or block-scalar text, appended in order. */
    public static YamlMergeShape appended(String anchor, int indent, String emptyBody) {
        return new YamlMergeShape(anchor, indent, emptyBody, false);
    }

    /** A document whose contributions are keyed blocks that must be merged key by key. */
    public static YamlMergeShape keyed(String anchor, int indent) {
        return new YamlMergeShape(anchor, indent, "", true);
    }

    /**
     * Merges every module's rendered document into one.
     *
     * <p>The sub-markers arrive as functions rather than {@code String.format} patterns so the
     * pattern stays a constant in the caller. A format string passed across a call boundary is a
     * format string an attacker's data could one day reach, which Find Security Bugs is right to
     * flag; a function also keeps the marker vocabulary where the marker constants live.
     *
     * @param contributions module id → that module's complete rendered document, in output order
     * @param subMarkerStart builds the opening module sub-marker for a module id
     * @param subMarkerEnd   builds the closing module sub-marker for a module id
     * @return the merged document, or {@code null} when any contribution does not contain
     *         {@link #anchor} — the shape no longer describes the renderer, and the caller is
     *         better off with the previous concatenation than with a document this code guessed at
     */
    public @Nullable String merge(List<Map.Entry<String, String>> contributions,
                                  UnaryOperator<String> subMarkerStart,
                                  UnaryOperator<String> subMarkerEnd) {
        String scaffold = null;
        List<Map.Entry<String, String>> chunks = new ArrayList<>();
        for (Map.Entry<String, String> contribution : contributions) {
            String document = contribution.getValue();
            int afterAnchor = endOfAnchorLine(document);
            if (afterAnchor < 0) return null;
            // Trailing newline dropped so every piece below can prepend its own; the merged
            // document then has the same line breaks the single-module rendering does.
            if (scaffold == null) scaffold = document.substring(0, afterAnchor).stripTrailing();
            String body = trimBlankLines(document.substring(afterAnchor));
            if (body.isBlank() || body.strip().equals(emptyBody.strip())) continue;
            chunks.add(Map.entry(contribution.getKey(), body));
        }
        if (scaffold == null) return null; // no contributions at all; caller handles that case
        if (chunks.isEmpty()) {
            return emptyBody.isBlank() ? scaffold : scaffold + "\n" + emptyBody;
        }

        StringBuilder out = new StringBuilder(scaffold);
        if (keyedBuckets) {
            if (!appendKeyedBuckets(out, chunks, subMarkerStart, subMarkerEnd)) return null;
        } else {
            for (Map.Entry<String, String> chunk : chunks) {
                appendWrapped(out, chunk.getKey(), chunk.getValue(), indent, subMarkerStart, subMarkerEnd);
            }
        }
        return out.toString();
    }

    /**
     * Regroups keyed contributions so each key appears once. Plandex renders
     * {@code guardrails:} with a {@code locked:} / {@code audit:} / {@code privacy:} block under
     * it, and appending two modules' blocks would repeat whichever keys they share — the same
     * duplicate-key defect one level further in.
     *
     * @return {@code false} when a chunk does not decompose into keyed blocks, so nothing is
     *         written that would drop the part this could not place
     */
    private boolean appendKeyedBuckets(StringBuilder out, List<Map.Entry<String, String>> chunks,
                                       UnaryOperator<String> subMarkerStart,
                                       UnaryOperator<String> subMarkerEnd) {
        Map<String, List<Map.Entry<String, String>>> byKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> chunk : chunks) {
            Map<String, String> buckets = splitBuckets(chunk.getValue());
            if (buckets == null || buckets.isEmpty()) return false;
            for (Map.Entry<String, String> bucket : buckets.entrySet()) {
                byKey.computeIfAbsent(bucket.getKey(), k -> new ArrayList<>())
                     .add(Map.entry(chunk.getKey(), bucket.getValue()));
            }
        }
        for (Map.Entry<String, List<Map.Entry<String, String>>> bucket : byKey.entrySet()) {
            out.append('\n').append(bucket.getKey());
            for (Map.Entry<String, String> contribution : bucket.getValue()) {
                appendWrapped(out, contribution.getKey(), contribution.getValue(),
                    indent + 2, subMarkerStart, subMarkerEnd);
            }
        }
        return true;
    }

    /**
     * Splits {@code "  locked:\n    - a\n  audit:\n    - b"} into key line → entry lines.
     *
     * @return {@code null} when content appears before the first key, which means the body is not
     *         the keyed block this shape claims and no key can safely be assigned to it
     */
    private @Nullable Map<String, String> splitBuckets(String body) {
        Map<String, String> buckets = new LinkedHashMap<>();
        String current = null;
        StringBuilder entries = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (isBucketKey(line)) {
                if (current != null) buckets.put(current, trimBlankLines(entries.toString()));
                current = line.stripTrailing();
                entries.setLength(0);
            } else if (current != null) {
                if (entries.length() > 0) entries.append('\n');
                entries.append(line);
            } else if (!line.isBlank()) {
                return null;
            }
        }
        if (current != null) buckets.put(current, trimBlankLines(entries.toString()));
        return buckets;
    }

    /** A key line at exactly {@link #indent} columns, with nothing after the colon. */
    private boolean isBucketKey(String line) {
        String trailingTrimmed = line.stripTrailing();
        if (!trailingTrimmed.endsWith(":")) return false;
        int leading = trailingTrimmed.length() - trailingTrimmed.stripLeading().length();
        return leading == indent;
    }

    private static void appendWrapped(StringBuilder out, String moduleId, String body, int indent,
                                      UnaryOperator<String> subMarkerStart,
                                      UnaryOperator<String> subMarkerEnd) {
        String pad = " ".repeat(indent);
        out.append('\n').append(pad).append(subMarkerStart.apply(moduleId))
           .append('\n').append(body)
           .append('\n').append(pad).append(subMarkerEnd.apply(moduleId));
    }

    /**
     * Index just past the newline ending the first line equal to {@link #anchor}, or {@code -1}.
     * Matched whole-line and indentation-sensitive, so {@code rules:} never matches Ellipsis's
     * nested {@code "  rules:"} and no sequence entry can be mistaken for the scaffold's end.
     */
    private int endOfAnchorLine(String document) {
        int from = 0;
        while (from <= document.length()) {
            int newline = document.indexOf('\n', from);
            int lineEnd = newline < 0 ? document.length() : newline;
            if (document.substring(from, lineEnd).stripTrailing().equals(anchor)) {
                return newline < 0 ? document.length() : newline + 1;
            }
            if (newline < 0) return -1;
            from = newline + 1;
        }
        return -1;
    }

    /** Drops leading and trailing blank lines while preserving every line's own indentation. */
    private static String trimBlankLines(String text) {
        String[] lines = text.split("\n", -1);
        int start = 0;
        int end = lines.length;
        while (start < end && lines[start].isBlank()) start++;
        while (end > start && lines[end - 1].isBlank()) end--;
        return String.join("\n", List.of(lines).subList(start, end));
    }
}
