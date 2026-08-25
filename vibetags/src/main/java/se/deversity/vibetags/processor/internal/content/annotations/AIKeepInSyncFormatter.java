package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIKeepInSync;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIKeepInSync annotations for all platforms.
 *
 * <p>Renderings state whether the mirrors are enforced. An unenforced mirror set is exactly the
 * case where a partial edit compiles, passes CI, and desyncs silently — so saying so is the
 * difference between a note and a warning.
 */
public final class AIKeepInSyncFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIKeepInSync keepInSync = element.annotation(AIKeepInSync.class);
        if (keepInSync == null) return;
        String className = element.path();
        String mirrors = String.join(", ", keepInSync.mirrors());
        String reason = keepInSync.reason();
        String enforcedBy = keepInSync.enforcedBy();
        // With no mirrors named there is no "at:" to complete, but the warning that nothing
        // enforces the agreement is still the point of the annotation, so it stays.
        String sites = mirrors.isEmpty()
            ? "This element is mirrored elsewhere."
            : "Editing this requires the same edit at: " + mirrors + ".";
        String summary = sites
                       + (reason.isEmpty() ? "" : " " + reason + ".")
                       + (enforcedBy.isEmpty()
                            ? " Nothing checks this automatically — a partial change desyncs silently."
                            : " Enforced by " + enforcedBy + ".");

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n");
                for (String mirror : keepInSync.mirrors()) {
                    sb.append("      <mirror>").append(Escape.xml(mirror)).append("</mirror>\n");
                }
                if (!reason.isEmpty()) {
                    sb.append("      <reason>").append(Escape.xml(reason)).append("</reason>\n");
                }
                sb.append("      <enforced-by>")
                  .append(Escape.xml(enforcedBy.isEmpty() ? "nothing — unenforced" : enforcedBy))
                  .append("</enforced-by>\n")
                  .append("    </element>\n");
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
                sb.append("### ").append(className).append('\n');
                if (keepInSync.mirrors().length > 0) {
                    sb.append("- **Must stay in sync with**:\n");
                    for (String mirror : keepInSync.mirrors()) {
                        sb.append("  - ").append(mirror).append('\n');
                    }
                }
                if (!reason.isEmpty()) {
                    sb.append("- **Reason**: ").append(reason).append('\n');
                }
                sb.append("- **Enforced by**: ")
                  .append(enforcedBy.isEmpty() ? "nothing — a partial edit desyncs silently" : enforcedBy)
                  .append('\n')
                  .append("- The element itself is free to change; changing only one side is the bug.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### KEEP IN SYNC: ").append(className).append('\n')
                  .append(CommonFormatterHelper.bullet("Mirrors", mirrors))
                  .append(reason.isEmpty() ? "" : "- **Reason**: " + reason + "\n")
                  .append("- **Enforced by**: ")
                  .append(enforcedBy.isEmpty() ? "nothing — verify by hand" : enforcedBy).append('\n')
                  .append("- **Rule**: Change all sites in the same commit, or none.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case SWEEP:
                sb.append("  - \"Keep in sync: editing ").append(Escape.json(className))
                  .append(" requires the same edit at ").append(Escape.json(mirrors)).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (mirrored)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
