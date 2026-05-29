package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIPure;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIPure annotations for all platforms.
 */
public final class AIPureFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIPure pure = element.getAnnotation(AIPure.class);
        if (pure == null) return;
        String className = ElementNaming.elementPath(element);
        String summary = "Must remain a pure function. Forbid assignments to enclosing state, fields, or static members.";

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <policy>Pure function: no side effects, deterministic.</policy>\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Requirement**: Mathematically pure function. No side effects.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### PURE FUNCTION: ").append(className).append("\n- **Policy**: Pure function (no state mutations allowed).\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (pure): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
