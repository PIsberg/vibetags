package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges {@code .pr_agent.toml} across modules by unioning the instruction lines inside the
 * multi-line strings.
 *
 * <p>The document is a fixed shape produced by {@code PrAgentRenderer}: a comment header, then two
 * sections — {@code [pr_reviewer]} and {@code [pr_code_suggestions]} — each holding the <em>same</em>
 * {@code extra_instructions = """…"""} body. Both are rewritten from the merged body, because
 * updating one and not the other would give PR-Agent two different views of the same project and
 * the difference would only show on whichever check ran second.
 *
 * <p>The body is a header sentence followed by one bullet per guardrail, so the union is line-wise
 * with exact duplicates collapsed: the header sentence is identical in every module and appears
 * once, and a guardrail two modules happen to share does not appear twice.
 *
 * <p>A module with nothing to say renders the placeholder sentence instead of bullets. That is
 * dropped when any other module contributed, and kept when none did — a file saying "no guardrails
 * are declared" is correct for a project that has none, and wrong sitting above a list of them.
 */
final class TomlInstructionsMerge implements WholeFileMerge {

    /** Stateless, so one instance serves every call — see {@link WholeFileMerge#tomlInstructions()}. */
    static final TomlInstructionsMerge INSTANCE = new TomlInstructionsMerge();

    private static final String OPEN = "extra_instructions = \"\"\"";
    private static final String CLOSE = "\"\"\"";
    private static final String EMPTY_BODY = "No VibeTags guardrails are currently declared.";

    @Override
    public @Nullable String merge(List<Map.Entry<String, String>> contributions) {
        String scaffold = null;
        Set<String> merged = new LinkedHashSet<>();
        boolean anyRealContent = false;

        for (Map.Entry<String, String> contribution : contributions) {
            String document = contribution.getValue();
            List<String> body = extractBody(document);
            if (body == null) {
                return null; // not the shape this understands; caller keeps the old behaviour
            }
            if (scaffold == null) {
                scaffold = document;
            }
            boolean isPlaceholder = body.size() == 1 && EMPTY_BODY.equals(body.get(0).strip());
            if (isPlaceholder) {
                continue;
            }
            anyRealContent = true;
            merged.addAll(body);
        }
        if (scaffold == null) {
            return null;
        }
        List<String> body = anyRealContent ? new ArrayList<>(merged) : List.of(EMPTY_BODY);
        return rewriteBothSections(scaffold, String.join("\n", body));
    }

    /** The lines between the first {@code """} pair, or {@code null} if there is not one. */
    private static @Nullable List<String> extractBody(String document) {
        int open = document.indexOf(OPEN);
        if (open < 0) {
            return null;
        }
        int bodyStart = document.indexOf('\n', open + OPEN.length());
        if (bodyStart < 0) {
            return null;
        }
        int close = document.indexOf(CLOSE, bodyStart);
        if (close < 0) {
            return null;
        }
        String body = document.substring(bodyStart + 1, close);
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines.isEmpty() ? List.of(EMPTY_BODY) : lines;
    }

    /**
     * Replaces the body of every {@code extra_instructions} block, keeping everything else — the
     * comment header, the section names, the ordering — exactly as the renderer wrote it. With one
     * contribution this reproduces the input, which is what keeps a single-module build unchanged.
     */
    private static String rewriteBothSections(String scaffold, String body) {
        StringBuilder out = new StringBuilder(scaffold.length() + body.length());
        int cursor = 0;
        while (true) {
            int open = scaffold.indexOf(OPEN, cursor);
            if (open < 0) {
                out.append(scaffold, cursor, scaffold.length());
                return out.toString();
            }
            int bodyStart = scaffold.indexOf('\n', open + OPEN.length());
            int close = scaffold.indexOf(CLOSE, bodyStart);
            if (bodyStart < 0 || close < 0) {
                out.append(scaffold, cursor, scaffold.length());
                return out.toString();
            }
            out.append(scaffold, cursor, bodyStart + 1).append(body).append('\n');
            cursor = close;
        }
    }
}
