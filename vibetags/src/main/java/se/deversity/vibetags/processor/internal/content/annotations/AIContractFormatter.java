package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIContract annotations for all platforms.
 */
public final class AIContractFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIContract contract = element.annotation(AIContract.class);
        if (contract == null) return;
        String className = element.path();
        String reason = contract.reason();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(reason).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("reason", reason))
                    .append("    </element>\n");
                break;
            case CODEX:
                sb.append(CommonFormatterHelper.codexBullet(className, reason));
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(reason).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(reason).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(reason).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append("): ").append(reason).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Reason", reason)).append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### CONTRACT: ").append(className).append("\n- **Constraint**: Signature is frozen. Do not change method names, parameter types, return types, or checked exceptions.\n")
                    .append(CommonFormatterHelper.bullet("Reason", reason)).append('\n');
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(reason).append('\n');
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(Escape.json(className)).append("\", \"reason\": \"").append(Escape.json(reason)).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Contract-frozen signature for ").append(Escape.json(className)).append(": do not change method name, parameters, return type, or checked exceptions\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (contract): signature frozen — ").append(reason).append('\n');
                break;
            default:
                break;
        }
    }
}
