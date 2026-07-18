package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AISunset annotations for all platforms.
 */
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

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n      <sunset_ticket>").append(Escape.xml(jira)).append("</sunset_ticket>\n      <replacement_target>").append(Escape.xml(replacementName)).append("</replacement_target>\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Sunset Status**: Active (Forbid new calls)\n- **JIRA Ticket**: ").append(jira).append("\n- **Replacement**: ").append(replacementName).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SUNSET API: ").append(className).append("\n- **Ticket**: ").append(jira).append("\n- **Replacement**: ").append(replacementName).append("\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (sunset): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
