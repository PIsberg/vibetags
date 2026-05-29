package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating `.idx/airules.md` for Firebase AI.
 * Uses the same Markdown output format as `.cursorrules`.
 */
public final class FirebaseRenderer implements PlatformRenderer {
    // CursorRenderer is stateless — one shared instance is sufficient.
    private static final CursorRenderer CURSOR_RENDERER = new CursorRenderer();

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        return CURSOR_RENDERER.render(collector, platform, context);
    }
}
