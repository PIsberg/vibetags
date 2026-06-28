package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIPublicAPI;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIPublicAPI annotations for all platforms.
 */
public final class AIPublicAPIFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        String className = ElementNaming.elementPath(element);
        AIPublicAPI ann = element.getAnnotation(AIPublicAPI.class);
        String reason = ann == null ? "" : ann.reason();
        String summary = CommonFormatterHelper.withReason(
            "Public API surface. Preserve signature, Javadoc, backwards compatibility, and binary/source stability.", reason);

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <api>public</api>").append(CommonFormatterHelper.claudeReason(reason)).append("\n    </element>\n");
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
                sb.append("### ").append(className).append("\n- Public API surface. Preserve signature, Javadoc, and behavior without breaking backwards compatibility.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### PUBLIC API: ").append(className).append("\n- **Rule**: Exposes public API. Do not modify public signature or break backwards compatibility.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (public-api): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
