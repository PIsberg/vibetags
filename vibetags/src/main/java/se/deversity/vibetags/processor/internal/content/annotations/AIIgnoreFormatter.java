package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.annotations.AIIgnore;
import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIIgnore annotations for all platforms.
 *
 * <p>The reason is rendered wherever the platform's output is prose, and omitted where it is a
 * path list a tool parses — the fifteen {@code *_IGNORE} globs, {@code .aiexclude} and Mentat's
 * JSON have nowhere to put a sentence.
 *
 * <p>{@link AIIgnore#reason()} has a default, and the default is not printed: it says "Excluded
 * from AI context", which is what the section heading above the entry already says. Printing it
 * would add a line of repetition to every entry in every project that never set one, so only a
 * reason somebody actually wrote reaches the file.
 */
public final class AIIgnoreFormatter implements AnnotationFormatter {

    /**
     * The annotation's own default, read from the annotation rather than copied. A literal here
     * would be a second declaration of the default and would silently stop matching the first time
     * the wording changed — at which point every file would start carrying it again.
     */
    private static final String DEFAULT_REASON = defaultReason();

    @Override
    public void format(TaggedElement element, StringBuilder sb, Platform platform) {
        String className = element.path();
        String simpleName = element.simpleName();
        String globPattern = "**/" + simpleName + ".java\n";

        // javac never hands back a null String from an annotation member, but a mocked or
        // synthesized instance does, and formatters run against those in tests and in tooling that
        // builds a model without a compiler. VibeTags is advisory: a null here must render one
        // line less, not throw out of somebody's build.
        AIIgnore ignore = element.annotation(AIIgnore.class);
        String reason = ignore == null || ignore.reason() == null ? "" : ignore.reason();
        boolean explained = !reason.isBlank() && !reason.equals(DEFAULT_REASON);
        String suffix = explained ? " - " + reason : "";

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append('`').append(suffix).append('\n');
                break;
            case CLAUDE:
                if (explained) {
                    sb.append("    <file path=\"").append(Escape.xml(className)).append("\">\n      <reason>")
                      .append(Escape.xml(reason)).append("</reason>\n    </file>\n");
                } else {
                    sb.append("    <file path=\"").append(Escape.xml(className)).append("\"/>\n");
                }
                break;
            case CODEX:
                sb.append("- `").append(className).append('`').append(suffix).append('\n');
                break;
            case COPILOT:
                sb.append("- `").append(className).append('`').append(suffix).append('\n');
                break;
            case QWEN:
                sb.append("* `").append(className).append('`').append(suffix).append('\n');
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append('`').append(suffix).append('\n');
                break;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className)
                  .append("): excluded from AI context").append(suffix).append('\n');
                break;
            case LLMS_FULL:
                sb.append("### ").append(className)
                  .append("\n- Excluded from AI context entirely - treat as non-existent")
                  .append(explained ? "\n- Reason: " + reason : "").append("\n\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### IGNORE: ").append(className)
                  .append("\n- **Instruction**: This element is strictly excluded from AI context. Do not reference it.")
                  .append(explained ? "\n- **Reason**: " + reason : "").append("\n\n");
                break;
            case ZED:
                sb.append("- `").append(className).append('`').append(suffix).append('\n');
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(Escape.json(className)).append("\"},\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (excluded): treat as non-existent")
                  .append(suffix).append('\n');
                break;
            // Ignore/exclusion files get standard globs:
            case AI_EXCLUDE:
            case CURSOR_IGNORE:
            case CLAUDE_IGNORE:
            case COPILOT_IGNORE:
            case QWEN_IGNORE:
            case CODY_IGNORE:
            case SUPERMAVEN_IGNORE:
            case DOUBLE_IGNORE:
            case CODEIUM_IGNORE:
            case ANTIGRAVITY_IGNORE:
            case AIDER_IGNORE:
            case REPOMIX_IGNORE:
            case GITINGEST_IGNORE:
            case GPT_IGNORE:
            case GHOSTCODER_IGNORE:
            case PIECES_IGNORE:
                sb.append(globPattern);
                break;
            default:
                break;
        }
    }

    /**
     * {@code AIIgnore.reason()}'s declared default. Read once at class-load; if the annotation ever
     * loses the member the formatter falls back to printing every reason, which is noisy rather
     * than wrong.
     */
    private static String defaultReason() {
        try {
            Object declared = AIIgnore.class.getDeclaredMethod("reason").getDefaultValue();
            return declared instanceof String s ? s : "";
        } catch (NoSuchMethodException e) {
            return "";
        }
    }
}
