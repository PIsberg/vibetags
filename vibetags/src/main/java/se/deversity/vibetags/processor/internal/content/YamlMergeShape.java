package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
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
 */
public record YamlMergeShape(String anchor, int indent, String emptyBody) {

    /** A document whose contributions are sequence entries or block-scalar text, appended in order. */
    public static YamlMergeShape appended(String anchor, int indent, String emptyBody) {
        return new YamlMergeShape(anchor, indent, emptyBody);
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
        for (Map.Entry<String, String> chunk : chunks) {
            appendWrapped(out, chunk.getKey(), chunk.getValue(), indent, subMarkerStart, subMarkerEnd);
        }
        return out.toString();
    }

    /**
     * Merges the several renderings one module produces, one per compiled source set, into the
     * single document that module contributes to {@link #merge}.
     *
     * <p>Maven and Gradle compile a module's main and test sources as two rounds, so a module whose
     * test code is annotated renders this platform twice. {@code merge} is built to receive one
     * complete document per module and write the scaffold once; handing it two documents joined
     * together puts a second scaffold inside a contribution's body, where nothing strips it. That
     * is the duplicate-key defect this record exists to prevent, one level further in, and it hid
     * behind the fixtures: every reactor example annotated main sources only.
     *
     * @param documents this module's rendered documents, in source-set order
     * @return the single document, or {@code null} when a part does not match this shape, so the
     *         caller keeps the concatenation it used before rather than a document guessed at here
     */
    public @Nullable String mergeSourceSets(List<String> documents) {
        if (documents.size() < 2) {
            return documents.isEmpty() ? null : documents.get(0);
        }
        String scaffold = null;
        List<String> bodies = new ArrayList<>();
        for (String document : documents) {
            int afterAnchor = endOfAnchorLine(document);
            if (afterAnchor < 0) return null;
            if (scaffold == null) scaffold = document.substring(0, afterAnchor).stripTrailing();
            String body = trimBlankLines(document.substring(afterAnchor));
            if (body.isBlank() || body.strip().equals(emptyBody.strip())) continue;
            bodies.add(body);
        }
        if (scaffold == null) return null;
        if (bodies.isEmpty()) {
            return emptyBody.isBlank() ? scaffold : scaffold + "\n" + emptyBody;
        }
        return scaffold + "\n" + String.join("\n", bodies);
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
     * Matched whole-line and indentation-sensitive, so a top-level anchor never matches a nested
     * key of the same name and no sequence entry can be mistaken for the scaffold's end.
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
