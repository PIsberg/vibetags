package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIImmutable;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIImmutable annotations for all platforms.
 */
public final class AIImmutableFormatter implements AnnotationFormatter {
    @Override
    public void render(TaggedElement element, StringBuilder sb, Platform platform) {
        AIImmutable im = element.annotation(AIImmutable.class);
        if (im == null) return;
        String className = element.path();
        String note = im.note();
        String summary = note.isEmpty() ? "immutable type" : note;

        switch (platform) {
            case CURSOR, QWEN:
                sb.append("* `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case CLAUDE:
                renderClaude(className, note, sb);
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append('\n');
                break;
            case COPILOT:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(" - ", summary)).append('\n');
                break;
            case GEMINI_MD:
                sb.append("- `").append(className).append('`').append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append("): immutable type").append(note.isEmpty() ? "" : " — " + note).append('\n');
                break;
            case LLMS_FULL:
                renderLlmsFull(className, note, sb);
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### IMMUTABLE: ").append(className).append("\n- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.\n").append(note.isEmpty() ? "" : "- **Note**: " + note + "\n").append('\n');
                break;
            case WINDSURF:
                sb.append("* `").append(className).append("` (immutable)").append(note.isEmpty() ? "" : " - " + note).append('\n');
                break;
            case ZED, CODERABBIT:
                sb.append("- `").append(className).append("` (immutable)").append(note.isEmpty() ? "" : ": " + note).append('\n');
                break;
            default:
                break;
        }
    }

    private static void renderClaude(String className, String note, StringBuilder sb) {
        sb.append("    <type path=\"").append(Escape.xml(className)).append("\">\n");
        if (!note.isEmpty()) {
            sb.append("      <note>").append(Escape.xml(note)).append("</note>\n");
        }
        sb.append("    </type>\n");
    }

    private static void renderLlmsFull(String className, String note, StringBuilder sb) {
        sb.append("### ").append(className).append("\n- Immutable type — never introduce non-final fields, setters, or mutating methods.\n");
        if (!note.isEmpty()) {
            sb.append("- **Note**: ").append(note).append('\n');
        }
        sb.append('\n');
    }
}
