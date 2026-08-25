package se.deversity.vibetags.processor.internal.content.annotations;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AICallersOnly;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AICallersOnly annotations for all platforms.
 */
public final class AICallersOnlyFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AICallersOnly callersOnly = element.annotation(AICallersOnly.class);
        if (callersOnly == null) return;
        String className = element.path();
        String[] value = callersOnly.value();
        String callers = String.join(", ", value);
        String summary = "Only callable by: [" + callers + "]";

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("allowed_callers", callers))
                    .append("    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Allowed Callers", callers)).append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### CALLERS LIMIT: ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Allowed Callers", callers)).append('\n');
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (callers limited): ").append(summary).append('\n');
                break;
            default:
                break;
        }
    }
}
