package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIDraft;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIDraft annotations for all platforms.
 */
public final class AIDraftFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIDraft draft = element.getAnnotation(AIDraft.class);
        if (draft == null) return;
        String className = ElementNaming.elementPath(element);
        String instructions = draft.instructions();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - Task: ").append(instructions).append("\n");
                break;
            case CLAUDE:
                sb.append("    <task path=\"").append(className).append("\">\n      <instructions>").append(instructions).append("</instructions>\n    </task>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(instructions).append("\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("`: ").append(instructions).append("\n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - Task: ").append(instructions).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(instructions).append("\n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): ").append(instructions).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Instructions**: ").append(instructions).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### DRAFT/TODO: ").append(className).append("\n- **Instruction**: ").append(instructions).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(instructions).append("\n");
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(className).append("\", \"instructions\": \"").append(instructions).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Implementation task for ").append(className).append(": ").append(instructions).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (draft): ").append(instructions).append("\n");
                break;
            default:
                break;
        }
    }
}
