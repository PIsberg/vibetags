package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.internal.AnnotationCollector;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;

/**
 * PlatformRenderer for generating `CLAUDE.local.md`.
 * Uses the exact same XML-based guardrail format as `CLAUDE.md` — Claude Code loads both files
 * for the same project, so the content model is identical.
 */
public final class ClaudeLocalRenderer implements PlatformRenderer {
    // ClaudeRenderer is stateless — one shared instance is sufficient.
    private static final ClaudeRenderer CLAUDE_RENDERER = new ClaudeRenderer();

    @Override
    public String render(AnnotationCollector collector, Platform platform, RenderingContext context) {
        return CLAUDE_RENDERER.render(collector, platform, context);
    }
}
