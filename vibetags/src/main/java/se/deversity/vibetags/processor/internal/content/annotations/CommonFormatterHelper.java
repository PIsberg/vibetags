package se.deversity.vibetags.processor.internal.content.annotations;

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Common formatting helper for VibeTags annotation formatters to eliminate platform boilerplate.
 */
final class CommonFormatterHelper {

    private CommonFormatterHelper() {}

    /**
     * Appends the optional rationale to a plain-text/markdown {@code summary} when present, so the
     * "why" carried by a marker annotation survives into the generated guardrail output (and thus
     * across AI sessions). Returns {@code summary} unchanged when {@code reason} is blank.
     */
    static String withReason(String summary, String reason) {
        return (reason == null || reason.isBlank()) ? summary : summary + " Reason: " + reason;
    }

    /**
     * Returns an indented {@code <reason>…</reason>} XML fragment for the Claude format when
     * {@code reason} is present (to be inserted before the element's closing tag), or an empty
     * string otherwise.
     */
    static String claudeReason(String reason) {
        return (reason == null || reason.isBlank()) ? "" : "\n      <reason>" + Escape.xml(reason) + "</reason>";
    }

    /**
     * Renders a markdown bullet with a bold label, or nothing at all when {@code value} is unset.
     *
     * <p>An annotation written bare — {@code @AILocked} with no {@code reason}, the form people
     * actually write — must not leave {@code - **Reason**:} with nothing after it in the generated
     * file. The label costs an agent's context window and carries no guardrail in return, so the
     * whole bullet is dropped rather than emitted empty. Pinned by {@code UnsetMemberRenderingTest}.
     */
    static String bullet(String label, String value) {
        return (value == null || value.isBlank()) ? "" : "- **" + label + "**: " + value + "\n";
    }

    /**
     * Renders an XML element indented six spaces for the Claude formats, or nothing at all when
     * {@code value} is unset, so a bare annotation does not produce {@code <reason></reason>}.
     *
     * <p>Emits the same bytes as the inline form it replaces whenever the member is populated.
     */
    static String element(String tag, String value) {
        return (value == null || value.isBlank())
            ? ""
            : "      <" + tag + ">" + Escape.xml(value) + "</" + tag + ">\n";
    }

    /**
     * {@code text} preceded by {@code separator}, or nothing at all when {@code text} is unset.
     *
     * <p>The counterpart to {@link #bullet} for the platforms that render a member inline rather
     * than under a bold label. {@code "* `Foo`" + clause(" - ", reason)} keeps the separator and
     * the value together, so a bare {@code @AILocked} renders as {@code * `Foo`} rather than
     * {@code * `Foo` - } — a line that reads as though the reason went missing rather than as
     * though it was never written. Pinned by {@code UnsetMemberRenderingTest}.
     */
    static String clause(String separator, CharSequence text) {
        String value = text == null ? null : text.toString();
        return (value == null || value.isBlank()) ? "" : separator + value;
    }

    /**
     * A {@code "Label: value"} sentence preceded by {@code lead}, or nothing when the value is
     * unset — the same rule as {@link #clause}, for the values that sit inside a summary sentence
     * rather than at the end of a line.
     */
    static String detail(String lead, String label, String value) {
        return (value == null || value.isBlank()) ? "" : lead + label + value;
    }

    /**
     * Renders the Codex bullet — the element's path in bold, then its summary — dropping the colon
     * along with the summary when there is none, so a bare annotation does not leave
     * {@code - **com.example.Foo**:} with nothing after it.
     *
     * <p>The element keeps its line either way. Its presence under the section heading is itself
     * the guardrail, and dropping the line would hide an annotation that is visibly there in the
     * source — the worse of the two failures.
     */
    static String codexBullet(String path, String summary) {
        return (summary == null || summary.isBlank())
            ? "- **" + path + "**\n"
            : "- **" + path + "**: " + summary + "\n";
    }

    /**
     * Attempts to format standard markdown/plain-text platforms.
     * Returns true if the platform was formatted and handled, false otherwise.
     */
    static boolean formatStandardPlatform(TaggedElement element, StringBuilder sb, Platform platform, String summary) {
        String className = element.path();
        // Every arm below runs the summary through clause(), so an annotation whose summary is
        // entirely made of unset members renders as the element alone rather than as the element
        // plus a separator with nothing after it.
        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append('`').append(clause(" - ", summary)).append('\n');
                return true;
            case CODEX:
                sb.append(codexBullet(className, summary));
                return true;
            case COPILOT:
                sb.append("- `").append(className).append('`').append(clause(" - ", summary)).append('\n');
                return true;
            case QWEN:
                sb.append("* `").append(className).append('`').append(clause(" - ", summary)).append('\n');
                return true;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append('`').append(clause(": ", summary)).append('\n');
                return true;
            case LLMS:
                sb.append("- [").append(element.displayName()).append("](").append(className).append(')').append(clause(": ", summary)).append('\n');
                return true;
            case ZED:
                sb.append("- `").append(className).append('`').append(clause(": ", summary)).append('\n');
                return true;
            default:
                return false;
        }
    }
}
