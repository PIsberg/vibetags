package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIObservability;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIObservability annotations for all platforms.
 */
public final class AIObservabilityFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIObservability obs = element.getAnnotation(AIObservability.class);
        if (obs == null) return;
        String className = ElementNaming.elementPath(element);
        String[] metrics = obs.metrics();
        String[] traces = obs.traces();
        String[] logs = obs.logs();
        String note = obs.note();

        StringBuilder summary = new StringBuilder();
        if (metrics.length > 0) summary.append("Metrics: ").append(String.join(", ", metrics)).append(". ");
        if (traces.length > 0)  summary.append("Traces: ").append(String.join(", ", traces)).append(". ");
        if (logs.length > 0)    summary.append("Logs: ").append(String.join(", ", logs)).append(". ");
        if (!note.isEmpty())    summary.append("Note: ").append(note);

        switch (platform) {
            case CURSOR:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n");
                for (String m : metrics) sb.append("      <metric>").append(m).append("</metric>\n");
                for (String t : traces)  sb.append("      <trace>").append(t).append("</trace>\n");
                for (String l : logs)    sb.append("      <log>").append(l).append("</log>\n");
                if (!note.isEmpty()) sb.append("      <note>").append(note).append("</note>\n");
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
                sb.append("### ").append(className).append("\n");
                if (metrics.length > 0) sb.append("- **Metrics**: ").append(String.join(", ", metrics)).append("\n");
                if (traces.length > 0)  sb.append("- **Traces**: ").append(String.join(", ", traces)).append("\n");
                if (logs.length > 0)    sb.append("- **Logs**: ").append(String.join(", ", logs)).append("\n");
                if (!note.isEmpty())    sb.append("- **Note**: ").append(note).append("\n");
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### OBSERVABILITY: ").append(className).append("\n- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard/alert.\n- **Details**: ").append(summary).append("\n\n");
                break;
            case WINDSURF:
                sb.append("* `").append(className).append("` (observability) - ").append(summary).append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("` (observability): ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (observability): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
