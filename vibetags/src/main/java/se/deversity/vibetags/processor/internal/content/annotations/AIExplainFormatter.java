package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIExplain;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIExplain annotations for all platforms.
 */
public final class AIExplainFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIExplain explain = element.getAnnotation(AIExplain.class);
        if (explain == null) return;
        String className = ElementNaming.elementPath(element);
        AIExplain.ComplexityLevel level = explain.value();
        String summary = "Requires step-by-step mathematical or logical explanation (Chain-of-Thought) of all changes. Complexity: " + level.name();

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n      <explanation_required>").append(Escape.xml(level.name())).append("</explanation_required>\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Explanation Needed**: Yes, Chain-of-Thought (Complexity: ").append(level.name()).append(")\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### EXPLAIN RATIONALE: ").append(className).append("\n- **Complexity**: ").append(level.name()).append("\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (explain): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
