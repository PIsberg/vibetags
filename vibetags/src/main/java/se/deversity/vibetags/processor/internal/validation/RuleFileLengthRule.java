package se.deversity.vibetags.processor.internal.validation;

import java.util.List;

/**
 * A generated rule file longer than Devin Desktop, formerly Windsurf, accepts (issue #695).
 *
 * <p>docs.devin.ai, Memories &amp; Rules, gives the workspace rule files in {@code .devin/rules/}
 * and {@code .windsurf/rules/} "Limited to 12,000 characters per file". It does not say whether a
 * longer file is cut or dropped. Either way the guardrails past the cap do not reach the agent and
 * the build said nothing, which is worst for the always-on {@code +vibetags-safety.md}, the one file
 * that carries every safety bucket. The legacy {@code .windsurfrules} file and the 6,000-character
 * global rules file are not covered: the docs give the first no cap, and VibeTags never writes the
 * second.
 *
 * <p>Characters are counted as {@link String#length()}, UTF-16 code units, which is also what a
 * JavaScript string reports for the same text. The docs do not define a character; this count is
 * never lower than the code point count, so any disagreement errs toward warning, and it is lower
 * than a UTF-8 byte count only for non-ASCII text.
 *
 * <p>Like {@link DuplicateYamlKeyRule}, this is not a {@link ValidationRule}: it reads an output file
 * after it is written, not an annotated element during a live round. The class that runs it,
 * {@code RuleFileLengthWarner}, only finds the files and reports.
 */
public final class RuleFileLengthRule {

    /** The documented per-file cap for a workspace rule file, in characters. */
    public static final int WORKSPACE_RULE_FILE_LIMIT = 12_000;

    /** The service keys of the directories the cap applies to, preferred directory first. */
    public static final List<String> CAPPED_DIRECTORIES = List.of("devin_granular", "windsurf_granular");

    private RuleFileLengthRule() {
    }

    /** The length of {@code content} as this check counts it: UTF-16 code units. */
    public static int length(String content) {
        return content.length();
    }

    /** Whether {@code content} is longer than the documented cap. A file of exactly the cap is within it. */
    public static boolean exceedsLimit(String content) {
        return length(content) > WORKSPACE_RULE_FILE_LIMIT;
    }

    /**
     * The diagnostic for an oversized file, without the {@code VibeTags: } prefix.
     *
     * @param shownPath  the file as shown to the user, a {@code /}-separated path
     * @param length     its length, from {@link #length}
     * @param safetyFile whether it is the always-on safety file, which no role split shrinks
     */
    public static String message(String shownPath, int length, boolean safetyFile) {
        String remedy = safetyFile
            ? "Opting into .windsurfrules moves the safety guardrails there and leaves this file a pointer to it."
            : "Split the role that groups these elements in .vibetags-roles, or shorten the annotation text.";
        return shownPath + " is " + length + " characters, over the " + WORKSPACE_RULE_FILE_LIMIT
            + " Devin Desktop and Windsurf accept for a workspace rule file. Their docs do not say whether "
            + "the rest is cut or the file is dropped; either way guardrails in it may not reach the agent. "
            + remedy;
    }
}
