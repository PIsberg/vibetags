package se.deversity.vibetags.processor.internal;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The outputs VibeTags still writes but will stop writing in the next major version, and the one
 * warning that tells an opted-in consumer so (#641; the removal is tracked in #645).
 *
 * <p>Each row names a tool that has moved on: a retired product, or a file its vendor no longer
 * documents. They are deprecated rather than removed because removing a service stops an opted-in
 * consumer's file regenerating, and a file that silently stops tracking the annotations is worse
 * than one nobody reads. The warning makes the removal visible a release before it lands.
 *
 * <p>This table is the only place the deprecation is stated in code. The evidence behind each row
 * lives in docs/PLATFORMS.md, which the warning points at.
 *
 * <p><strong>Once per compilation, not once per build.</strong> The warning is raised from the
 * root service resolution, which runs once per javac invocation, so a reactor prints it once per
 * module that compiles. Collapsing that to once per build would need state that outlives a
 * compilation, and a static set in a Gradle daemon outlives the build as well: the second build of
 * the same project would print nothing. One multi-line warning per compilation matches the AGENTS.md
 * note raised from the same method.
 */
public final class DeprecatedServices {

    /**
     * One deprecated output.
     *
     * @param file        the path as the user sees it, relative to the root, a directory with a
     *                    trailing {@code /}; pinned against
     *                    {@link ServiceRegistry#buildServiceFileMap} by DeprecatedServicesTest
     * @param why         the vendor fact, as one clause
     * @param advice      what to do instead, as one clause naming the replacement
     * @param replacement the replacement file(s) for the log event, comma-separated, no spaces
     */
    record Notice(String file, String why, String advice, String replacement) {}

    private static final Map<String, Notice> NOTICES = notices();

    private DeprecatedServices() {}

    private static Map<String, Notice> notices() {
        Map<String, Notice> m = new LinkedHashMap<>();
        m.put("gemini", new Notice("gemini_instructions.md",
            "no Google product documents reading this file",
            "use GEMINI.md for the Gemini CLI or .gemini/styleguide.md for Gemini Code Assist",
            "GEMINI.md,.gemini/styleguide.md"));
        // Cody: Sourcegraph ended Free and Pro only, and Cody Enterprise is still supported (#677),
        // so the notice rests on that plus the file being absent from Sourcegraph's docs.
        m.put("cody", new Notice(".cody/config.json",
            "Sourcegraph ended Cody Free and Pro on 23 July 2025, and its docs do not describe this file;"
                + " Cody Enterprise continues, and its docs replace custom commands with the Prompt Library",
            "Sourcegraph points Free and Pro users to Amp, which reads AGENTS.md",
            "AGENTS.md"));
        m.put("cody_ignore", new Notice(".codyignore",
            "Sourcegraph ended Cody Free and Pro on 23 July 2025, and its docs do not describe this file;"
                + " Cody Enterprise excludes content through admin Context Filters",
            "Sourcegraph points Free and Pro users to Amp, which reads AGENTS.md",
            "AGENTS.md"));
        // Supermaven: the sunset post keeps free autocomplete running for existing JetBrains and
        // Neovim users, so "discontinued" would overstate it (#677).
        m.put("supermaven_ignore", new Notice(".supermavenignore",
            "Supermaven announced its sunset on 21 November 2025, keeping only free autocomplete for"
                + " existing JetBrains and Neovim users",
            "it recommends VS Code users move to Cursor, whose Tab reads .cursorignore",
            ".cursorignore"));
        m.put("cline", new Notice(".clinerules",
            "Cline's current docs describe a .clinerules/ directory, not this single file",
            "use the .clinerules/ directory; Cline also reads .cursorrules, .windsurfrules and AGENTS.md",
            ".clinerules/"));
        // Void: the README of voideditor/void opens "Void is now deprecated", and the repository is
        // archived (last push 2026-06-02). Its convertToLLMMessageService read .voidrules, so this path was
        // never Void's own (#665).
        m.put("void", new Notice(".void/rules.md",
            "Void's README says it is deprecated and its repository is archived, and Void itself read .voidrules,"
                + " not this file",
            "Void names no successor, only a list of community forks; move the guardrails to the file"
                + " of the editor you use now",
            "none"));
        // Long-tail outputs, each confirmed at the vendor before deprecating (#666). Where the
        // vendor offers no file-based replacement the log event records replacement=none.
        m.put("mentat", new Notice(".mentatconfig.json",
            "the Mentat CLI repository is archived (AbanteAI/archive-old-cli-mentat), and it read"
                + " .mentat_config.json, not this file",
            "no tool reads this file, so there is nothing to move to",
            "none"));
        m.put("sweep", new Notice("sweep.yaml",
            "Sweep's README now describes a JetBrains assistant rather than the GitHub App that read"
                + " sweep.yaml, and the app's docs no longer load",
            "Sweep documents no replacement for this file",
            "none"));
        m.put("plandex", new Notice(".plandex.yaml",
            "Plandex's source never names this file, and Plandex Cloud has been winding down since"
                + " 3 October 2025",
            "load guardrail files into a plan explicitly, for example with plandex load AGENTS.md",
            "AGENTS.md"));
        m.put("pearai_granular", new Notice(".pearai/rules/",
            "PearAI's app repositories are archived, and its docs name only .pearaiignore, never this"
                + " directory",
            "PearAI documents no rules directory to move to",
            "none"));
        m.put("ghostcoder_ignore", new Notice(".ghostcoderignore",
            "the Ghostcoder repository now redirects to moatless-tools, a research project that names"
                + " no such file",
            "no tool reads this file, so there is nothing to move to",
            "none"));
        m.put("double_ignore", new Notice(".doubleignore",
            "Double's documentation describes no ignore file",
            "Double offers no file-based exclusion to move to",
            "none"));
        m.put("pieces_ignore", new Notice(".piecesignore",
            "Pieces' full documentation describes no ignore file; it excludes applications in its"
                + " settings",
            "use the application exclusions in Pieces' own settings",
            "none"));
        m.put("ai_rules_granular", new Notice(".ai/rules/",
            "no tool or published convention reads this directory",
            "the cross-tool file that tools do read is AGENTS.md",
            "AGENTS.md"));
        // Claude Code: code.claude.com/docs/llms-full.txt has no mention of .claudeignore; the
        // permissions page documents Read deny rules as the way to keep a path from Claude (#667).
        m.put("claude_ignore", new Notice(".claudeignore",
            "Claude Code's documentation never mentions this file",
            "Claude Code keeps files from Claude with Read deny rules under permissions.deny in"
                + " .claude/settings.json",
            ".claude/settings.json"));
        // Copilot: docs.github.com's "Excluding content from GitHub Copilot" configures exclusions in
        // repository, organization or enterprise settings, and the docs search finds no
        // .copilotignore (#668).
        m.put("copilot_ignore", new Notice(".copilotignore",
            "GitHub's Copilot documentation never mentions this file; exclusions are configured in"
                + " settings, on Copilot Business and Enterprise",
            "set the paths under the repository's Settings, Copilot, Content exclusion",
            "none"));
        // Antigravity: none of the 90 pages in antigravity.google/llms.txt mentions this file; its
        // IDE settings page documents "Respect .gitignore", and its permissions page read_file Deny
        // rules (#670).
        m.put("antigravity_ignore", new Notice(".antigravityignore",
            "Antigravity's documentation never mentions this file",
            "Antigravity keeps files from its agent with read_file Deny permission rules, or with"
                + " .gitignore and its Respect .gitignore setting",
            ".gitignore"));
        return Collections.unmodifiableMap(m);
    }

    /** The deprecated service keys, in table order. */
    public static Set<String> keys() {
        return NOTICES.keySet();
    }

    /** The user-facing path of each deprecated output, keyed by service key. */
    static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        NOTICES.forEach((key, notice) -> files.put(key, notice.file()));
        return files;
    }

    /**
     * Raises one WARNING naming every deprecated output in {@code active}, and logs one
     * {@code platform.deprecated} event per output. Does nothing when none is active.
     */
    public static void warnIfOptedIn(Messager messager, @Nullable Logger log, Set<String> active) {
        StringBuilder lines = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Notice> e : NOTICES.entrySet()) {
            if (!active.contains(e.getKey())) {
                continue;
            }
            Notice n = e.getValue();
            lines.append("\n  ").append(n.file()).append(": ").append(n.why())
                .append("; ").append(n.advice()).append('.');
            count++;
            if (log != null) {
                log.warn("platform.deprecated key={} file={} replacement={}",
                    e.getKey(), n.file(), n.replacement());
            }
        }
        if (count == 0) {
            return;
        }
        boolean one = count == 1;
        // build.yml's gradle-multimodule warning gate matches "opted-in outputs are deprecated" in
        // this first line, both to exclude it and to require it; rephrasing it breaks that gate.
        messager.printMessage(Diagnostic.Kind.WARNING,
            "VibeTags: " + (one ? "an opted-in output is" : count + " opted-in outputs are")
                + " deprecated. VibeTags still writes " + (one ? "it" : "them")
                + ", and will stop in the next major version:" + lines
                + "\n  To keep the guardrails, create the replacement, move any hand-written content"
                + " across, and delete the deprecated file or directory. The evidence is in docs/PLATFORMS.md.");
    }
}
