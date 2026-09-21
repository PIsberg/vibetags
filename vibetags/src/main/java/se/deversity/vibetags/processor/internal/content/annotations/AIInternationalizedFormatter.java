package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIInternationalized;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIInternationalized annotations for all platforms.
 */
public final class AIInternationalizedFormatter implements AnnotationFormatter {
    @Override
    public void render(TaggedElement element, StringBuilder sb, Platform platform) {
        String className = element.path();
        AIInternationalized ann = element.annotation(AIInternationalized.class);
        String reason = ann == null ? "" : ann.reason();
        String summary = CommonFormatterHelper.withReason(
            "Internationalization mandated. User-facing strings must not be hardcoded; retrieve from resources.", reason);

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) return;

        switch (platform) {
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <i18n>required</i18n>").append(CommonFormatterHelper.claudeReason(reason)).append("\n    </element>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Internationalization mandate. Prohibit hardcoding user-facing strings; all user-visible text must be localized.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### INTERNATIONALIZED: ").append(className).append("\n- **Rule**: Internationalization required. Do not hardcode user-facing labels or strings.\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (i18n)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
