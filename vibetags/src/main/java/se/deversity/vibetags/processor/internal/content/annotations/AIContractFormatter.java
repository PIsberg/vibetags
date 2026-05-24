package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIContract annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AIContractFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIContract contract = element.getAnnotation(AIContract.class);
        if (contract == null) return;
        String className = ElementNaming.elementPath(element);
        String reason = contract.reason();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(reason).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n      <reason>").append(reason).append("</reason>\n    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(reason).append("\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(reason).append("\n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(reason).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(reason).append("\n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): ").append(reason).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Reason**: ").append(reason).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### CONTRACT: ").append(className).append("\n- **Constraint**: Signature is frozen. Do not change method names, parameter types, return types, or checked exceptions.\n- **Reason**: ").append(reason).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(reason).append("\n");
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(className).append("\", \"reason\": \"").append(reason).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Contract-frozen signature for ").append(className).append(": do not change method name, parameters, return type, or checked exceptions\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (contract): signature frozen — ").append(reason).append("\n");
                break;
            default:
                break;
        }
    }
}
