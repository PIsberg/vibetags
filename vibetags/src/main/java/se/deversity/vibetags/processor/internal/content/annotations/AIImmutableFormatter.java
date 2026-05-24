package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIImmutable annotations for all platforms.
 */
public final class AIImmutableFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIImmutable im = element.getAnnotation(AIImmutable.class);
        if (im == null) return;
        String className = ElementNaming.elementPath(element);
        String note = im.note();
        String summary = note.isEmpty() ? "immutable type" : note;

        switch (platform) {
            case CURSOR:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <type path=\"").append(className).append("\">\n");
                if (!note.isEmpty()) {
                    sb.append("      <note>").append(note).append("</note>\n");
                }
                sb.append("    </type>\n");
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
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): immutable type").append(note.isEmpty() ? "" : " — " + note).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Immutable type — never introduce non-final fields, setters, or mutating methods.\n");
                if (!note.isEmpty()) {
                    sb.append("- **Note**: ").append(note).append("\n");
                }
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### IMMUTABLE: ").append(className).append("\n- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.\n").append(note.isEmpty() ? "" : "- **Note**: " + note + "\n").append("\n");
                break;
            case WINDSURF:
                sb.append("* `").append(className).append("` (immutable)").append(note.isEmpty() ? "" : " - " + note).append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("` (immutable)").append(note.isEmpty() ? "" : ": " + note).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (immutable)").append(note.isEmpty() ? "" : ": " + note).append("\n");
                break;
            default:
                break;
        }
    }
}
