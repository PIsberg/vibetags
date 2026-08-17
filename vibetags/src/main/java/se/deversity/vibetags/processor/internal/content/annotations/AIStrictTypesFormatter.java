package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIStrictTypes;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIStrictTypes annotations for all platforms.
 */
public final class AIStrictTypesFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        String className = element.path();
        AIStrictTypes ann = element.annotation(AIStrictTypes.class);
        String reason = ann == null ? "" : ann.reason();
        String summary = CommonFormatterHelper.withReason(
            "Loose typing (Object, Map<String, Object>, raw types) is prohibited. Enforce type safety.", reason);

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <types>strict</types>").append(CommonFormatterHelper.claudeReason(reason)).append("\n    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append('\n');
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(summary).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(summary).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(summary).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append("): ").append(summary).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Prohibit loose typing. Use strongly-typed transfer objects or domain models instead of Object or Map<String, Object>.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### STRICT TYPES: ").append(className).append("\n- **Rule**: Loose typing is prohibited. Enforce explicit type-safety and strongly-typed objects.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append('\n');
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (strict-types): ").append(summary).append('\n');
                break;
            default:
                break;
        }
    }
}
