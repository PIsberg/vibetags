package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AISandboxOnly;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AISandboxOnly annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AISandboxOnlyFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AISandboxOnly sandboxOnly = element.getAnnotation(AISandboxOnly.class);
        if (sandboxOnly == null) return;
        String className = ElementNaming.elementPath(element);
        String summary = "Strictly sandbox or test environment only. Production code must never import or invoke.";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <policy>Sandbox or test only. Do not invoke from production.</policy>\n    </file>\n");
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
                sb.append("### ").append(className).append("\n- **Scope**: Sandbox/testing environments only. Never use in production.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### SANDBOX ONLY: ").append(className).append("\n- **Policy**: Sandbox/testing environments only.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (sandbox-only): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
