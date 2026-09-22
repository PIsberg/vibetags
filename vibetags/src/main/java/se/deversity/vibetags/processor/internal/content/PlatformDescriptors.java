package se.deversity.vibetags.processor.internal.content;

// CPD-OFF: eighty-five entries of one shape are the point of a table, not duplication to factor out.

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.processor.internal.content.PlatformDescriptor.Kind;
import se.deversity.vibetags.processor.internal.content.platforms.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one table that says, per generated output, everything the processor decides about it before
 * any content exists: where it goes, whether that path is a file or a directory, whether its own
 * presence is the opt-in or another service activates it, which {@link Platform} renders it, with
 * which renderer, and how an exclusion list is labelled and written.
 *
 * <p>Those facts used to live in five parallel structures that nothing connected
 * (<a href="https://github.com/PIsberg/vibetags/issues/762">issue #762</a>): {@link Platform} held
 * the key, {@code ServiceRegistry.OPT_IN_KEYS} the opt-in flag,
 * {@code ServiceRegistry.buildServiceFileMap} the path, {@code ServiceRegistry.writesDirectory} the
 * kind, and the switch in {@link PlatformRendererRegistry} the renderer, with
 * {@code IgnoreFileRenderer} and {@code AIIgnoreFormatter} each holding a label list of its own.
 * Adding a platform meant finding all of them, and a missed one failed quietly every time: a
 * platform with no path renders content that is dropped at the write step, a path with no platform
 * is an opt-in nothing fills, and a forgotten renderer label printed the wrong wording or threw
 * where nobody looked.
 *
 * <p><strong>Append only.</strong> The order is the order
 * {@code ServiceRegistry.buildServiceFileMap} returns, which is observable: it sets the order of
 * the note a new user copies file names from, and of merge and log iteration across a reactor. It
 * is deliberately not the declaration order of {@link Platform}.
 *
 * <p>The table lives in the rendering layer because an entry names its {@link PlatformRenderer}.
 * {@code ServiceRegistry} reads it from {@code internal}, which may depend on {@code content} and
 * never the reverse; that direction is also why {@link PlatformDescriptor#relativePath()} is a
 * {@code String} and not a {@code Path}, since nothing here knows a project root.
 *
 * <p><strong>No renderer may read this class from a static initializer.</strong> Building the
 * table constructs every renderer, so a renderer whose own {@code <clinit>} asked the table a
 * question would see it half-built and get a null back, in a class-loading order that depends on
 * who touched what first. Reading it from a method, which is what
 * {@code IgnoreFileRenderer} and {@code AIIgnoreFormatter} do, is always safe.
 */
public final class PlatformDescriptors {

    private static final CursorRenderer CURSOR_RENDERER = new CursorRenderer();
    private static final ClaudeRenderer CLAUDE_RENDERER = new ClaudeRenderer();
    private static final AiExcludeRenderer AI_EXCLUDE_RENDERER = new AiExcludeRenderer();
    private static final CodexRenderer CODEX_RENDERER = new CodexRenderer();
    private static final CopilotRenderer COPILOT_RENDERER = new CopilotRenderer();
    private static final QwenRenderer QWEN_RENDERER = new QwenRenderer();
    private static final GeminiRenderer GEMINI_RENDERER = new GeminiRenderer();
    private static final LlmsRenderer LLMS_RENDERER = new LlmsRenderer();
    private static final AiderConventionsRenderer AIDER_CONVENTIONS_RENDERER = new AiderConventionsRenderer();
    private static final IgnoreFileRenderer IGNORE_FILE_RENDERER = new IgnoreFileRenderer();
    private static final WindsurfRenderer WINDSURF_RENDERER = new WindsurfRenderer();
    private static final ZedRenderer ZED_RENDERER = new ZedRenderer();
    private static final CodyRenderer CODY_RENDERER = new CodyRenderer();
    private static final MentatRenderer MENTAT_RENDERER = new MentatRenderer();
    private static final SweepRenderer SWEEP_RENDERER = new SweepRenderer();
    private static final PlandexRenderer PLANDEX_RENDERER = new PlandexRenderer();
    private static final InterpreterRenderer INTERPRETER_RENDERER = new InterpreterRenderer();
    private static final ClineSafetyRenderer CLINE_SAFETY_RENDERER = new ClineSafetyRenderer();
    private static final JunieRenderer JUNIE_RENDERER = new JunieRenderer();
    private static final ClaudeLocalRenderer CLAUDE_LOCAL_RENDERER = new ClaudeLocalRenderer();
    private static final ClaudeSkillRenderer CLAUDE_SKILL_RENDERER = new ClaudeSkillRenderer();
    private static final CodeRabbitRenderer CODERABBIT_RENDERER = new CodeRabbitRenderer();
    private static final PrAgentRenderer PR_AGENT_RENDERER = new PrAgentRenderer();
    private static final EllipsisRenderer ELLIPSIS_RENDERER = new EllipsisRenderer();
    private static final RooModesRenderer ROO_MODES_RENDERER = new RooModesRenderer();
    private static final LocksReportRenderer LOCKS_REPORT_RENDERER = new LocksReportRenderer();
    private static final GranularRenderer GRANULAR_RENDERER = new GranularRenderer();
    private static final GeminiStyleguideRenderer GEMINI_STYLEGUIDE_RENDERER = new GeminiStyleguideRenderer();
    private static final AiderConfRenderer AIDER_CONF_RENDERER = new AiderConfRenderer();
    private static final GreptileRenderer GREPTILE_RENDERER = new GreptileRenderer();
    private static final GreptileRulesRenderer GREPTILE_RULES_RENDERER = new GreptileRulesRenderer();
    private static final GreptileConfigRenderer GREPTILE_CONFIG_RENDERER = new GreptileConfigRenderer();
    private static final RoutedTestingRenderer TESTING_RENDERER = new RoutedTestingRenderer();

    /**
     * Every generated output, in the pinned service-map order. Append only; see the class
     * comment. {@code PlatformDescriptorsTest} pins the order and each entry.
     */
    public static final List<PlatformDescriptor> ALL = List.of(
        // Five other free-form Markdown outputs take the .cursorrules rendering as is, which is why
        // CURSOR_RENDERER appears against cline, firebase, void, goose and replit below. They were five
        // classes that each held a private CursorRenderer and forwarded to it (#764); naming the renderer
        // in the table says the same thing without a class to keep in step. CursorRenderer formats as
        // CURSOR whatever platform it is handed, and passes the real one to the scoped-rules index, where
        // none of the five has a governing directory, so they never collapse.
        new PlatformDescriptor("cursor", ".cursorrules", Kind.FILE, null, Platform.CURSOR, CURSOR_RENDERER, null, false),
        new PlatformDescriptor("claude", "CLAUDE.md", Kind.FILE, null, Platform.CLAUDE, CLAUDE_RENDERER, null, false),
        new PlatformDescriptor("aiexclude", ".aiexclude", Kind.FILE, null, Platform.AI_EXCLUDE, AI_EXCLUDE_RENDERER, null, true),
        // AGENTS.md. Written only as the sole AI config file, or into an existing marker pair
        // (invariant 4); resolveActiveServices drops it otherwise.
        new PlatformDescriptor("codex", "AGENTS.md", Kind.FILE, null, Platform.CODEX, CODEX_RENDERER, null, false),
        new PlatformDescriptor("gemini", "gemini_instructions.md", Kind.FILE, null, Platform.GEMINI, GEMINI_RENDERER, null, false),
        new PlatformDescriptor("copilot", ".github/copilot-instructions.md", Kind.FILE, null, Platform.COPILOT, COPILOT_RENDERER, null, false),
        new PlatformDescriptor("qwen", "QWEN.md", Kind.FILE, null, Platform.QWEN, QWEN_RENDERER, null, false),
        new PlatformDescriptor("cursor_ignore", ".cursorignore", Kind.FILE, null, Platform.CURSOR_IGNORE, IGNORE_FILE_RENDERER, "Cursor", true),
        new PlatformDescriptor("claude_ignore", ".claudeignore", Kind.FILE, null, Platform.CLAUDE_IGNORE, IGNORE_FILE_RENDERER, "Claude", true),
        new PlatformDescriptor("copilot_ignore", ".copilotignore", Kind.FILE, null, Platform.COPILOT_IGNORE, IGNORE_FILE_RENDERER, "Copilot", true),
        new PlatformDescriptor("qwen_ignore", ".qwenignore", Kind.FILE, null, Platform.QWEN_IGNORE, IGNORE_FILE_RENDERER, "Qwen", true),
        // The Codex sidecar: the one documented exception to invariant 1. Implicit, activated by
        // codex rather than by its own presence, so it has no opt-in key.
        new PlatformDescriptor("codex_config", ".codex/config.toml", Kind.FILE, "codex", Platform.CODEX_CONFIG, CODEX_RENDERER, null, false),
        new PlatformDescriptor("codex_rules", ".codex/rules/vibetags.rules", Kind.FILE, "codex", Platform.CODEX_RULES, CODEX_RENDERER, null, false),
        // Qwen's /refactor command, opted into by its own presence and not implied by QWEN.md (#655).
        // .qwen/settings.json is deliberately not mapped (#650): it is Qwen Code's own project settings
        // file, and a whole-file write erased the user's MCP servers and permissions.
        new PlatformDescriptor("qwen_refactor", ".qwen/commands/refactor.md", Kind.FILE, null, Platform.QWEN_REFACTOR, QWEN_RENDERER, null, false),
        new PlatformDescriptor("llms", "llms.txt", Kind.FILE, null, Platform.LLMS, LLMS_RENDERER, null, false),
        new PlatformDescriptor("llms_full", "llms-full.txt", Kind.FILE, null, Platform.LLMS_FULL, LLMS_RENDERER, null, false),
        new PlatformDescriptor("aider_conventions", "CONVENTIONS.md", Kind.FILE, null, Platform.AIDER_CONVENTIONS, AIDER_CONVENTIONS_RENDERER, null, false),
        new PlatformDescriptor("aider_ignore", ".aiderignore", Kind.FILE, null, Platform.AIDER_IGNORE, IGNORE_FILE_RENDERER, "Aider", true),
        // Aider's config file. Without a read: entry aider never loads the CONVENTIONS.md VibeTags
        // already writes, so this is what makes that platform do anything at all.
        new PlatformDescriptor("aider_conf", ".aider.conf.yml", Kind.FILE, null, Platform.AIDER_CONF, AIDER_CONF_RENDERER, null, false),
        // Every granular directory shares one renderer. That used to be thirteen case labels written by
        // hand, of which only twelve were ever written: GEMINI_GRANULAR was missing, so getRenderer threw
        // Unsupported platform for it and nothing failed, because GuardrailContentBuilder filters these
        // keys out before asking. A latent crash is what a forgotten case label looks like here.
        new PlatformDescriptor("cursor_granular", ".cursor/rules", Kind.DIRECTORY, null, Platform.CURSOR_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("roo_granular", ".roo/rules", Kind.DIRECTORY, null, Platform.ROO_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("trae_granular", ".trae/rules", Kind.DIRECTORY, null, Platform.TRAE_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("windsurf", ".windsurfrules", Kind.FILE, null, Platform.WINDSURF, WINDSURF_RENDERER, null, false),
        new PlatformDescriptor("zed", ".rules", Kind.FILE, null, Platform.ZED, ZED_RENDERER, null, false),
        new PlatformDescriptor("cody", ".cody/config.json", Kind.FILE, null, Platform.CODY, CODY_RENDERER, null, false),
        new PlatformDescriptor("cody_ignore", ".codyignore", Kind.FILE, null, Platform.CODY_IGNORE, IGNORE_FILE_RENDERER, "Cody", true),
        new PlatformDescriptor("supermaven_ignore", ".supermavenignore", Kind.FILE, null, Platform.SUPERMAVEN_IGNORE, IGNORE_FILE_RENDERER, "Supermaven", true),
        new PlatformDescriptor("windsurf_granular", ".windsurf/rules", Kind.DIRECTORY, null, Platform.WINDSURF_GRANULAR, GRANULAR_RENDERER, null, false),
        // Inside .windsurf/rules/: the safety tier as a trigger: always_on rule (issue #684). Implicit,
        // like cline_safety, so it has no opt-in key of its own.
        new PlatformDescriptor("windsurf_safety", ".windsurf/rules/+vibetags-safety.md", Kind.FILE, "windsurf_granular", Platform.WINDSURF_SAFETY, WINDSURF_RENDERER, null, false),
        new PlatformDescriptor("continue_granular", ".continue/rules", Kind.DIRECTORY, null, Platform.CONTINUE_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("tabnine_granular", ".tabnine/guidelines", Kind.DIRECTORY, null, Platform.TABNINE_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("amazonq_granular", ".amazonq/rules", Kind.DIRECTORY, null, Platform.AMAZONQ_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("ai_rules_granular", ".ai/rules", Kind.DIRECTORY, null, Platform.AI_RULES_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("pearai_granular", ".pearai/rules", Kind.DIRECTORY, null, Platform.PEARAI_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("mentat", ".mentatconfig.json", Kind.FILE, null, Platform.MENTAT, MENTAT_RENDERER, null, false),
        new PlatformDescriptor("sweep", "sweep.yaml", Kind.FILE, null, Platform.SWEEP, SWEEP_RENDERER, null, false),
        new PlatformDescriptor("plandex", ".plandex.yaml", Kind.FILE, null, Platform.PLANDEX, PLANDEX_RENDERER, null, false),
        new PlatformDescriptor("double_ignore", ".doubleignore", Kind.FILE, null, Platform.DOUBLE_IGNORE, IGNORE_FILE_RENDERER, "Double.bot", true),
        new PlatformDescriptor("interpreter", ".interpreter/profiles/vibetags.yaml", Kind.FILE, null, Platform.INTERPRETER, INTERPRETER_RENDERER, null, false),
        new PlatformDescriptor("codeium_ignore", ".codeiumignore", Kind.FILE, null, Platform.CODEIUM_IGNORE, IGNORE_FILE_RENDERER, "Codeium", true),
        // Ignore files for Roo Code, Continue and Augment Code, each the tool's only exclusion
        // mechanism, and each beside a rules directory VibeTags already writes (see docs/PLATFORMS.md
        // for the three that were rejected).
        new PlatformDescriptor("roo_ignore", ".rooignore", Kind.FILE, null, Platform.ROO_IGNORE, IGNORE_FILE_RENDERER, "Roo Code", true),
        new PlatformDescriptor("continue_ignore", ".continueignore", Kind.FILE, null, Platform.CONTINUE_IGNORE, IGNORE_FILE_RENDERER, "Continue", true),
        new PlatformDescriptor("augment_ignore", ".augmentignore", Kind.FILE, null, Platform.AUGMENT_IGNORE, IGNORE_FILE_RENDERER, "Augment Code", true),
        // GEMINI.md, the Gemini CLI file. Renders as GEMINI (Platform.rendersAs), so the two print the
        // same words from one formatter arm (#721, #764).
        new PlatformDescriptor("gemini_md", "GEMINI.md", Kind.FILE, null, Platform.GEMINI_MD, GEMINI_RENDERER, null, false),
        new PlatformDescriptor("antigravity_ignore", ".antigravityignore", Kind.FILE, null, Platform.ANTIGRAVITY_IGNORE, IGNORE_FILE_RENDERER, "Antigravity AI", true),
        // The legacy single .clinerules file, mutually exclusive with the directory below.
        new PlatformDescriptor("cline", ".clinerules", Kind.FILE, null, Platform.CLINE, CURSOR_RENDERER, null, false),
        // Cline's directory form, at the same path as the file. A path is one or the other, so
        // isOptedIn lets exactly one of the two activate (issue #642).
        new PlatformDescriptor("cline_granular", ".clinerules", Kind.DIRECTORY, null, Platform.CLINE_GRANULAR, GRANULAR_RENDERER, null, false),
        // Inside that directory: the safety tier, always loaded (issue #648). Implicit, like
        // codex_config under codex, so it has no opt-in key of its own.
        new PlatformDescriptor("cline_safety", ".clinerules/+vibetags-safety.md", Kind.FILE, "cline_granular", Platform.CLINE_SAFETY, CLINE_SAFETY_RENDERER, null, false),
        new PlatformDescriptor("junie", ".junie/guidelines.md", Kind.FILE, null, Platform.JUNIE, JUNIE_RENDERER, null, false),
        // Junie's current file, checked before .junie/guidelines.md. Not the root AGENTS.md: a separate
        // key, so the sole-file rule treats it like any other opt-in (#673).
        new PlatformDescriptor("junie_agents", ".junie/AGENTS.md", Kind.FILE, null, Platform.JUNIE_AGENTS, JUNIE_RENDERER, null, false),
        new PlatformDescriptor("kiro_granular", ".kiro/steering", Kind.DIRECTORY, null, Platform.KIRO_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("firebase", ".idx/airules.md", Kind.FILE, null, Platform.FIREBASE, CURSOR_RENDERER, null, false),
        new PlatformDescriptor("claude_local", "CLAUDE.local.md", Kind.FILE, null, Platform.CLAUDE_LOCAL, CLAUDE_LOCAL_RENDERER, null, false),
        new PlatformDescriptor("claude_skill", ".claude/skills/vibetags-guardrails/SKILL.md", Kind.FILE, null, Platform.CLAUDE_SKILL, CLAUDE_SKILL_RENDERER, null, false),
        // The cross-client Agent Skills location. The same SKILL.md, read by every client that scans
        // .agents/skills/ rather than only its own vendor directory.
        new PlatformDescriptor("agents_skill", ".agents/skills/vibetags-guardrails/SKILL.md", Kind.FILE, null, Platform.AGENTS_SKILL, CLAUDE_SKILL_RENDERER, null, false),
        new PlatformDescriptor("claude_granular", ".claude/rules", Kind.DIRECTORY, null, Platform.CLAUDE_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("copilot_granular", ".github/instructions", Kind.DIRECTORY, null, Platform.COPILOT_GRANULAR, GRANULAR_RENDERER, null, false),
        // Gemini granular rules (#320): lets GEMINI.md collapse to a scoped-rules index.
        new PlatformDescriptor("gemini_granular", ".gemini/rules", Kind.DIRECTORY, null, Platform.GEMINI_GRANULAR, GRANULAR_RENDERER, null, false),
        // Grok Build scoped rules. Granular only: Grok reads AGENTS.md natively, so it has no VibeTags
        // aggregate of its own and this key never collapses another file to an index.
        new PlatformDescriptor("grok_granular", ".grok/rules", Kind.DIRECTORY, null, Platform.GROK_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("antigravity_granular", ".agents/rules", Kind.DIRECTORY, null, Platform.ANTIGRAVITY_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("aiassistant_granular", ".aiassistant/rules", Kind.DIRECTORY, null, Platform.AIASSISTANT_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("augment_granular", ".augment/rules", Kind.DIRECTORY, null, Platform.AUGMENT_GRANULAR, GRANULAR_RENDERER, null, false),
        new PlatformDescriptor("zencoder_granular", ".zencoder/rules", Kind.DIRECTORY, null, Platform.ZENCODER_GRANULAR, GRANULAR_RENDERER, null, false),
        // Devin Desktop, formerly Windsurf (#671). Its preferred rules directory, beside the Windsurf
        // paths it still reads. Neither collapses .windsurfrules.
        new PlatformDescriptor("devin_granular", ".devin/rules", Kind.DIRECTORY, null, Platform.DEVIN_GRANULAR, GRANULAR_RENDERER, null, false),
        // The same always-on safety file in the preferred directory (issue #684).
        new PlatformDescriptor("devin_safety", ".devin/rules/+vibetags-safety.md", Kind.FILE, "devin_granular", Platform.DEVIN_SAFETY, WINDSURF_RENDERER, null, false),
        new PlatformDescriptor("devin_ignore", ".devinignore", Kind.FILE, null, Platform.DEVIN_IGNORE, IGNORE_FILE_RENDERER, "Devin Desktop", true),
        // replit.md, root only. The Replit Agent writes to this file itself, so VibeTags is not its only
        // author. That is survivable only because of the marker contract: the region between the markers
        // is replaced and whatever the Agent adds around it is kept. The failure mode is a stale block
        // between two compiles, not lost content.
        new PlatformDescriptor("replit", "replit.md", Kind.FILE, null, Platform.REPLIT, CURSOR_RENDERER, null, false),
        // .goosehints. goose also reads AGENTS.md, which VibeTags writes only as the sole AI config file
        // (invariant 4), so this is the file that reaches a goose user whose project also uses Claude or
        // Cursor (#610).
        new PlatformDescriptor("goose", ".goosehints", Kind.FILE, null, Platform.GOOSE, CURSOR_RENDERER, null, false),
        // Context-packer ignore files.
        new PlatformDescriptor("repomix_ignore", ".repomixignore", Kind.FILE, null, Platform.REPOMIX_IGNORE, IGNORE_FILE_RENDERER, "Repomix", true),
        new PlatformDescriptor("gitingest_ignore", ".gitingestignore", Kind.FILE, null, Platform.GITINGEST_IGNORE, IGNORE_FILE_RENDERER, "Gitingest", true),
        new PlatformDescriptor("gpt_ignore", ".gptignore", Kind.FILE, null, Platform.GPT_IGNORE, IGNORE_FILE_RENDERER, "GPT context packer", true),
        new PlatformDescriptor("ghostcoder_ignore", ".ghostcoderignore", Kind.FILE, null, Platform.GHOSTCODER_IGNORE, IGNORE_FILE_RENDERER, "Ghostcoder", true),
        new PlatformDescriptor("pieces_ignore", ".piecesignore", Kind.FILE, null, Platform.PIECES_IGNORE, IGNORE_FILE_RENDERER, "Pieces for Developers", true),
        // AI pull-request reviewers.
        new PlatformDescriptor("coderabbit", ".coderabbit.yaml", Kind.FILE, null, Platform.CODERABBIT, CODERABBIT_RENDERER, null, false),
        new PlatformDescriptor("pr_agent", ".pr_agent.toml", Kind.FILE, null, Platform.PR_AGENT, PR_AGENT_RENDERER, null, false),
        new PlatformDescriptor("ellipsis", "ellipsis.yaml", Kind.FILE, null, Platform.ELLIPSIS, ELLIPSIS_RENDERER, null, false),
        // Gemini Code Assist for GitHub: the review style guide is a separate product from the Gemini
        // CLI's GEMINI.md, with its own path.
        new PlatformDescriptor("gemini_styleguide", ".gemini/styleguide.md", Kind.FILE, null, Platform.GEMINI_STYLEGUIDE, GEMINI_STYLEGUIDE_RENDERER, null, false),
        // Greptile's PR reviewer. greptile.json is the legacy single-file form, richly hand-configured in
        // practice, so VibeTags owns only a delimited span inside two of its string values and leaves
        // every other byte alone (#639). .greptile/rules.md is the recommended form, and
        // .greptile/config.json carries its exclusions: VibeTags owns a span in ignorePatterns only
        // (#651). When both exist in the root, Greptile reads .greptile/ and ignores greptile.json
        // entirely; docs/PLATFORMS.md says so.
        new PlatformDescriptor("greptile", "greptile.json", Kind.FILE, null, Platform.GREPTILE, GREPTILE_RENDERER, null, true),
        new PlatformDescriptor("greptile_rules", ".greptile/rules.md", Kind.FILE, null, Platform.GREPTILE_RULES, GREPTILE_RULES_RENDERER, null, false),
        new PlatformDescriptor("greptile_config", ".greptile/config.json", Kind.FILE, null, Platform.GREPTILE_CONFIG, GREPTILE_CONFIG_RENDERER, null, true),
        // Editors and modes.
        new PlatformDescriptor("void", ".void/rules.md", Kind.FILE, null, Platform.VOID, CURSOR_RENDERER, null, false),
        new PlatformDescriptor("roo_modes", ".roomodes", Kind.FILE, null, Platform.ROO_MODES, ROO_MODES_RENDERER, null, false),
        // Machine-readable @AILocked report for CI diff guards. No extension, so hash markers and the
        // ordinary multi-module merge.
        new PlatformDescriptor("locks_report", ".vibetags-locks", Kind.FILE, null, Platform.LOCKS_REPORT, LOCKS_REPORT_RENDERER, null, false),
        // Lean indexed root aggregate opt-in (multi-module). The one key with no Platform and no
        // renderer: its presence only flips the reactor-root CLAUDE.md / .cursorrules / .windsurfrules /
        // copilot-instructions.md merge from embedding each module's guardrails to linking the module's
        // own scoped rule files (#788).
        new PlatformDescriptor("root_index", ".vibetags-root-index", Kind.FILE, null, null, null, null, false),
        // TESTING.md. No tool reads it by name: its presence asks for a test round's non-safety
        // guardrails to be written there instead of into the always-loaded aggregates. A .md file, so
        // HTML markers and the ordinary multi-module merge; its renderer writes nothing outside a test
        // round.
        new PlatformDescriptor("testing", "TESTING.md", Kind.FILE, null, Platform.TESTING, TESTING_RENDERER, null, false)
    );

    private static final Map<String, PlatformDescriptor> BY_KEY = indexByKey();
    private static final Map<Platform, PlatformDescriptor> BY_PLATFORM = indexByPlatform();

    private PlatformDescriptors() {}

    private static Map<String, PlatformDescriptor> indexByKey() {
        Map<String, PlatformDescriptor> map = new LinkedHashMap<>();
        for (PlatformDescriptor d : ALL) {
            map.put(d.serviceKey(), d);
        }
        return Map.copyOf(map);
    }

    private static Map<Platform, PlatformDescriptor> indexByPlatform() {
        Map<Platform, PlatformDescriptor> map = new LinkedHashMap<>();
        for (PlatformDescriptor d : ALL) {
            Platform p = d.platform();
            if (p != null) {
                map.put(p, d);
            }
        }
        return Map.copyOf(map);
    }

    /** The entry for a service key, or {@code null} when the key is not a generated output. */
    public static @Nullable PlatformDescriptor byKey(String serviceKey) {
        return BY_KEY.get(serviceKey);
    }

    /**
     * The entry for a platform. Every {@link Platform} has one, which
     * {@code PlatformServiceMapParityTest} pins, so this never answers {@code null} for a constant
     * that exists.
     */
    public static @Nullable PlatformDescriptor byPlatform(Platform platform) {
        return BY_PLATFORM.get(platform);
    }

    /** The shared renderer behind every granular rules directory. */
    public static GranularRenderer granularRenderer() {
        return GRANULAR_RENDERER;
    }
}
