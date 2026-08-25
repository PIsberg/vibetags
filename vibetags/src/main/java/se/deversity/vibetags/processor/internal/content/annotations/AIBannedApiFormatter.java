package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIBannedApi;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIBannedApi annotations for all platforms.
 *
 * <p>The replacement is rendered alongside the prohibition wherever one is declared: an agent
 * denied its first choice and given no route usually invents a worse second one.
 */
public final class AIBannedApiFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIBannedApi banned = element.annotation(AIBannedApi.class);
        if (banned == null) return;
        String className = element.path();
        String forbidden = String.join(", ", banned.forbidden());
        String useInstead = banned.useInstead();
        String reason = banned.reason();
        String summary = "Must not use: " + forbidden + "."
                       + (useInstead.isEmpty() ? "" : " Use " + useInstead + " instead.")
                       + (reason.isEmpty() ? "" : " (" + reason + ")");

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("forbidden", forbidden));
                if (!useInstead.isEmpty()) {
                    sb.append("      <use-instead>").append(Escape.xml(useInstead)).append("</use-instead>\n");
                }
                if (!reason.isEmpty()) {
                    sb.append("      <reason>").append(Escape.xml(reason)).append("</reason>\n");
                }
                sb.append("    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append('\n');
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(summary).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(summary).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(summary).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append("): ").append(summary).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Banned here", forbidden));
                if (!useInstead.isEmpty()) {
                    sb.append("- **Sanctioned route**: ").append(useInstead).append('\n');
                }
                if (!reason.isEmpty()) {
                    sb.append("- **Reason**: ").append(reason).append('\n');
                }
                sb.append("- These symbols compile, so the compiler will not stop you. The ban is a project rule.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### BANNED APIs: ").append(className).append('\n')
                  .append(CommonFormatterHelper.bullet("Forbidden", forbidden))
                  .append(useInstead.isEmpty() ? "" : "- **Use instead**: " + useInstead + "\n")
                  .append(reason.isEmpty() ? "" : "- **Reason**: " + reason + "\n")
                  .append("- **Rule**: These compile but are prohibited at this element.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append('\n');
                break;
            case SWEEP:
                sb.append("  - \"Banned in ").append(Escape.json(className)).append(": ")
                  .append(Escape.json(forbidden))
                  .append(useInstead.isEmpty() ? "" : ". Use " + Escape.json(useInstead))
                  .append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (banned APIs): ").append(summary).append('\n');
                break;
            default:
                break;
        }
    }
}
