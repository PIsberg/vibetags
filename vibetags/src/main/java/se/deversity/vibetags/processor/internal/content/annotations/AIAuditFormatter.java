package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIAudit;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIAudit annotations for all platforms.
 */
public final class AIAuditFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIAudit audit = element.getAnnotation(AIAudit.class);
        if (audit == null) return;
        String[] checkFor = audit.checkFor();
        if (checkFor.length == 0) return;
        String className = ElementNaming.elementPath(element);
        String checkForJoined = String.join(", ", checkFor);

        switch (platform) {
            case CURSOR:
            case CODEX:
            case QWEN:
            case WINDSURF:
                sb.append("* `").append(className).append("`\n  - Required Checks: ").append(checkForJoined).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n");
                for (String v : checkFor) {
                    sb.append("      <vulnerability_check>").append(Escape.xml(v)).append("</vulnerability_check>\n");
                }
                sb.append("    </file>\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("`\n  - Required Checks: ").append(checkForJoined).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("File: `").append(className).append("`\nCritical Vulnerabilities to Prevent:");
                for (String v : checkFor) {
                    sb.append("\n- ").append(v);
                }
                sb.append("\n\n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): check for ").append(checkForJoined).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Required Checks**: ").append(checkForJoined).append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SECURITY AUDIT: ").append(className).append("\n- **Required Checks**: ").append(checkForJoined).append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("` — check for: ").append(checkForJoined).append("\n");
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(Escape.json(className)).append("\", \"checks\": [").append(buildJsonStringArray(checkFor)).append("]},\n");
                break;
            case SWEEP:
                sb.append("  - \"Security audit required for ").append(Escape.json(className)).append(": ").append(Escape.json(checkForJoined)).append("\"\n");
                break;
            case PLANDEX:
                // checks is a YAML flow sequence — quote+escape each item so a value containing
                // ']' / ',' / '"' cannot break out of the list. (buildJsonStringArray quotes each
                // element; YAML double-quoted scalars use the same escapes as JSON.)
                sb.append("    - path: \"").append(Escape.json(className)).append("\"\n      checks: [").append(buildJsonStringArray(checkFor)).append("]\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (audit): check for ").append(checkForJoined).append("\n");
                break;
            default:
                break;
        }
    }

    private static String buildJsonStringArray(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("\"").append(Escape.json(v)).append("\"");
        }
        return sb.toString();
    }
}
