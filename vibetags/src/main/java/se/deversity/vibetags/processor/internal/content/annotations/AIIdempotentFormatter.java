package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIIdempotent;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIIdempotent annotations for all platforms.
 */
public final class AIIdempotentFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIIdempotent idempotent = element.annotation(AIIdempotent.class);
        if (idempotent == null) return;
        String className = element.path();
        String reason = idempotent.reason();
        String summary = "Idempotency guaranteed. Multiple invocations must produce the same result as one."
                       + (reason.isEmpty() ? "" : " Reason: " + reason);

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) return;

        switch (platform) {
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <idempotent>true</idempotent>\n");
                if (!reason.isEmpty()) {
                    sb.append("      <reason>").append(Escape.xml(reason)).append("</reason>\n");
                }
                sb.append("    </element>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Idempotency guaranteed. Multiple invocations must produce the same result as a single invocation.\n");
                if (!reason.isEmpty()) {
                    sb.append("- **Reason**: ").append(reason).append('\n');
                }
                sb.append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### IDEMPOTENT: ").append(className).append("\n- **Rule**: Must remain idempotent. Multiple invocations must produce the same result as one.\n")
                  .append(reason.isEmpty() ? "" : "- **Reason**: " + reason + "\n").append('\n');
                break;
            case SWEEP:
                sb.append("  - \"Idempotency requirement for ").append(Escape.json(className)).append(": ").append(Escape.json(summary)).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (idempotent)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
