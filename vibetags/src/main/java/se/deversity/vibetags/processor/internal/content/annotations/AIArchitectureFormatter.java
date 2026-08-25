package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIArchitecture;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIArchitecture annotations for all platforms.
 */
public final class AIArchitectureFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIArchitecture arch = element.annotation(AIArchitecture.class);
        if (arch == null) return;
        String className = element.path();
        String belongsTo = arch.belongsTo();
        String[] cannotRef = arch.cannotReference();
        String cannotRefStr = String.join(", ", cannotRef);
        // A bare @AIArchitecture names no layer, so the sentence about layers is not written at
        // all rather than written around an empty code span.
        String layer = CommonFormatterHelper.detail("", "Belongs to layer: ", belongsTo.isEmpty() ? "" : "`" + belongsTo + "`");
        String prohibited = cannotRef.length > 0 ? "Prohibited from referencing: [" + cannotRefStr + "]" : "";
        String summary = layer.isEmpty() ? prohibited
            : layer + (prohibited.isEmpty() ? "" : ". " + prohibited);

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("belongs_to", belongsTo));
                for (String r : cannotRef) {
                    sb.append("      <cannot_reference>").append(Escape.xml(r)).append("</cannot_reference>\n");
                }
                sb.append("    </element>\n");
                break;
            case CODEX:
                sb.append(CommonFormatterHelper.codexBullet(className, summary));
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
                    .append(CommonFormatterHelper.bullet("Belongs to Layer", belongsTo));
                if (cannotRef.length > 0) {
                    sb.append("- **Prohibited References**: ").append(cannotRefStr).append('\n');
                }
                sb.append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### ARCHITECTURE LAYER: ").append(className).append('\n')
                  .append(CommonFormatterHelper.bullet("Layer", belongsTo))
                  .append(cannotRef.length > 0 ? "- **Cannot Reference**: " + cannotRefStr + "\n" : "").append('\n');
                break;
            case ZED:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (architecture)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
