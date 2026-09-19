package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AIStrictClasspath;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIStrictClasspath annotations for all platforms.
 */
public final class AIStrictClasspathFormatter implements AnnotationFormatter {
    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        String className = element.path();
        AIStrictClasspath ann = element.annotation(AIStrictClasspath.class);
        String reason = ann == null ? "" : ann.reason();
        String summary = CommonFormatterHelper.withReason(
            "Strict compile-time dependency/classpath constraints. Dynamic loading and reflection hacks prohibited.", reason);

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) return;

        switch (platform) {
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n      <classpath>strict</classpath>").append(CommonFormatterHelper.claudeReason(reason)).append("\n    </element>\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- Strict classpath integrity. Prohibit dynamic runtime class loading, reflections, or external JAR injection.\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### STRICT CLASSPATH: ").append(className).append("\n- **Rule**: Enforce strict classpath integrity. Dynamic loading or custom classloaders are prohibited.\n\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (strict-classpath)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }
}
