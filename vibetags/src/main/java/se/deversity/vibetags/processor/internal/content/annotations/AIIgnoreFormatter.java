package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIIgnore annotations for all platforms.
 */
public final class AIIgnoreFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        String className = ElementNaming.elementPath(element);
        String simpleName = element.getSimpleName().toString();
        String globPattern = "**/" + simpleName + ".java\n";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` \n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\"/>\n");
                break;
            case CODEX:
                sb.append("- `").append(className).append("` \n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` \n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("` \n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("` \n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): excluded from AI context\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Excluded from AI context entirely - treat as non-existent\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### IGNORE: ").append(className).append("\n- **Instruction**: This element is strictly excluded from AI context. Do not reference it.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("` \n");
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(className).append("\"},\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (excluded): treat as non-existent\n");
                break;
            // Ignore/exclusion files get standard globs:
            case AI_EXCLUDE:
            case CURSOR_IGNORE:
            case CLAUDE_IGNORE:
            case COPILOT_IGNORE:
            case QWEN_IGNORE:
            case CODY_IGNORE:
            case SUPERMAVEN_IGNORE:
            case DOUBLE_IGNORE:
            case CODEIUM_IGNORE:
            case ANTIGRAVITY_IGNORE:
            case AIDER_IGNORE:
            case REPOMIX_IGNORE:
            case GITINGEST_IGNORE:
            case GPT_IGNORE:
            case GHOSTCODER_IGNORE:
            case PIECES_IGNORE:
                sb.append(globPattern);
                break;
            default:
                break;
        }
    }
}
