package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AISecure annotations for all platforms.
 */
public final class AISecureFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AISecure secure = element.getAnnotation(AISecure.class);
        if (secure == null) return;
        String className = ElementNaming.elementPath(element);
        String aspect = secure.aspect();
        String summary = "Security-critical code" + (aspect.isEmpty() ? "" : " [" + aspect + "]")
                       + ". Do not weaken security properties. Flag any change for security review.";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n");
                if (!aspect.isEmpty()) {
                    sb.append("      <aspect>").append(aspect).append("</aspect>\n");
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
                sb.append("### ").append(className).append("\n- Security-critical code");
                if (!aspect.isEmpty()) {
                    sb.append(" (aspect: ").append(aspect).append(")");
                }
                sb.append(".\n- Never weaken security properties. Every change requires explicit security review.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SECURITY-CRITICAL: ").append(className).append("\n")
                  .append(aspect.isEmpty() ? "" : "- **Aspect**: " + aspect + "\n")
                  .append("- **Rule**: Do not weaken security properties. Every change must be reviewed for security impact.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case SWEEP:
                sb.append("  - \"Security-critical: ").append(className).append(" [").append(aspect.isEmpty() ? "general" : aspect).append("]. Do not weaken security.\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (security-critical): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
