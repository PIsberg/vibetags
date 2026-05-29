package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AITemporary;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AITemporary annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AITemporaryFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AITemporary temp = element.getAnnotation(AITemporary.class);
        if (temp == null) return;
        String className = ElementNaming.elementPath(element);
        String expiresOn = temp.expiresOn();
        String reason = temp.reason();
        String summary = "Temporary logic/workaround. Expires on: " + expiresOn + ". Reason: " + reason;

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <temporary_expiration>").append(expiresOn).append("</temporary_expiration>\n      <temporary_reason>").append(reason).append("</temporary_reason>\n    </file>\n");
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
                sb.append("### ").append(className).append("\n- **Temporal Expiration**: ").append(expiresOn).append("\n- **Reason**: ").append(reason).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### TEMPORARY WORKAROUND: ").append(className).append("\n- **Expires On**: ").append(expiresOn).append("\n- **Reason**: ").append(reason).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (temporary): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
