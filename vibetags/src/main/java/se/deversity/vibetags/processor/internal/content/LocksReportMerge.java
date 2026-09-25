package se.deversity.vibetags.processor.internal.content;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Joins the {@code .vibetags-locks} bodies of several source sets into one (issue #851).
 *
 * <p>The report is JSON Lines with {@code #} comment lines, and every line stands alone: a comment,
 * the format record, or one locked element. So the join is a union of lines, the first body's in
 * order, then each line a later body adds. Stacked, a test round with no locks of its own repeated
 * the two header comments and the format record; readers skip comments and non-{@code locked}
 * records, so that was noise rather than damage, but it sat in a file the CI Locked Files Guard
 * parses on every pull request.
 *
 * <p>Declines, so the caller concatenates as before, on a line that is neither a comment nor a JSON
 * object, which no version of the renderer writes. Dropping only exact duplicate lines cannot lose a
 * lock: two locked elements always differ in their {@code element} field.
 */
public final class LocksReportMerge {

    private LocksReportMerge() {
    }

    /**
     * {@link SourceSetMerge} for {@code .vibetags-locks}.
     *
     * @param bodies the rendered bodies, main source set first
     * @return one report body, or {@code null} to decline
     */
    public static @Nullable String merge(List<String> bodies) {
        if (bodies.size() < 2) {
            return bodies.isEmpty() ? null : bodies.get(0);
        }
        Set<String> lines = new LinkedHashSet<>();
        for (String body : bodies) {
            for (String line : body.split("\n", -1)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!trimmed.startsWith("#") && !trimmed.startsWith("{")) {
                    return null;
                }
                lines.add(line);
            }
        }
        return String.join("\n", new ArrayList<>(lines));
    }
}
