package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIRegulation;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIRegulation annotations for all platforms.
 */
public final class AIRegulationFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIRegulation reg = element.annotation(AIRegulation.class);
        if (reg == null) return;
        String className = element.path();
        String standard = reg.standard();
        String clause = reg.clause();
        String description = reg.description();
        String summary = standard + (clause.isEmpty() ? "" : " " + clause) + " — " + description;

        switch (platform) {
            case CURSOR:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("standard", standard));
                if (!clause.isEmpty()) {
                    sb.append("      <clause>").append(Escape.xml(clause)).append("</clause>\n");
                }
                sb.append(CommonFormatterHelper.element("description", description)).append("    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append('\n');
                break;
            case COPILOT:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append(')').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Standard", standard));
                if (!clause.isEmpty()) {
                    sb.append("- **Clause**: ").append(clause).append('\n');
                }
                sb.append(CommonFormatterHelper.bullet("Description", description)).append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### REGULATORY: ").append(className).append('\n')
                  .append(CommonFormatterHelper.bullet("Standard", standard))
                  .append(clause.isEmpty() ? "" : "- **Clause**: " + clause + "\n")
                  .append(CommonFormatterHelper.bullet("Description", description)).append('\n');
                break;
            case WINDSURF:
                sb.append("* `").append(className).append("` (regulation)").append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case ZED:
                sb.append("- `").append(className).append("` (regulation)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (regulation)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
