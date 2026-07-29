package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIThreadAffinity;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIThreadAffinity annotations for all platforms.
 *
 * <p>Every rendering says explicitly that adding a lock is the wrong fix. That is the failure this
 * annotation exists to prevent: an agent told to "make it thread-safe" synchronises, when the
 * requirement is not mutual exclusion but which thread runs the call.
 */
public final class AIThreadAffinityFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIThreadAffinity affinity = element.getAnnotation(AIThreadAffinity.class);
        if (affinity == null) return;
        String className = ElementNaming.elementPath(element);
        String thread = affinity.thread();
        String where = describe(affinity.value(), thread);
        String marshalVia = affinity.marshalVia();
        String symptom = affinity.symptomIfViolated();
        String summary = "Pinned to " + where + ". NOT thread-safe — adding a lock is the wrong fix."
                       + (marshalVia.isEmpty() ? "" : " Marshal via " + marshalVia + ".")
                       + (symptom.isEmpty() ? "" : " If violated: " + symptom);

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n");
                sb.append("      <affinity>").append(Escape.xml(where)).append("</affinity>\n");
                if (!marshalVia.isEmpty()) {
                    sb.append("      <marshal-via>").append(Escape.xml(marshalVia)).append("</marshal-via>\n");
                }
                if (!symptom.isEmpty()) {
                    sb.append("      <symptom-if-violated>").append(Escape.xml(symptom)).append("</symptom-if-violated>\n");
                }
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
                sb.append("### ").append(className).append("\n- **Thread affinity**: ").append(where).append("\n");
                sb.append("- This is the inverse of thread-safety: safe on one thread, unsafe on every other.\n");
                sb.append("- Do NOT add synchronization to \"fix\" this — move the call, do not lock it.\n");
                if (!marshalVia.isEmpty()) {
                    sb.append("- **Marshal via**: ").append(marshalVia).append("\n");
                }
                if (!symptom.isEmpty()) {
                    sb.append("- **Symptom if violated**: ").append(symptom).append("\n");
                }
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### THREAD AFFINITY: ").append(className).append("\n")
                  .append("- **Pinned to**: ").append(where).append("\n")
                  .append(marshalVia.isEmpty() ? "" : "- **Marshal via**: " + marshalVia + "\n")
                  .append(symptom.isEmpty() ? "" : "- **Symptom if violated**: " + symptom + "\n")
                  .append("- **Rule**: Not thread-safe. Do not add locks; call it from the correct thread.\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case SWEEP:
                sb.append("  - \"Thread affinity: ").append(Escape.json(className)).append(" runs only on ")
                  .append(Escape.json(where)).append(". Do not add locks.\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (thread affinity): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }

    /** Human-readable thread constraint; NAMED falls back to the enum wording when unnamed. */
    private static String describe(AIThreadAffinity.Affinity affinity, String thread) {
        switch (affinity) {
            case MAIN_ONLY:       return "the main/UI thread only";
            case NEVER_MAIN:      return "any thread except the main/UI thread";
            case BACKGROUND_ONLY: return "a background thread only";
            case NAMED:           return thread.isEmpty() ? "one specific named thread" : "the " + thread + " thread only";
            default:              return affinity.name();
        }
    }
}
