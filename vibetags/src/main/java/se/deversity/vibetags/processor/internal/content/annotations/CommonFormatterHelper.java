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
     * Appends the optional rationale to a plain-text/markdown {@code summary} when present, so the
     * "why" carried by a marker annotation survives into the generated guardrail output (and thus
     * across AI sessions). Returns {@code summary} unchanged when {@code reason} is blank.
     */
    public static String withReason(String summary, String reason) {
        return (reason == null || reason.isBlank()) ? summary : summary + " Reason: " + reason;
    }

    /**
     * Returns an indented {@code <reason>…</reason>} XML fragment for the Claude format when
     * {@code reason} is present (to be inserted before the element's closing tag), or an empty
     * string otherwise.
     */
    public static String claudeReason(String reason) {
        return (reason == null || reason.isBlank()) ? "" : "\n      <reason>" + reason + "</reason>";
    }

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
