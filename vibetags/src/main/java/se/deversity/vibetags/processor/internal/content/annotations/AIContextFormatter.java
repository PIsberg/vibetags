package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIContext annotations for all platforms.
 */
public final class AIContextFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIContext context = element.annotation(AIContext.class);
        if (context == null) return;
        String className = element.path();
        String focus = context.focus();
        String avoids = context.avoids();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("`\n  * Focus: ").append(focus).append("\n  * Avoid: ").append(avoids).append('\n');
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("focus", focus))
                    .append(CommonFormatterHelper.element("avoids", avoids))
                    .append("    </file>\n");
                break;
            case CODEX:
                sb.append("- `").append(className).append("`: Focus on ").append(focus).append(". Avoid ").append(avoids).append(".\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("`\n  - Focus: ").append(focus).append("\n  - Avoid: ").append(avoids).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append("`\n  * Focus: ").append(focus).append("\n  * Avoid: ").append(avoids).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: Focus - ").append(focus).append(". Avoid - ").append(avoids).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append("): Focus - ").append(focus).append(". Avoid - ").append(avoids).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Focus", focus))
                    .append(CommonFormatterHelper.bullet("Avoid", avoids)).append('\n');
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### CONTEXT: ").append(className).append('\n')
                    .append(CommonFormatterHelper.bullet("Focus", focus))
                    .append(CommonFormatterHelper.bullet("Avoid", avoids)).append('\n');
                break;
            case ZED:
                sb.append("- `").append(className).append("`: Focus - ").append(focus).append(". Avoid - ").append(avoids).append('\n');
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (context): Focus - ").append(focus).append(". Avoid - ").append(avoids).append('\n');
                break;
            default:
                break;
        }
    }
}
