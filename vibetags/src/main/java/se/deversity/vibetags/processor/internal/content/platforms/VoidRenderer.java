package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating Void Editor's {@code .void/rules.md}.
 * Uses the exact same Markdown output format as {@code .cursorrules}.
 */
public final class VoidRenderer implements PlatformRenderer {
    // CursorRenderer is stateless — one shared instance is sufficient.
    private static final CursorRenderer CURSOR_RENDERER = new CursorRenderer();

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        return CURSOR_RENDERER.render(model, platform, context);
    }
}
