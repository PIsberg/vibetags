package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIGenerated;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIGenerated annotations for all platforms.
 *
 * <p>Every rendering states the redirect, never a bare prohibition: an agent that is only told
 * "do not edit" gives up or works around the obstacle, whereas one told where the change belongs
 * can still complete the task.
 */
public final class AIGeneratedFormatter implements AnnotationFormatter {
    @Override
    public void render(TaggedElement element, StringBuilder sb, Platform platform) {
        AIGenerated generated = element.annotation(AIGenerated.class);
        if (generated == null) return;
        String className = element.path();
        String from = generated.from();
        String regenerateWith = generated.regenerateWith();
        String editInstead = generated.editInstead();
        String target = editInstead.isEmpty() ? from : editInstead;
        String summary = summary(from, target, regenerateWith);

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) return;

        switch (platform) {
            case CLAUDE:
                renderClaude(className, from, editInstead, regenerateWith, sb);
                break;
            case LLMS_FULL:
                renderLlmsFull(className, from, target, regenerateWith, sb);
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### GENERATED: ").append(className).append('\n')
                  .append(CommonFormatterHelper.bullet("Source", from))
                  .append(CommonFormatterHelper.bullet("Edit instead", target))
                  .append(regenerateWith.isEmpty() ? "" : "- **Regenerate with**: " + regenerateWith + "\n")
                  .append("- **Rule**: Never hand-edit. Change the source and regenerate.\n\n");
                break;
            case CODERABBIT:
                sb.append("- `").append(className).append("` (generated)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }

    private static String summary(String from, String target, String regenerateWith) {
        // With no source named there is nothing to point an agent at, but "do not hand-edit this"
        // is the guardrail and survives on its own.
        return (from.isEmpty() ? "Machine-generated." : "Generated from `" + from + "`.")
            + " Hand edits are overwritten"
            + (target.isEmpty() ? "" : " — edit `" + target + "` instead")
            + (regenerateWith.isEmpty() ? "" : ", then run `" + regenerateWith + "`") + ".";
    }

    private static void renderClaude(String className, String from, String editInstead,
                                     String regenerateWith, StringBuilder sb) {
        sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
            .append(CommonFormatterHelper.element("from", from));
        if (!editInstead.isEmpty()) {
            sb.append("      <edit-instead>").append(Escape.xml(editInstead)).append("</edit-instead>\n");
        }
        if (!regenerateWith.isEmpty()) {
            sb.append("      <regenerate-with>").append(Escape.xml(regenerateWith)).append("</regenerate-with>\n");
        }
        sb.append("    </element>\n");
    }

    private static void renderLlmsFull(String className, String from, String target,
                                       String regenerateWith, StringBuilder sb) {
        sb.append("### ").append(className).append('\n')
            .append(from.isEmpty()
                ? "- Machine-generated.\n"
                : "- Machine-generated from `" + from + "`.\n")
            .append("- Hand edits are silently overwritten on the next regeneration.\n")
            .append(target.isEmpty() ? "" : "- Edit `" + target + "` instead.\n");
        if (!regenerateWith.isEmpty()) {
            sb.append("- Regenerate with: `").append(regenerateWith).append("`\n");
        }
        sb.append("- Still read this element to understand behaviour — only never write it.\n\n");
    }
}
