package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIStrictExceptions;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIStrictExceptions annotations for all platforms.
 */
public final class AIStrictExceptionsFormatter implements AnnotationFormatter {
    @Override
    public void render(TaggedElement element, StringBuilder sb, Platform platform) {
        String className = element.path();
        AIStrictExceptions ann = element.annotation(AIStrictExceptions.class);
        String reason = ann == null ? "" : ann.reason();
        String summary = CommonFormatterHelper.withReason(
            "Strict exception handling required. Catching/throwing generic Exception/Throwable is prohibited.", reason);

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) return;

        switch (platform) {
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <exceptions>strict</exceptions>").append(CommonFormatterHelper.claudeReason(reason)).append("\n    </element>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Enforce precise exception handling. Prohibit catching or throwing generic Exceptions/Throwables. Use specific or custom exceptions.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### STRICT EXCEPTIONS: ").append(className).append("\n- **Rule**: Prohibit catching or throwing generic Exception/Throwable. Use custom, domain-specific exceptions.\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (strict-exceptions)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
