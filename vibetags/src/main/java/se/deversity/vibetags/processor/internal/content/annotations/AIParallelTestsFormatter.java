package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIParallelTests;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIParallelTests annotations for all platforms.
 */
public final class AIParallelTestsFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        String className = ElementNaming.elementPath(element);
        AIParallelTests ann = element.getAnnotation(AIParallelTests.class);
        String reason = ann == null ? "" : ann.reason();
        String summary = CommonFormatterHelper.withReason(
            "Strict test isolation required. No shared mutable state or external resource conflicts.", reason);

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <isolation>strict</isolation>").append(CommonFormatterHelper.claudeReason(reason)).append("\n    </element>\n");
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
                sb.append("### ").append(className).append("\n- Enforce strict test isolation and thread safety. Do not share mutable state between parallel test cases.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### TEST ISOLATION: ").append(className).append("\n- **Rule**: Strict test isolation required. No shared mutable state, specific order, or resource conflicts.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (test-isolation): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
