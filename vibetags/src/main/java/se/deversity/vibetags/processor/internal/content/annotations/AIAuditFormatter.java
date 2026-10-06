package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIAudit annotations for all platforms.
 */
public final class AIAuditFormatter implements AnnotationFormatter {
    @Override
    public void render(TaggedElement element, StringBuilder sb, Platform platform) {
        AIAudit audit = element.annotation(AIAudit.class);
        if (audit == null) return;
        String[] checkFor = audit.checkFor();
        if (checkFor.length == 0) return;
        String className = element.path();
        String checkForJoined = String.join(", ", checkFor);

        switch (platform) {
            case CURSOR:
            case CODEX:
            case QWEN:
            case WINDSURF:
                sb.append("* `").append(className).append("`\n  - Required Checks: ").append(checkForJoined).append('\n');
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n");
                for (String v : checkFor) {
                    sb.append("      <vulnerability_check>").append(Escape.xml(v)).append("</vulnerability_check>\n");
                }
                sb.append("    </file>\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("`\n  - Required Checks: ").append(checkForJoined).append('\n');
                break;
            case GEMINI_MD:
                // The blank line that separates two blocks opens each block rather than closing it:
                // a trailing one doubled the gap before the next heading, which brings its own (#723).
                sb.append("\nFile: `").append(className).append("`\nCritical Vulnerabilities to Prevent:");
                for (String v : checkFor) {
                    sb.append("\n- ").append(v);
                }
                sb.append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append("): check for ").append(checkForJoined).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Required Checks**: ").append(checkForJoined).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SECURITY AUDIT: ").append(className).append("\n- **Required Checks**: ").append(checkForJoined).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("` — check for: ").append(checkForJoined).append('\n');
                break;
            case CODERABBIT:
                sb.append("- `").append(className).append("` (audit): check for ").append(checkForJoined).append('\n');
                break;
            default:
                break;
        }
    }
}
