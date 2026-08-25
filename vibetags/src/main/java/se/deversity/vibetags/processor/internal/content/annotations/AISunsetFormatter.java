package se.deversity.vibetags.processor.internal.content.annotations;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AISunset;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AISunset annotations for all platforms.
 */
public final class AISunsetFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AISunset sunset = element.annotation(AISunset.class);
        if (sunset == null) return;
        String className = element.path();
        String jira = sunset.jira();

        // replacement() is Class-valued and unreadable during annotation processing; the collector
        // resolved it to a type name while the compiler was still in scope.
        String replacementName = element.typeMember("AISunset.replacement", "java.lang.Object");

        String summary = "Strictly sunset/deprecated. Forbid any *new* calls or references. JIRA: " + jira + ". Replacement: `" + replacementName + "`";

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("sunset_ticket", jira))
                    .append(CommonFormatterHelper.element("replacement_target", replacementName))
                    .append("    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Sunset Status**: Active (Forbid new calls)\n")
                    .append(CommonFormatterHelper.bullet("JIRA Ticket", jira))
                    .append(CommonFormatterHelper.bullet("Replacement", replacementName)).append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SUNSET API: ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Ticket", jira))
                    .append(CommonFormatterHelper.bullet("Replacement", replacementName)).append('\n');
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (sunset): ").append(summary).append('\n');
                break;
            default:
                break;
        }
    }
}
