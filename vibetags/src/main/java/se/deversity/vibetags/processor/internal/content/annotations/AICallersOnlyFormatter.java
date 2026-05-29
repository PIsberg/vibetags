package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AICallersOnly annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AICallersOnlyFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AICallersOnly callersOnly = element.getAnnotation(AICallersOnly.class);
        if (callersOnly == null) return;
        String className = ElementNaming.elementPath(element);
        String[] value = callersOnly.value();
        String callers = String.join(", ", value);
        String summary = "Only callable by: [" + callers + "]";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <allowed_callers>").append(callers).append("</allowed_callers>\n    </file>\n");
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
                sb.append("### ").append(className).append("\n- **Allowed Callers**: ").append(callers).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### CALLERS LIMIT: ").append(className).append("\n- **Allowed Callers**: ").append(callers).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (callers limited): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
