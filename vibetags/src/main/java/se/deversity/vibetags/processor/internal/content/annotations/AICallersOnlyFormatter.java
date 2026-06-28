package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AICallersOnly annotations for all platforms.
 */
public final class AICallersOnlyFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AICallersOnly callersOnly = element.getAnnotation(AICallersOnly.class);
        if (callersOnly == null) return;
        String className = ElementNaming.elementPath(element);
        String[] value = callersOnly.value();
        String callers = String.join(", ", value);
        String summary = "Only callable by: [" + callers + "]";

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n      <allowed_callers>").append(Escape.xml(callers)).append("</allowed_callers>\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Allowed Callers**: ").append(callers).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### CALLERS LIMIT: ").append(className).append("\n- **Allowed Callers**: ").append(callers).append("\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (callers limited): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
