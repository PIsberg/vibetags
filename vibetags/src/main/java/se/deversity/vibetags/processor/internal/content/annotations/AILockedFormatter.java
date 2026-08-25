package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AILocked;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AILocked annotations for all platforms.
 */
public final class AILockedFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AILocked locked = element.annotation(AILocked.class);
        if (locked == null) return;
        String className = element.path();
        String reason = locked.reason();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - Reason: ").append(reason).append('\n');
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("reason", reason))
                    .append("    </file>\n");
                break;
            case AI_EXCLUDE:
                sb.append("**/").append(element.simpleName()).append(".java\n");
                break;
            case CODEX:
                sb.append(CommonFormatterHelper.codexBullet(className, reason));
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
                sb.append("#### LOCKED: ").append(className).append("\n- **Status**: Locked (Do Not Edit)\n")
                    .append(CommonFormatterHelper.bullet("Reason", reason)).append('\n');
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(reason).append('\n');
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(Escape.json(className)).append("\", \"reason\": \"").append(Escape.json(reason)).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Do not modify ").append(Escape.json(className)).append(": ").append(Escape.json(reason)).append("\"\n");
                break;
            case PLANDEX:
                sb.append("    - path: \"").append(Escape.json(className)).append("\"\n      reason: \"").append(Escape.json(reason)).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (locked): ").append(reason).append('\n');
                break;
            default:
                break;
        }
    }
}
