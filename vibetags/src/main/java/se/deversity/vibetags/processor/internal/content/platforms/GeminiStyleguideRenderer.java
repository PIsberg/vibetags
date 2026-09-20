package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for Gemini Code Assist's {@code .gemini/styleguide.md} review style guide.
 *
 * <p>This is a different product from the Gemini CLI, which reads {@code GEMINI.md} and is handled
 * by {@link GeminiRenderer}. Gemini Code Assist reviews pull requests on GitHub and takes its
 * per-repository rules from {@code .gemini/styleguide.md}, so the guardrails have to be restated
 * here or the reviewer never sees them.
 *
 * <p>The document itself is built by {@link GuardrailInstructionBlock#reviewDocument}, shared with
 * {@link GreptileRulesRenderer}: the two differ only in the two literals passed below.
 */
public final class GeminiStyleguideRenderer implements PlatformRenderer {

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        return GuardrailInstructionBlock.reviewDocument(
                model, context, "review style guide", "Gemini Code Assist");
    }
}
