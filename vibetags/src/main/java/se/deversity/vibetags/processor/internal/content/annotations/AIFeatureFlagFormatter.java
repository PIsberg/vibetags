package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIFeatureFlag;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIFeatureFlag annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AIFeatureFlagFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIFeatureFlag ff = element.getAnnotation(AIFeatureFlag.class);
        if (ff == null) return;
        String className = ElementNaming.elementPath(element);
        String flag = ff.flag();
        boolean defaultValue = ff.defaultValue();
        String flagDisplay = flag.isEmpty() ? "(unspecified)" : "'" + flag + "'";
        String summary = "Gated by feature flag: " + flagDisplay + " (default: " + defaultValue + "). "
                       + "Preserve the flag check — never assume it is always on.";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n");
                if (!flag.isEmpty()) {
                    sb.append("      <flag>").append(flag).append("</flag>\n");
                }
                sb.append("      <default_value>").append(defaultValue).append("</default_value>\n");
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
                sb.append("### ").append(className).append("\n- Feature flag: ").append(flagDisplay).append(" (default: ").append(defaultValue).append(")\n");
                sb.append("- Preserve the flag check. Never assume the flag is always active. Test both enabled and disabled code paths.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### FEATURE FLAG: ").append(className).append("\n- **Flag**: ").append(flagDisplay).append(" (default: ").append(defaultValue).append(")\n- **Rule**: Never assume flag is always active. Preserve the flag check.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case SWEEP:
                sb.append("  - \"Feature flag gate for ").append(className).append(": ").append(summary).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (feature-flag): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
