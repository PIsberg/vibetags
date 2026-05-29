package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIInputSanitized annotations for all platforms.
 */
public final class AIInputSanitizedFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIInputSanitized inputSanitized = element.getAnnotation(AIInputSanitized.class);
        if (inputSanitized == null) return;
        String className = ElementNaming.elementPath(element);
        AIInputSanitized.SanitizerType[] types = inputSanitized.value();
        String[] typesStr = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            typesStr[i] = types[i].name();
        }
        String typeList = String.join(", ", typesStr);
        String summary = "Input parameter/field must be strictly sanitized against injection attacks: [" + typeList + "]";

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <sanitization_types>").append(typeList).append("</sanitization_types>\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Sanitization Requirement**: ").append(typeList).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### INPUT SANITIZATION: ").append(className).append("\n- **Required Filters**: ").append(typeList).append("\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (sanitized): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
