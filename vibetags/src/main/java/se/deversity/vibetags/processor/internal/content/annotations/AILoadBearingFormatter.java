package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AILoadBearing;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AILoadBearing annotations for all platforms.
 *
 * <p>The invariant leads every rendering and the failure mode follows it: "this looks wrong" is
 * only actionable once an agent knows what breaks, otherwise the tidy-up happens anyway.
 */
public final class AILoadBearingFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AILoadBearing loadBearing = element.annotation(AILoadBearing.class);
        if (loadBearing == null) return;
        String className = element.path();
        String invariant = loadBearing.invariant();
        String breaksIf = loadBearing.breaksIf();
        boolean suppressAudit = loadBearing.suppressAudit();
        String summary = "Looks removable but is deliberate."
                       + CommonFormatterHelper.detail(" ", "Invariant: ", invariant)
                       + (breaksIf.isEmpty() ? "" : " Breaks if changed: " + breaksIf)
                       + (suppressAudit ? " Not a defect — do not flag." : "");

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("invariant", invariant));
                if (!breaksIf.isEmpty()) {
                    sb.append("      <breaks-if>").append(Escape.xml(breaksIf)).append("</breaks-if>\n");
                }
                if (suppressAudit) {
                    sb.append("      <suppress-audit>true</suppress-audit>\n");
                }
                sb.append("    </element>\n");
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
                sb.append("### ").append(className).append("\n- This code is deliberate, not accidental.\n")
                    .append(CommonFormatterHelper.bullet("Invariant", invariant));
                if (!breaksIf.isEmpty()) {
                    sb.append("- **Breaks if changed**: ").append(breaksIf).append('\n');
                }
                if (suppressAudit) {
                    sb.append("- Audit tooling should stop reporting this element — the oddity is by design.\n");
                }
                sb.append("- Edits are allowed as long as the invariant survives.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### LOAD-BEARING: ").append(className).append('\n')
                  .append(CommonFormatterHelper.bullet("Invariant", invariant))
                  .append(breaksIf.isEmpty() ? "" : "- **Breaks if changed**: " + breaksIf + "\n")
                  .append(suppressAudit ? "- **Audit**: Not a defect. Do not flag.\n" : "")
                  .append("- **Rule**: Refactor freely, but preserve the invariant.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case SWEEP:
                sb.append("  - \"Load-bearing: ").append(Escape.json(className)).append(" must preserve: ")
                  .append(Escape.json(invariant)).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (load-bearing)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
