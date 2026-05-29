package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Common formatting helper for VibeTags annotation formatters to eliminate platform boilerplate.
 */
final class CommonFormatterHelper {

    private CommonFormatterHelper() {}

    /**
     * Attempts to format standard markdown/plain-text platforms.
     * Returns true if the platform was formatted and handled, false otherwise.
     */
    public static boolean formatStandardPlatform(Element element, StringBuilder sb, Platform platform, String summary) {
        String className = ElementNaming.elementPath(element);
        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                return true;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append("\n");
                return true;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(summary).append("\n");
                return true;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                return true;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                return true;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): ").append(summary).append("\n");
                return true;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                return true;
            default:
                return false;
        }
    }
}
