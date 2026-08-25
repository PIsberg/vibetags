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
        // A bare @AIContext sets neither member, and "Focus:" with nothing after it is worse than
        // no line: it tells an agent something was meant to be here. Each half is emitted only
        // when it has content, in whichever shape the platform uses.
        String bulletedFocus = CommonFormatterHelper.clause("\n  * Focus: ", focus)
                             + CommonFormatterHelper.clause("\n  * Avoid: ", avoids);
        String dashedFocus = CommonFormatterHelper.clause("\n  - Focus: ", focus)
                           + CommonFormatterHelper.clause("\n  - Avoid: ", avoids);
        String inline = CommonFormatterHelper.clause("Focus - ", focus)
                      + (focus.isEmpty() || avoids.isEmpty() ? "" : ". ")
                      + CommonFormatterHelper.clause("Avoid - ", avoids);
        // Codex reads prose rather than the dashed form, and keeps its own wording: this arm
        // renders "Focus on X. Avoid Y." Guarding the members must not reword it — AGENTS.md is
        // generated through here, and rewording rewrites a committed file for no reason.
        StringBuilder prose = new StringBuilder();
        if (!focus.isEmpty()) {
            prose.append("Focus on ").append(focus).append('.');
        }
        if (!avoids.isEmpty()) {
            if (prose.length() > 0) {
                prose.append(' ');
            }
            prose.append("Avoid ").append(avoids).append('.');
        }
        String codexProse = prose.toString();

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append('`').append(bulletedFocus).append('\n');
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n")
                    .append(CommonFormatterHelper.element("focus", focus))
                    .append(CommonFormatterHelper.element("avoids", avoids))
                    .append("    </file>\n");
                break;
            case CODEX:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", codexProse)).append('\n');
                break;
            case COPILOT:
                sb.append("- `").append(className).append('`').append(dashedFocus).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append('`').append(bulletedFocus).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", inline)).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append(')').append(CommonFormatterHelper.clause(": ", inline)).append('\n');
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
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", inline)).append('\n');
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (context)").append(CommonFormatterHelper.clause(": ", inline)).append('\n');
                break;
            default:
                break;
        }
    }
}
