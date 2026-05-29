package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIMemoryBudget;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIMemoryBudget annotations for all platforms.
 */
public final class AIMemoryBudgetFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIMemoryBudget memoryBudget = element.getAnnotation(AIMemoryBudget.class);
        if (memoryBudget == null) return;
        String className = ElementNaming.elementPath(element);
        AIMemoryBudget.AllocationPolicy policy = memoryBudget.value();
        String summary = "Strict memory budget policy: " + policy.name() + ". Minimize or prevent runtime allocations.";

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <allocation_policy>").append(policy.name()).append("</allocation_policy>\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Memory Policy**: ").append(policy.name()).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### MEMORY BUDGET: ").append(className).append("\n- **Policy**: ").append(policy.name()).append("\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (memory-budget): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
