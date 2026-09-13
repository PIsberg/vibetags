package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.model.GuardrailModel;

/**
 * PlatformRenderer for the always-loaded safety file in Cline's {@code .clinerules/} directory
 * (issue #648).
 *
 * <p>The directory's per-element rule files load only when a {@code paths:} glob matches a file in
 * the task's context. The six safety buckets must not wait for that (invariant 6), and Cline has no
 * aggregate beside the directory to keep them in, because its aggregate is the same path. This file
 * is that aggregate's indexed variant: the Cursor-format locked section and the inline safety
 * buckets, exactly as {@code .cursorrules} keeps them when its own granular directory is opted in,
 * with no front matter so Cline loads it on every request.
 *
 * <p>Rendered even when the safety tier is empty. The file is then a header and one sentence, and
 * that is the price of retiring a guardrail: a renderer that went quiet once the last
 * {@code @AILocked} was removed would leave its rule in a file Cline loads every time.
 */
public final class ClineSafetyRenderer implements PlatformRenderer {

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        StringBuilder sb = new StringBuilder(context.estimatedContentSize());
        AnnotationSections.renderIndexedPreamble(sb, model, Platform.CURSOR, context.getGeneratedHeader());
        AnnotationSections.renderInlineSafetySections(sb, model, Platform.CURSOR);
        sb.append("\n## SCOPED RULES\n")
          .append("Every other guardrail lives in the other rule files in .clinerules/, each loaded ")
          .append("when a file its paths: globs match is in the task's context.\n");
        return sb.toString();
    }
}
