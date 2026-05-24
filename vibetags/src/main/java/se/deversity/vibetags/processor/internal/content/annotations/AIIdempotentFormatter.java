package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIIdempotent annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AIIdempotentFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIIdempotent idempotent = element.getAnnotation(AIIdempotent.class);
        if (idempotent == null) return;
        String className = ElementNaming.elementPath(element);
        String reason = idempotent.reason();
        String summary = "Idempotency guaranteed. Multiple invocations must produce the same result as one."
                       + (reason.isEmpty() ? "" : " Reason: " + reason);

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n      <idempotent>true</idempotent>\n");
                if (!reason.isEmpty()) {
                    sb.append("      <reason>").append(reason).append("</reason>\n");
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
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): ").append(summary).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Idempotency guaranteed. Multiple invocations must produce the same result as a single invocation.\n");
                if (!reason.isEmpty()) {
                    sb.append("- **Reason**: ").append(reason).append("\n");
                }
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### IDEMPOTENT: ").append(className).append("\n- **Rule**: Must remain idempotent. Multiple invocations must produce the same result as one.\n")
                  .append(reason.isEmpty() ? "" : "- **Reason**: " + reason + "\n").append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case SWEEP:
                sb.append("  - \"Idempotency requirement for ").append(className).append(": ").append(summary).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (idempotent): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
