package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AILocked annotations for all platforms.
 */
public final class AILockedFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AILocked locked = element.getAnnotation(AILocked.class);
        if (locked == null) return;
        String className = ElementNaming.elementPath(element);
        String reason = locked.reason();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - Reason: ").append(reason).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <reason>").append(reason).append("</reason>\n    </file>\n");
                break;
            case AI_EXCLUDE:
                sb.append("**/").append(element.getSimpleName()).append(".java\n");
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
                sb.append("#### LOCKED: ").append(className).append("\n- **Status**: Locked (Do Not Edit)\n- **Reason**: ").append(reason).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(reason).append("\n");
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(className).append("\", \"reason\": \"").append(reason).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Do not modify ").append(className).append(": ").append(reason).append("\"\n");
                break;
            case PLANDEX:
                sb.append("    - path: \"").append(className).append("\"\n      reason: \"").append(reason).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (locked): ").append(reason).append("\n");
                break;
            default:
                break;
        }
    }
}
