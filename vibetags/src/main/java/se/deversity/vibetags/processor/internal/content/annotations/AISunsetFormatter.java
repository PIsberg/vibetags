package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AISunset annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AISunsetFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AISunset sunset = element.getAnnotation(AISunset.class);
        if (sunset == null) return;
        String className = ElementNaming.elementPath(element);
        String jira = sunset.jira();
        
        // replacement attribute has Class value, which throws MirroredTypeException during compilation/processing
        String replacementName = "java.lang.Object";
        try {
            replacementName = sunset.replacement().getName();
        } catch (MirroredTypeException mte) {
            TypeMirror mirror = mte.getTypeMirror();
            if (mirror != null) {
                replacementName = mirror.toString();
            }
        }
        
        String summary = "Strictly sunset/deprecated. Forbid any *new* calls or references. JIRA: " + jira + ". Replacement: `" + replacementName + "`";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <sunset_ticket>").append(jira).append("</sunset_ticket>\n      <replacement_target>").append(replacementName).append("</replacement_target>\n    </file>\n");
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
                sb.append("### ").append(className).append("\n- **Sunset Status**: Active (Forbid new calls)\n- **JIRA Ticket**: ").append(jira).append("\n- **Replacement**: ").append(replacementName).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SUNSET API: ").append(className).append("\n- **Ticket**: ").append(jira).append("\n- **Replacement**: ").append(replacementName).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (sunset): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
