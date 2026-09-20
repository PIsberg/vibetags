package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for Greptile's {@code .greptile/rules.md}.
 *
 * <p>Greptile documents the {@code .greptile/} folder as its recommended configuration and the root
 * {@code greptile.json} as the legacy form. {@code rules.md} is plain Markdown passed to the reviewer
 * with no parsing, so it takes HTML-comment markers like any other Markdown output and needs none of
 * the key-merge machinery {@code greptile.json} does.
 *
 * <p>The document itself is built by {@link GuardrailInstructionBlock#reviewDocument}, shared with
 * {@link GeminiStyleguideRenderer}: the two differ only in the two literals passed below.
 */
public final class GreptileRulesRenderer implements PlatformRenderer {

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        return GuardrailInstructionBlock.reviewDocument(
                model, context, "review rules", "Greptile");
    }
}
