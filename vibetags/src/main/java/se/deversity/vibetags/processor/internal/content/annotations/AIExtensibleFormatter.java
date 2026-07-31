package se.deversity.vibetags.processor.internal.content.annotations;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIExtensible;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIExtensible annotations for all platforms.
 */
public final class AIExtensibleFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        AIExtensible extensible = element.annotation(AIExtensible.class);
        if (extensible == null) return;
        String className = element.path();
        AIExtensible.Strategy strategy = extensible.value();
        String summary = "Designed for extension via strategy/polymorphism. Do not expand conditionals/switch chains. Required Pattern: " + strategy.name();

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) {
            return;
        }

        switch (platform) {
            case CLAUDE:
                sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n      <extension_pattern>").append(Escape.xml(strategy.name())).append("</extension_pattern>\n    </file>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Extension Design**: ").append(strategy.name()).append(". Use polymorphism.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### POLYMORPHIC EXTENSION: ").append(className).append("\n- **Strategy**: ").append(strategy.name()).append("\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (extensible): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
