package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIThreadSafe;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIThreadSafe annotations for all platforms.
 */
public final class AIThreadSafeFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIThreadSafe ts = element.getAnnotation(AIThreadSafe.class);
        if (ts == null) return;
        String className = ElementNaming.elementPath(element);
        String strategy = ts.strategy().name();
        String note = ts.note();
        String summary = "Strategy: " + strategy + (note.isEmpty() ? "" : ". Note: " + note);

        switch (platform) {
            case CURSOR:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n      <strategy>").append(strategy).append("</strategy>\n");
                if (!note.isEmpty()) {
                    sb.append("      <note>").append(note).append("</note>\n");
                }
                sb.append("    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append("\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(summary).append("\n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): ").append(summary).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Strategy**: ").append(strategy).append("\n");
                if (!note.isEmpty()) {
                    sb.append("- **Note**: ").append(note).append("\n");
                }
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### THREAD-SAFE: ").append(className).append("\n- **Strategy**: ").append(strategy).append("\n").append(note.isEmpty() ? "" : "- **Note**: " + note + "\n").append("\n");
                break;
            case WINDSURF:
                sb.append("* `").append(className).append("` (thread-safe) - ").append(summary).append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("` (thread-safe): ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (thread-safe): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
