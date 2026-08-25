package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIPerformance;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIPerformance annotations for all platforms.
 */
public final class AIPerformanceFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIPerformance perf = element.annotation(AIPerformance.class);
        if (perf == null) return;
        String className = element.path();
        String constraint = perf.constraint();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", constraint)).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("constraint", constraint))
                    .append("    </element>\n");
                break;
            case CODEX:
                sb.append(CommonFormatterHelper.codexBullet(className, constraint));
                break;
            case COPILOT:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", constraint)).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", constraint)).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", constraint)).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append(')').append(CommonFormatterHelper.clause(": ", constraint)).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Constraint", constraint)).append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### PERFORMANCE CONSTRAINTS: ").append(className).append("\n- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.\n")
                    .append(CommonFormatterHelper.bullet("Constraint", constraint)).append('\n');
                break;
            case ZED:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", constraint)).append('\n');
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(Escape.json(className)).append("\", \"constraint\": \"").append(Escape.json(constraint)).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Performance constraint for ").append(Escape.json(className)).append(": ").append(Escape.json(constraint)).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (performance)").append(CommonFormatterHelper.clause(": ", constraint)).append('\n');
                break;
            default:
                break;
        }
    }
}
