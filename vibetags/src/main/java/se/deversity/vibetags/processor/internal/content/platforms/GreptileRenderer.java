package se.deversity.vibetags.processor.internal.content.platforms;

import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.FormatterRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRenderer;
import se.deversity.vibetags.processor.internal.content.RenderingContext;
import se.deversity.vibetags.processor.internal.content.WholeFileMerge;
import se.deversity.vibetags.processor.model.GuardrailModel;
import se.deversity.vibetags.processor.model.TaggedElement;

import java.util.ArrayList;
import java.util.List;

/**
 * PlatformRenderer for Greptile's root {@code greptile.json}.
 *
 * <p>This does <em>not</em> render the file. A real {@code greptile.json} carries thirty-odd
 * hand-set fields (strictness, comment types, branch and author filters, output sections), and a
 * whole-file write would erase every one of them on the first compile after opt-in. What this
 * renders is the part VibeTags owns, one JSON array of lines per owned key:
 *
 * <pre>
 * {
 *   "instructions": [
 *     "Enforce the following VibeTags guardrails. Flag any change that violates them:",
 *     "- `com.example.Foo` ..."
 *   ],
 *   "ignorePatterns": [
 *     "**&#47;Generated.java"
 *   ]
 * }
 * </pre>
 *
 * <p>{@code GuardrailFileWriter} joins each array into the text of a delimited span and splices that
 * span into the matching string value of the user's document, leaving every other byte where it
 * was. Lines rather than one string, so the reactor merge can union them per key without decoding
 * JSON in the rendering layer.
 *
 * <p>Every line goes through {@link Escape#json}: the instruction text includes annotation
 * attributes, some of them copied out of third-party dependency JARs.
 */
public final class GreptileRenderer implements PlatformRenderer {

    /** The owned key for the reviewer's natural-language instructions. */
    public static final String INSTRUCTIONS_KEY = "instructions";

    /** The owned key for the {@code .gitignore}-syntax list of paths the reviewer skips. */
    public static final String IGNORE_PATTERNS_KEY = "ignorePatterns";

    static final String PREAMBLE = "Enforce the following VibeTags guardrails. Flag any change that violates them:";
    static final String EMPTY_BODY = "No VibeTags guardrails are currently declared.";

    private static final WholeFileMerge MERGE = WholeFileMerge.jsonLineArrays(EMPTY_BODY);

    @Override
    public String render(GuardrailModel model, Platform platform, RenderingContext context) {
        List<String> instructions = new ArrayList<>();
        List<String> block = GuardrailInstructionBlock.lines(model);
        if (block.isEmpty()) {
            instructions.add(EMPTY_BODY);
        } else {
            instructions.add(PREAMBLE);
            for (String line : block) {
                instructions.add("- " + line);
            }
        }

        StringBuilder globs = new StringBuilder();
        for (TaggedElement e : model.ignore()) {
            FormatterRegistry.ignore().format(e, globs, Platform.GREPTILE);
        }
        List<String> patterns = new ArrayList<>();
        for (String line : globs.toString().split("\n", -1)) {
            if (!line.isBlank() && !patterns.contains(line.strip())) {
                patterns.add(line.strip());
            }
        }

        StringBuilder sb = new StringBuilder(1024).append("{\n");
        appendArray(sb, INSTRUCTIONS_KEY, instructions);
        sb.append(",\n");
        appendArray(sb, IGNORE_PATTERNS_KEY, patterns);
        return sb.append("\n}\n").toString();
    }

    /**
     * The owned lines are unioned per key across modules. Concatenating two of these documents is
     * not JSON, and keeping only the compiling module's would publish one module's view of the
     * project, which is what every whole-file format did in a reactor until #265.
     */
    @Override
    public WholeFileMerge wholeFileMerge() {
        return MERGE;
    }

    private static void appendArray(StringBuilder sb, String key, List<String> lines) {
        sb.append("  \"").append(key).append("\": [");
        if (lines.isEmpty()) {
            sb.append(']');
            return;
        }
        sb.append('\n');
        for (int i = 0; i < lines.size(); i++) {
            sb.append("    \"").append(Escape.json(lines.get(i))).append('"')
              .append(i == lines.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ]");
    }
}
