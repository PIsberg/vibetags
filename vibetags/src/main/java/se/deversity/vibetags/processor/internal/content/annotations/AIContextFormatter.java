package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIContext annotations for all platforms.
 */
public final class AIContextFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIContext context = element.getAnnotation(AIContext.class);
        if (context == null) return;
        String className = ElementNaming.elementPath(element);
        String focus = context.focus();
        String avoids = context.avoids();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("`\n  * Focus: ").append(focus).append("\n  * Avoid: ").append(avoids).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n      <focus>").append(Escape.xml(focus)).append("</focus>\n      <avoids>").append(Escape.xml(avoids)).append("</avoids>\n    </file>\n");
                break;
            case CODEX:
                sb.append("- `").append(className).append("`: Focus on ").append(focus).append(". Avoid ").append(avoids).append(".\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("`\n  - Focus: ").append(focus).append("\n  - Avoid: ").append(avoids).append("\n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("`\n  * Focus: ").append(focus).append("\n  * Avoid: ").append(avoids).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: Focus - ").append(focus).append(". Avoid - ").append(avoids).append("\n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): Focus - ").append(focus).append(". Avoid - ").append(avoids).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Focus**: ").append(focus).append("\n- **Avoid**: ").append(avoids).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### CONTEXT: ").append(className).append("\n- **Focus**: ").append(focus).append("\n- **Avoid**: ").append(avoids).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: Focus - ").append(focus).append(". Avoid - ").append(avoids).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (context): Focus - ").append(focus).append(". Avoid - ").append(avoids).append("\n");
                break;
            default:
                break;
        }
    }
}
