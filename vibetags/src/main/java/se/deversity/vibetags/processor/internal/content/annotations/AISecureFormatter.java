package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AISecure annotations for all platforms.
 */
public final class AISecureFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AISecure secure = element.annotation(AISecure.class);
        if (secure == null) return;
        String className = element.path();
        String aspect = secure.aspect();
        String summary = "Security-critical code" + (aspect.isEmpty() ? "" : " [" + aspect + "]")
                       + ". Do not weaken security properties. Flag any change for security review.";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n");
                if (!aspect.isEmpty()) {
                    sb.append("      <aspect>").append(Escape.xml(aspect)).append("</aspect>\n");
                }
                sb.append("    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append('\n');
                break;
            case COPILOT:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append(')').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Security-critical code");
                if (!aspect.isEmpty()) {
                    sb.append(" (aspect: ").append(aspect).append(')');
                }
                sb.append(".\n- Never weaken security properties. Every change requires explicit security review.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SECURITY-CRITICAL: ").append(className).append('\n')
                  .append(aspect.isEmpty() ? "" : "- **Aspect**: " + aspect + "\n")
                  .append("- **Rule**: Do not weaken security properties. Every change must be reviewed for security impact.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case SWEEP:
                sb.append("  - \"Security-critical: ").append(Escape.json(className)).append(" [").append(Escape.json(aspect.isEmpty() ? "general" : aspect)).append("]. Do not weaken security.\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (security-critical)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
