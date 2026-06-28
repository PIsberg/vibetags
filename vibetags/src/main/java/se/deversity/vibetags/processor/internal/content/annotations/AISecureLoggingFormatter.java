package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AISecureLogging;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AISecureLogging annotations for all platforms.
 */
public final class AISecureLoggingFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AISecureLogging secureLogging = element.getAnnotation(AISecureLogging.class);
        if (secureLogging == null) return;
        String className = ElementNaming.elementPath(element);
        AISecureLogging.MaskingPolicy policy = secureLogging.value();
        String summary = "Sensitive variable. Forbid direct logging/printing. Enforce masking policy: " + policy.name();

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n      <logging_policy>").append(Escape.xml(policy.name())).append("</logging_policy>\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Secure Logging Policy**: ").append(policy.name()).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SECURE LOGGING: ").append(className).append("\n- **Required Masking**: ").append(policy.name()).append("\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (secure-logging): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
