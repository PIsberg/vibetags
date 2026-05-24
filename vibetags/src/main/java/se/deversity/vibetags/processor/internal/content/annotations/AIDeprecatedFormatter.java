package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIDeprecated;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIDeprecated annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AIDeprecatedFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIDeprecated dep = element.getAnnotation(AIDeprecated.class);
        if (dep == null) return;
        String className = ElementNaming.elementPath(element);
        String replacedBy = dep.replacedBy();
        String migrationGuide = dep.migrationGuide();
        String deadline = dep.deadline();

        StringBuilder summary = new StringBuilder();
        if (!replacedBy.isEmpty()) {
            summary.append("Replaced by: `").append(replacedBy).append("`. ");
        }
        summary.append(migrationGuide);
        if (!deadline.isEmpty()) {
            summary.append(" (Removal deadline: ").append(deadline).append(")");
        }

        switch (platform) {
            case CURSOR:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n");
                if (!replacedBy.isEmpty()) {
                    sb.append("      <replaced_by>").append(replacedBy).append("</replaced_by>\n");
                }
                sb.append("      <migration_guide>").append(migrationGuide).append("</migration_guide>\n");
                if (!deadline.isEmpty()) {
                    sb.append("      <deadline>").append(deadline).append("</deadline>\n");
                }
                sb.append("    </element>\n");
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
                sb.append("### ").append(className).append("\n");
                if (!replacedBy.isEmpty()) {
                    sb.append("- **Replaced by**: ").append(replacedBy).append("\n");
                }
                sb.append("- **Migration**: ").append(migrationGuide).append("\n");
                if (!deadline.isEmpty()) {
                    sb.append("- **Deadline**: ").append(deadline).append("\n");
                }
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### DEPRECATED: ").append(className).append("\n- **Status**: Scheduled for removal. Do not extend.\n")
                  .append(replacedBy.isEmpty() ? "" : "- **Replaced by**: " + replacedBy + "\n")
                  .append("- **Migration**: ").append(migrationGuide).append("\n")
                  .append(deadline.isEmpty() ? "" : "- **Deadline**: " + deadline + "\n").append("\n");
                break;
            case WINDSURF:
                sb.append("* `").append(className).append("` (deprecated) - ").append(summary).append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("` (deprecated): ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (deprecated): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
