package se.deversity.vibetags.processor.internal.content.annotations;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AITemporary;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AITemporary annotations for all platforms.
 */
public final class AITemporaryFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AITemporary temp = element.annotation(AITemporary.class);
        if (temp == null) return;
        String className = element.path();
        String expiresOn = temp.expiresOn();
        String reason = temp.reason();
        // A bare @AITemporary names neither a date nor a reason. That it is temporary is still the
        // guardrail, so the sentence stands and the two clauses that have no content are dropped.
        String summary = "Temporary logic/workaround."
            + CommonFormatterHelper.detail(" ", "Expires on: ", expiresOn.isEmpty() ? "" : expiresOn + ".")
            + CommonFormatterHelper.detail(" ", "Reason: ", reason);

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("temporary_expiration", expiresOn))
                    .append(CommonFormatterHelper.element("temporary_reason", reason))
                    .append("    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Temporal Expiration", expiresOn))
                    .append(CommonFormatterHelper.bullet("Reason", reason)).append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### TEMPORARY WORKAROUND: ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Expires On", expiresOn))
                    .append(CommonFormatterHelper.bullet("Reason", reason)).append('\n');
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (temporary)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
