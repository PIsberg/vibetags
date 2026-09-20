package se.deversity.vibetags.processor.internal.content.platforms;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailModel;

/**
 * PlatformRenderer for {@code TESTING.md}, the file a test round's guardrails are routed to.
 *
 * <p>No tool reads {@code TESTING.md} by name, so it has no format of its own to honour. It
 * borrows the {@code AGENTS.md} Markdown, rendered as {@link Platform#CODEX} rather than as
 * {@link Platform#TESTING}: every annotation formatter switches on the platform and writes nothing
 * for one it has no arm for, so rendering under its own name would need 44 new arms and would
 * silently drop whichever one was forgotten.
 */
public final class RoutedTestingRenderer implements PlatformRenderer {
    // CodexRenderer is stateless — one shared instance is sufficient.
    private static final CodexRenderer CODEX_RENDERER = new CodexRenderer();

    /** Says what the file is scoped to, and that a guardrail missing here is not missing. */
    static final String PREAMBLE =
        "These guardrails apply to test code. Read them before changing a test, a fixture or a test helper.\n"
            + "Safety guardrails on test code (locked, core, privacy, ignore, audit, secure) are not repeated"
            + " here: they stay in the always-loaded instruction files.\n\n";

    /**
     * @return {@code null} outside a test round. A main round has no test guardrails, and an empty
     *         body would replace the region the test round wrote; no entry leaves it in place.
     */
    @Override
    public @Nullable String render(GuardrailModel model, Platform platform, RenderingContext context) {
        if (!context.testRound()) {
            return null;
        }
        return PREAMBLE + CODEX_RENDERER.render(model, Platform.CODEX, context);
    }
}
