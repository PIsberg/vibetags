package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIExtensible annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AIExtensibleFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIExtensible extensible = element.getAnnotation(AIExtensible.class);
        if (extensible == null) return;
        String className = ElementNaming.elementPath(element);
        AIExtensible.Strategy strategy = extensible.value();
        String summary = "Designed for extension via strategy/polymorphism. Do not expand conditionals/switch chains. Required Pattern: " + strategy.name();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <extension_pattern>").append(strategy.name()).append("</extension_pattern>\n    </file>\n");
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
                sb.append("### ").append(className).append("\n- **Extension Design**: ").append(strategy.name()).append(". Use polymorphism.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### POLYMORPHIC EXTENSION: ").append(className).append("\n- **Strategy**: ").append(strategy.name()).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (extensible): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
