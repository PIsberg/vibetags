package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIPerformance annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AIPerformanceFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIPerformance perf = element.getAnnotation(AIPerformance.class);
        if (perf == null) return;
        String className = ElementNaming.elementPath(element);
        String constraint = perf.constraint();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(constraint).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n      <constraint>").append(constraint).append("</constraint>\n    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(constraint).append("\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("`: ").append(constraint).append("\n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(constraint).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(constraint).append("\n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): ").append(constraint).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Constraint**: ").append(constraint).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### PERFORMANCE CONSTRAINTS: ").append(className).append("\n- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.\n- **Constraint**: ").append(constraint).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(constraint).append("\n");
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(className).append("\", \"constraint\": \"").append(constraint).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Performance constraint for ").append(className).append(": ").append(constraint).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (performance): ").append(constraint).append("\n");
                break;
            default:
                break;
        }
    }
}
