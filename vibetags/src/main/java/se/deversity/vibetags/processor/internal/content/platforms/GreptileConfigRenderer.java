package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.WholeFileMerge;
import se.deversity.vibetags.processor.model.GuardrailModel;

/**
 * PlatformRenderer for Greptile's {@code .greptile/config.json} (#651).
 *
 * <p>In Greptile's recommended {@code .greptile/} form the guardrails go to {@code rules.md} and file
 * exclusions go to {@code config.json}, under {@code ignorePatterns}: a {@code .gitignore}-syntax
 * string, newline-separated, beside review settings the user sets by hand. Like
 * {@link GreptileRenderer}, this renders only the part VibeTags owns, here one key:
 *
 * <pre>
 * {
 *   "ignorePatterns": [
 *     "**&#47;Generated.java"
 *   ]
 * }
 * </pre>
 *
 * <p>{@code GuardrailFileWriter} splices that into a delimited span inside the user's
 * {@code ignorePatterns} value and leaves every other byte of the document alone. The config's own
 * {@code instructions} setting is not touched: {@code rules.md} carries the guardrails.
 */
public final class GreptileConfigRenderer implements PlatformRenderer {

    /**
     * Never rendered: a module with nothing to ignore renders an empty array rather than a line, so
     * there is no placeholder to drop. The empty string cannot collide with a pattern, since blank
     * lines are filtered out of every rendering.
     */
    private static final WholeFileMerge MERGE = WholeFileMerge.jsonLineArrays("");

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(256).append("{\n");
        GreptileRenderer.appendArray(sb, GreptileRenderer.IGNORE_PATTERNS_KEY,
            GreptileRenderer.ignorePatterns(model, Platform.GREPTILE_CONFIG));
        return sb.append("\n}\n").toString();
    }

    /** Patterns are unioned across modules, so a reactor's config lists every module's exclusions. */
    @Override
    public WholeFileMerge wholeFileMerge() {
        return MERGE;
    }
}
