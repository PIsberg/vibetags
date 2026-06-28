package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIRegulation annotations for all platforms.
 */
public final class AIRegulationFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIRegulation reg = element.getAnnotation(AIRegulation.class);
        if (reg == null) return;
        String className = ElementNaming.elementPath(element);
        String standard = reg.standard();
        String clause = reg.clause();
        String description = reg.description();
        String summary = standard + (clause.isEmpty() ? "" : " " + clause) + " — " + description;

        switch (platform) {
            case CURSOR:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <standard>").append(Escape.xml(standard)).append("</standard>\n");
                if (!clause.isEmpty()) {
                    sb.append("      <clause>").append(Escape.xml(clause)).append("</clause>\n");
                }
                sb.append("      <description>").append(Escape.xml(description)).append("</description>\n    </element>\n");
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
                sb.append("### ").append(className).append("\n- **Standard**: ").append(standard).append("\n");
                if (!clause.isEmpty()) {
                    sb.append("- **Clause**: ").append(clause).append("\n");
                }
                sb.append("- **Description**: ").append(description).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### REGULATORY: ").append(className).append("\n- **Standard**: ").append(standard).append("\n")
                  .append(clause.isEmpty() ? "" : "- **Clause**: " + clause + "\n")
                  .append("- **Description**: ").append(description).append("\n\n");
                break;
            case WINDSURF:
                sb.append("* `").append(className).append("` (regulation) - ").append(summary).append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("` (regulation): ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (regulation): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
