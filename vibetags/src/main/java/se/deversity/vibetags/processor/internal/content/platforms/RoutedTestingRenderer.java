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
     * The one heading the borrowed rendering always writes, content or not. Everything else
     * {@code CodexRenderer} emits is already conditional on having something to say.
     */
    private static final String EMPTY_LOCKED_SECTION = "## LOCKED FILES (DO NOT EDIT)\n\n";

    /**
     * @return {@code null} outside a test round. A main round has no test guardrails, and an empty
     *         body would replace the region the test round wrote; no entry leaves it in place.
     */
    @Override
    public @Nullable String render(GuardrailModel model, Platform platform, RenderingContext context) {
        if (!context.testRound()) {
            return null;
        }
        return PREAMBLE + withoutEmptyLockedSection(
            CODEX_RENDERER.render(model, Platform.CODEX, context), model);
    }

    /**
     * Drops the locked heading when nothing can appear under it.
     *
     * <p>The content builder renders this file from {@code model.withoutSafety()}, so its locked
     * bucket is empty by construction and the heading is a standing lie: a reader meets "LOCKED
     * FILES (DO NOT EDIT)" with nothing beneath it two lines after the preamble said the locked
     * guardrails are in the always-loaded files, and concludes no test file is locked. The test
     * that this repository already writes for it is that an opted-in file holding only a header is
     * a defect, not an output.
     *
     * <p>The condition is the model's own bucket rather than the platform, so a caller that does
     * hand this renderer a locked element still sees it. That is the case {@code TestingRendererTest}
     * pins, and it is why this is not simply a shorter preamble.
     */
    private static String withoutEmptyLockedSection(String body, GuardrailModel model) {
        return model.locked().isEmpty() ? body.replace(EMPTY_LOCKED_SECTION, "") : body;
    }
}
