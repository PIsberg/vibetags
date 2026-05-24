package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AICore annotations for all platforms.
 */
public final class AICoreFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AICore core = element.getAnnotation(AICore.class);
        if (core == null) return;
        String className = ElementNaming.elementPath(element);
        String sensitivity = core.sensitivity();
        String note = core.note();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n      <sensitivity>").append(sensitivity).append("</sensitivity>\n      <note>").append(note).append("</note>\n    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("** (sensitivity: ").append(sensitivity).append("): ").append(note).append("\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` — sensitivity: ").append(sensitivity).append(". ").append(note).append("\n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Sensitivity**: ").append(sensitivity).append("\n- **Note**: ").append(note).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### CORE FUNCTIONALITY: ").append(className).append("\n- **Sensitivity**: ").append(sensitivity).append("\n- **Note**: ").append(note).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: Sensitivity: ").append(sensitivity).append(". Note: ").append(note).append("\n");
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(className).append("\", \"sensitivity\": \"").append(sensitivity).append("\", \"note\": \"").append(note).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Core functionality (change with caution): ").append(className).append(" [sensitivity: ").append(sensitivity).append("]\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (core, sensitivity: ").append(sensitivity).append("): ").append(note).append("\n");
                break;
            default:
                break;
        }
    }
}
