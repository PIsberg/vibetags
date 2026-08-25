package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIPrivacy;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIPrivacy annotations for all platforms.
 */
public final class AIPrivacyFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIPrivacy privacy = element.annotation(AIPrivacy.class);
        if (privacy == null) return;
        String className = element.path();
        String reason = privacy.reason();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(reason).append('\n');
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("reason", reason))
                    .append("    </element>\n");
                break;
            case CODEX:
                sb.append("- `").append(className).append("`: ").append(reason).append('\n');
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(reason).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(reason).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(reason).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append("): ").append(reason).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Reason", reason)).append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### PRIVACY/PII: ").append(className).append("\n- **Safety Rule**: Never log or expose runtime values of this element.\n")
                    .append(CommonFormatterHelper.bullet("Reason", reason)).append('\n');
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(reason).append('\n');
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(Escape.json(className)).append("\", \"reason\": \"").append(Escape.json(reason)).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"PII protection required for ").append(Escape.json(className)).append(": never log or expose runtime values\"\n");
                break;
            case PLANDEX:
                // Same list-entry shape as AILockedFormatter's PLANDEX case; PlandexRenderer
                // nests these under its "  privacy:" key.
                sb.append("    - path: \"").append(Escape.json(className)).append("\"\n      reason: \"").append(Escape.json(reason)).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (privacy): ").append(reason).append('\n');
                break;
            default:
                break;
        }
    }
}
