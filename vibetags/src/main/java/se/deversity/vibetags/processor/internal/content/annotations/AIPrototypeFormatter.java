package se.deversity.vibetags.processor.internal.content.annotations;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIPrototype;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIPrototype annotations for all platforms.
 */
public final class AIPrototypeFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIPrototype prototype = element.annotation(AIPrototype.class);
        if (prototype == null) return;
        String className = element.path();
        String reason = prototype.reason();
        String summary = CommonFormatterHelper.withReason(
            "Experimental prototype class. Strict constraints (test coverage, i18n) are suspended. Stable production code must never depend on it.", reason);

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n      <status>Experimental Prototype</status>").append(CommonFormatterHelper.claudeReason(reason)).append("\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Scope**: Experimental Prototype. Bypasses strict validation rules.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### EXPERIMENTAL PROTOTYPE: ").append(className).append("\n- **Policy**: Prototype stub (suspends strict QA constraints).\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (prototype): ").append(summary).append('\n');
                break;
            default:
                break;
        }
    }
}
