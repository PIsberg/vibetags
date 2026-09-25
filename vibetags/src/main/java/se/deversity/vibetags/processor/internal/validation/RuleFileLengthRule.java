package se.deversity.vibetags.processor.internal.validation;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A generated rule file longer than the tool that loads it accepts (issues #695, #701).
 *
 * <p>docs.devin.ai, Memories &amp; Rules, gives the workspace rule files in {@code .devin/rules/}
 * and {@code .windsurf/rules/} "Workspace rule files are limited to 12,000 characters each" (re-checked
 * 2026-09-25) and does not say whether a longer file is cut or dropped. antigravity.google/docs/rules
 * says of {@code .agents/rules/} "Antigravity truncates any single rule file that exceeds 24,000
 * bytes", counted after its {@code @} includes are expanded, which VibeTags never writes (#850; the
 * page said 12,000 characters when #701 was built on it). Either way the guardrails past the cap do
 * not reach the agent and the build said nothing, which is worst for the always-on
 * {@code +vibetags-safety.md} of Devin Desktop and Windsurf, the one file that carries every safety
 * bucket. The legacy {@code .windsurfrules} file and the 6,000-character global rules file are not
 * covered: the docs give the first no cap, and VibeTags never writes the second. No other granular
 * directory VibeTags writes has a per-file cap in its vendor's docs; docs/PLATFORMS.md lists what was
 * checked.
 *
 * <p>Each directory is measured in its vendor's unit. Devin Desktop's characters are counted as
 * {@link String#length()}, UTF-16 code units, which is also what a JavaScript string reports for the
 * same text; the docs do not define a character, and this count is never lower than the code point
 * count, so any disagreement errs toward warning. Antigravity's bytes are the UTF-8 encoding, and the
 * two differ in both directions: 12,001 ASCII characters are well within 24,000 bytes, while 9,000 CJK
 * characters are over it.
 *
 * <p>Like {@link DuplicateYamlKeyRule}, this is not a {@link ValidationRule}: it reads an output file
 * after it is written, not an annotated element during a live round. The class that runs it,
 * {@code RuleFileLengthWarner}, only finds the files and reports.
 */
public final class RuleFileLengthRule {

    /** Devin Desktop's documented per-file cap for {@code .devin/rules/} and {@code .windsurf/rules/}, in characters. */
    public static final int WORKSPACE_RULE_FILE_LIMIT = 12_000;

    /** Antigravity's documented per-file cap for {@code .agents/rules/}, in UTF-8 bytes. */
    public static final int ANTIGRAVITY_RULE_FILE_BYTES = 24_000;

    private static final String ANTIGRAVITY = "antigravity_granular";

    /**
     * The service keys of the directories the cap applies to: Devin Desktop's, preferred directory
     * first, then Antigravity's.
     */
    public static final List<String> CAPPED_DIRECTORIES =
        List.of("devin_granular", "windsurf_granular", "antigravity_granular");

    private RuleFileLengthRule() {
    }

    /** Whether {@code serviceKey}'s cap is in bytes rather than characters. */
    public static boolean countsBytes(String serviceKey) {
        return ANTIGRAVITY.equals(serviceKey);
    }

    /** The documented cap for {@code serviceKey}'s directory, in its unit. */
    public static int limit(String serviceKey) {
        return countsBytes(serviceKey) ? ANTIGRAVITY_RULE_FILE_BYTES : WORKSPACE_RULE_FILE_LIMIT;
    }

    /** The length of {@code content} as {@code serviceKey}'s cap counts it: UTF-8 bytes, or UTF-16 code units. */
    public static int length(String serviceKey, String content) {
        return countsBytes(serviceKey) ? content.getBytes(StandardCharsets.UTF_8).length : content.length();
    }

    /** Whether {@code content} is longer than {@code serviceKey}'s cap. A file of exactly the cap is within it. */
    public static boolean exceedsLimit(String serviceKey, String content) {
        return length(serviceKey, content) > limit(serviceKey);
    }

    /**
     * The diagnostic for an oversized file, without the {@code VibeTags: } prefix.
     *
     * @param serviceKey the directory's service key, one of {@link #CAPPED_DIRECTORIES}
     * @param shownPath  the file as shown to the user, a {@code /}-separated path
     * @param length     its length in the directory's unit, from {@link #length}
     * @param safetyFile whether it is named as the always-on safety file, which no role split shrinks;
     *                   only Devin Desktop and Windsurf have one, so elsewhere the name changes nothing
     */
    public static String message(String serviceKey, String shownPath, int length, boolean safetyFile) {
        boolean antigravity = countsBytes(serviceKey);
        String remedy = safetyFile && !antigravity
            ? "Opting into .windsurfrules moves the safety guardrails there and leaves this file a pointer to it."
            : "Split the role that groups these elements in .vibetags-roles, or shorten the annotation text.";
        if (antigravity) {
            return shownPath + " is " + length + " bytes, over the " + ANTIGRAVITY_RULE_FILE_BYTES
                + " bytes Antigravity reads from a rules file. It truncates the rest, so guardrails past that"
                + " point do not reach the agent. " + remedy;
        }
        return shownPath + " is " + length + " characters, over the " + WORKSPACE_RULE_FILE_LIMIT
            + " Devin Desktop and Windsurf accept for a workspace rule file. Their docs do not"
            + " say whether the rest is cut or the file is dropped; either way guardrails in it may not reach"
            + " the agent. " + remedy;
    }
}
