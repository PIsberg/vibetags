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
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIGenerated generated = element.annotation(AIGenerated.class);
        if (generated == null) return;
        String className = element.path();
        String from = generated.from();
        String regenerateWith = generated.regenerateWith();
        String editInstead = generated.editInstead();
        String target = editInstead.isEmpty() ? from : editInstead;
        String summary = "Generated from `" + from + "`. Hand edits are overwritten — edit `"
                       + target + "` instead"
                       + (regenerateWith.isEmpty() ? "" : ", then run `" + regenerateWith + "`") + ".";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n");
                sb.append("      <from>").append(Escape.xml(from)).append("</from>\n");
                if (!editInstead.isEmpty()) {
                    sb.append("      <edit-instead>").append(Escape.xml(editInstead)).append("</edit-instead>\n");
                }
                if (!regenerateWith.isEmpty()) {
                    sb.append("      <regenerate-with>").append(Escape.xml(regenerateWith)).append("</regenerate-with>\n");
                }
                sb.append("    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append("\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(summary).append("\n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append("): ").append(summary).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Machine-generated from `").append(from).append("`.\n");
                sb.append("- Hand edits are silently overwritten on the next regeneration.\n");
                sb.append("- Edit `").append(target).append("` instead.\n");
                if (!regenerateWith.isEmpty()) {
                    sb.append("- Regenerate with: `").append(regenerateWith).append("`\n");
                }
                sb.append("- Still read this element to understand behaviour — only never write it.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### GENERATED: ").append(className).append("\n")
                  .append("- **Source**: ").append(from).append("\n")
                  .append("- **Edit instead**: ").append(target).append("\n")
                  .append(regenerateWith.isEmpty() ? "" : "- **Regenerate with**: " + regenerateWith + "\n")
                  .append("- **Rule**: Never hand-edit. Change the source and regenerate.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case SWEEP:
                sb.append("  - \"Generated code: ").append(Escape.json(className)).append(" comes from ")
                  .append(Escape.json(from)).append(". Edit ").append(Escape.json(target))
                  .append(" instead of the generated file.\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (generated): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
