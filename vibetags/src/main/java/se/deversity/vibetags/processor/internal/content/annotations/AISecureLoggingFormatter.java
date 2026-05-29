package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AISecureLogging annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AISecureLoggingFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AISecureLogging secureLogging = element.getAnnotation(AISecureLogging.class);
        if (secureLogging == null) return;
        String className = ElementNaming.elementPath(element);
        AISecureLogging.MaskingPolicy policy = secureLogging.value();
        String summary = "Sensitive variable. Forbid direct logging/printing. Enforce masking policy: " + policy.name();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <logging_policy>").append(policy.name()).append("</logging_policy>\n    </file>\n");
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
                sb.append("### ").append(className).append("\n- **Secure Logging Policy**: ").append(policy.name()).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SECURE LOGGING: ").append(className).append("\n- **Required Masking**: ").append(policy.name()).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (secure-logging): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
