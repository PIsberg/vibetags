package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIArchitecture annotations for all platforms.
 */
public final class AIArchitectureFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIArchitecture arch = element.getAnnotation(AIArchitecture.class);
        if (arch == null) return;
        String className = ElementNaming.elementPath(element);
        String belongsTo = arch.belongsTo();
        String[] cannotRef = arch.cannotReference();
        String cannotRefStr = String.join(", ", cannotRef);
        String summary = "Belongs to layer: `" + belongsTo + "`" + (cannotRef.length > 0 ? ". Prohibited from referencing: [" + cannotRefStr + "]" : "");

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <belongs_to>").append(Escape.xml(belongsTo)).append("</belongs_to>\n");
                for (String r : cannotRef) {
                    sb.append("      <cannot_reference>").append(Escape.xml(r)).append("</cannot_reference>\n");
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
                sb.append("### ").append(className).append("\n- **Belongs to Layer**: ").append(belongsTo).append("\n");
                if (cannotRef.length > 0) {
                    sb.append("- **Prohibited References**: ").append(cannotRefStr).append("\n");
                }
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### ARCHITECTURE LAYER: ").append(className).append("\n- **Layer**: ").append(belongsTo).append("\n")
                  .append(cannotRef.length > 0 ? "- **Cannot Reference**: " + cannotRefStr + "\n" : "").append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (architecture): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
