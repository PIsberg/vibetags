package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for {@code .goosehints}, the project hints file read by goose, Block's
 * open-source coding agent.
 *
 * <p>Free-form Markdown with no schema of its own, so it takes the same output as
 * {@code .cursorrules} rather than a format invented for it. {@link FirebaseRenderer} delegates for
 * the same reason.
 *
 * <p>goose also reads {@code AGENTS.md}, and its own guidance is to reach for {@code .goosehints}
 * when the context is directory-scoped and for {@code AGENTS.md} when a project fits in one file.
 * VibeTags writes {@code AGENTS.md} only as the sole AI config file (tier-1 invariant 4), so
 * {@code .goosehints} is the file that actually reaches a goose user whose project also uses Claude
 * or Cursor. See issue #610 for the wider version of that problem.
 */
public final class GooseRenderer implements PlatformRenderer {
    // CursorRenderer is stateless — one shared instance is sufficient.
    private static final CursorRenderer CURSOR_RENDERER = new CursorRenderer();

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        return CURSOR_RENDERER.render(model, platform, context);
    }
}
