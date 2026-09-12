package se.deversity.vibetags.processor.internal.content.platforms;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for Replit Agent's {@code replit.md}.
 *
 * <p>Replit documents {@code replit.md} as the project-root file the Agent reads for architecture,
 * conventions and coding style, and it must be at the root: the Agent does not look in
 * subdirectories.
 *
 * <p>One thing about this platform is unlike the others. The Replit Agent <em>writes</em> to
 * {@code replit.md} itself as it learns about a project, so VibeTags is not the only author. That
 * is survivable rather than dangerous, and only because of the marker contract: the file is
 * Markdown, so it gets HTML-comment markers and
 * {@link se.deversity.vibetags.processor.internal.GuardrailFileWriter} replaces only the region
 * between them. Whatever the Agent adds around the block is left alone, and if the Agent ever
 * rewrites the file wholesale the next compile puts the block back. The failure mode is a stale
 * block between two compiles, not lost content.
 *
 * <p>Delegates to {@link CursorRenderer} for the body: the Markdown bucket-walk is what Replit's
 * documentation asks for (clear instructions, concrete examples, project constraints) and a second
 * hand-rolled copy of it would be a twin free to drift.
 */
public final class ReplitRenderer implements PlatformRenderer {

    /** CursorRenderer is stateless, so one shared instance is enough. */
    private static final CursorRenderer CURSOR_RENDERER = new CursorRenderer();

    @Override
    public @Nullable String render(GuardrailModel model, Platform platform, RenderingContext context) {
        return CURSOR_RENDERER.render(model, platform, context);
    }
}
