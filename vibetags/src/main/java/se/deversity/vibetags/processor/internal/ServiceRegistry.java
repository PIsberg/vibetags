package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIContext;
import se.deversity.vibetags.processor.VibeTagsLogger;
import se.deversity.vibetags.processor.internal.content.Platform;
import se.deversity.vibetags.processor.internal.content.PlatformRendererRegistry;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps logical AI-platform service keys to their output file paths and resolves which services
 * are "active" based on which files already exist on disk (file-existence opt-in model).
 */
@AIContext(
    focus = "Maps platform service keys to output file paths; resolves active services by checking file existence on disk",
    avoids = "Creating output files that do not already exist — file presence on disk is the user's explicit opt-in signal"
)
public final class ServiceRegistry {

    /** Subset of service keys whose presence on disk activates a service. */
    private static final Set<String> OPT_IN_KEYS = Set.of(
        "cursor", "claude", "aiexclude", "codex", "gemini", "copilot", "qwen",
        // Qwen's /refactor command: opted into by its own presence, not implied by QWEN.md (#655)
        "qwen_refactor",
        "cursor_ignore", "claude_ignore", "copilot_ignore", "qwen_ignore",
        "llms", "llms_full", "aider_conventions", "aider_ignore",
        "cursor_granular", "roo_granular", "trae_granular",
        // v0.7.0 platforms
        "windsurf", "zed", "cody", "cody_ignore", "supermaven_ignore",
        "windsurf_granular", "continue_granular", "tabnine_granular",
        "amazonq_granular", "ai_rules_granular",
        // v0.8.0 platforms
        "pearai_granular", "mentat", "sweep", "plandex",
        "double_ignore", "interpreter", "codeium_ignore",
        // Ignore files for Roo Code, Continue and Augment Code, each the tool's only
        // exclusion mechanism (see docs/PLATFORMS.md for the three that were rejected)
        "roo_ignore", "continue_ignore", "augment_ignore",
        // v0.9.6 platforms
        "gemini_md", "antigravity_ignore",
        // v0.9.7 platforms
        "cline", "junie", "kiro_granular",
        // Junie's current file, checked before .junie/guidelines.md. Not the root AGENTS.md: a
        // separate key, so the sole-file rule treats it like any other opt-in (#673)
        "junie_agents",
        // Cline's .clinerules/ directory, mutually exclusive with the .clinerules file above
        "cline_granular",
        // Firebase AI
        "firebase",
        // Claude Code local override, Skill, and granular rules; Copilot granular instructions
        "claude_local", "claude_skill", "claude_granular", "copilot_granular",
        // Gemini granular rules (#320): lets GEMINI.md collapse to a scoped-rules index
        "gemini_granular",
        // Grok Build scoped rules. Granular only: Grok reads AGENTS.md natively, so it has no
        // VibeTags aggregate of its own and this key never collapses another file to an index.
        "grok_granular",
        // 2026-09 platform sweep: three granular directories and one aggregate
        "antigravity_granular", "aiassistant_granular", "augment_granular", "goose",
        // Cross-client Agent Skills location, Zencoder scoped rules, Replit Agent context file
        "agents_skill", "zencoder_granular", "replit",
        // Devin Desktop, formerly Windsurf (#671). Its preferred rules directory, beside the
        // Windsurf paths it still reads, and its ignore file. Neither collapses .windsurfrules.
        "devin_granular", "devin_ignore",
        // Context-packer ignore files
        "repomix_ignore", "gitingest_ignore", "gpt_ignore", "ghostcoder_ignore", "pieces_ignore",
        // AI pull-request reviewers
        "coderabbit", "pr_agent", "ellipsis",
        // Editors & modes
        "void", "roo_modes",
        // Machine-readable @AILocked report for CI diff guards
        "locks_report",
        // Gemini Code Assist for GitHub: the review style guide is a separate product from the
        // Gemini CLI's GEMINI.md, with its own path.
        "gemini_styleguide",
        // Aider's config file. Without a read: entry aider never loads the CONVENTIONS.md
        // VibeTags already writes, so this is what makes that platform do anything at all.
        "aider_conf",
        // Greptile's PR reviewer. greptile.json is the legacy single-file form, richly hand-configured
        // in practice, so VibeTags owns only a delimited span inside two of its string values and
        // leaves every other byte alone (#639). .greptile/rules.md is the recommended form, and
        // .greptile/config.json carries its exclusions: VibeTags owns a span in ignorePatterns only (#651).
        "greptile", "greptile_rules", "greptile_config",
        // Lean indexed root aggregate (multi-module): link to per-module rules instead of embedding
        "root_index",
        // TESTING.md. No tool reads it by name: its presence asks for a test round's non-safety
        // guardrails to be written there instead of into the always-loaded aggregates.
        "testing"
    );

    /**
     * The always-loaded safety file inside a granular directory whose rule files load only on a
     * glob match: Cline's {@code .clinerules/} (issue #648), and Devin Desktop's
     * {@code .devin/rules/} and {@code .windsurf/rules/} (issue #684).
     *
     * <p>Every per-element rule file there loads only when a matching file is in the task's
     * context, so a project on the directory alone had nowhere to keep the six safety buckets always
     * loaded (invariant 6). This file carries them in the shape each tool loads on every request: no
     * front matter for Cline, {@code trigger: always_on} for Devin Desktop.
     *
     * <p>The leading {@code +} is load-bearing. Element stems are {@code [A-Za-z0-9-]}
     * ({@code ElementNaming.granularQName}), role stems {@code [A-Za-z0-9._-]}
     * ({@code RoleConfig.sanitize}) and mirrored stems start with {@code mirrored-}, the same in
     * every granular directory, so no rule file can ever share this name, and the orphan sweep's
     * exclusion of it can never shelter a stale rule file.
     */
    public static final String SAFETY_TIER_FILE = "+vibetags-safety.md";

    /** Cline's safety file, {@link #SAFETY_TIER_FILE} inside {@code .clinerules/} (issue #648). */
    public static final String CLINE_SAFETY_FILE = SAFETY_TIER_FILE;

    private ServiceRegistry() {}

    /**
     * The service keys whose file's presence on disk is the user's opt-in signal, as an
     * immutable copy. Exposed for tooling (the {@code vibetags-cli} {@code init} and
     * {@code doctor} commands) so the opt-in list has exactly one home; the CLI creating a
     * file from this set is the user opting in explicitly — the processor itself still never
     * creates one.
     */
    public static Set<String> optInKeys() {
        return Set.copyOf(OPT_IN_KEYS);
    }

    /**
     * Returns the canonical map of service key → output file path for a given project root.
     */
    public static Map<String, Path> buildServiceFileMap(Path root) {
        Map<String, Path> map = new LinkedHashMap<>();
        map.put("cursor",    root.resolve(".cursorrules"));
        map.put("claude",    root.resolve("CLAUDE.md"));
        map.put("aiexclude", root.resolve(".aiexclude"));
        map.put("codex",     root.resolve("AGENTS.md"));
        map.put("gemini",    root.resolve("gemini_instructions.md"));
        map.put("copilot",   root.resolve(".github/copilot-instructions.md"));
        map.put("qwen",      root.resolve("QWEN.md"));
        map.put("cursor_ignore",  root.resolve(".cursorignore"));
        map.put("claude_ignore",  root.resolve(".claudeignore"));
        map.put("copilot_ignore", root.resolve(".copilotignore"));
        map.put("qwen_ignore",    root.resolve(".qwenignore"));
        map.put("codex_config",   root.resolve(".codex/config.toml"));
        map.put("codex_rules",    root.resolve(".codex/rules/vibetags.rules"));
        // .qwen/settings.json is deliberately not mapped (#650): it is Qwen Code's own project
        // settings file, and a whole-file write erased the user's MCP servers and permissions.
        map.put("qwen_refactor",  root.resolve(".qwen/commands/refactor.md"));
        map.put("llms",           root.resolve("llms.txt"));
        map.put("llms_full",      root.resolve("llms-full.txt"));
        map.put("aider_conventions", root.resolve("CONVENTIONS.md"));
        map.put("aider_ignore",      root.resolve(".aiderignore"));
        map.put("aider_conf",        root.resolve(".aider.conf.yml"));
        map.put("cursor_granular",   root.resolve(".cursor/rules"));
        map.put("roo_granular",      root.resolve(".roo/rules"));
        map.put("trae_granular",     root.resolve(".trae/rules"));
        // New platforms
        map.put("windsurf",          root.resolve(".windsurfrules"));
        map.put("zed",               root.resolve(".rules"));
        map.put("cody",              root.resolve(".cody/config.json"));
        map.put("cody_ignore",       root.resolve(".codyignore"));
        map.put("supermaven_ignore", root.resolve(".supermavenignore"));
        map.put("windsurf_granular", root.resolve(".windsurf/rules"));
        // Inside that directory: the safety tier as a trigger: always_on rule (issue #684). Implicit,
        // like cline_safety, so it has no opt-in key of its own.
        map.put("windsurf_safety",   root.resolve(".windsurf/rules").resolve(SAFETY_TIER_FILE));
        map.put("continue_granular", root.resolve(".continue/rules"));
        map.put("tabnine_granular",  root.resolve(".tabnine/guidelines"));
        map.put("amazonq_granular",  root.resolve(".amazonq/rules"));
        map.put("ai_rules_granular", root.resolve(".ai/rules"));
        // v0.8.0 platforms
        map.put("pearai_granular",  root.resolve(".pearai/rules"));
        map.put("mentat",           root.resolve(".mentatconfig.json"));
        map.put("sweep",            root.resolve("sweep.yaml"));
        map.put("plandex",          root.resolve(".plandex.yaml"));
        map.put("double_ignore",    root.resolve(".doubleignore"));
        map.put("interpreter",      root.resolve(".interpreter/profiles/vibetags.yaml"));
        map.put("codeium_ignore",   root.resolve(".codeiumignore"));
        // Ignore files for three platforms whose rules directory VibeTags already writes
        map.put("roo_ignore",       root.resolve(".rooignore"));
        map.put("continue_ignore",  root.resolve(".continueignore"));
        map.put("augment_ignore",   root.resolve(".augmentignore"));
        // v0.9.6 platforms
        map.put("gemini_md",          root.resolve("GEMINI.md"));
        map.put("antigravity_ignore", root.resolve(".antigravityignore"));
        // v0.9.7 platforms
        map.put("cline",         root.resolve(".clinerules"));
        // Cline's directory form, at the same path as the file. A path is one or the other, so
        // isOptedIn lets exactly one of the two activate (issue #642).
        map.put("cline_granular", root.resolve(".clinerules"));
        // Inside that directory: the safety tier, always loaded (issue #648). Implicit, like
        // codex_config under codex, so it has no opt-in key of its own.
        map.put("cline_safety", root.resolve(".clinerules").resolve(CLINE_SAFETY_FILE));
        map.put("junie",         root.resolve(".junie/guidelines.md"));
        map.put("junie_agents",  root.resolve(".junie/AGENTS.md"));
        map.put("kiro_granular", root.resolve(".kiro/steering"));
        // Firebase AI
        map.put("firebase",      root.resolve(".idx/airules.md"));
        // Claude Code local override, Skill, and granular rules; Copilot granular instructions
        map.put("claude_local",     root.resolve("CLAUDE.local.md"));
        map.put("claude_skill",     root.resolve(".claude/skills/vibetags-guardrails/SKILL.md"));
        // The cross-client Agent Skills location. Same SKILL.md, read by every client that scans
        // .agents/skills/ rather than only its own vendor directory.
        map.put("agents_skill",     root.resolve(".agents/skills/vibetags-guardrails/SKILL.md"));
        map.put("claude_granular",  root.resolve(".claude/rules"));
        map.put("copilot_granular", root.resolve(".github/instructions"));
        map.put("gemini_granular", root.resolve(".gemini/rules"));
        // Grok Build scoped rules
        map.put("grok_granular",   root.resolve(".grok/rules"));
        // 2026-09 platform sweep
        map.put("antigravity_granular", root.resolve(".agents/rules"));
        map.put("aiassistant_granular", root.resolve(".aiassistant/rules"));
        map.put("augment_granular",     root.resolve(".augment/rules"));
        map.put("zencoder_granular",    root.resolve(".zencoder/rules"));
        // Devin Desktop, formerly Windsurf (#671)
        map.put("devin_granular",       root.resolve(".devin/rules"));
        // The same always-on safety file in the preferred directory (issue #684)
        map.put("devin_safety",         root.resolve(".devin/rules").resolve(SAFETY_TIER_FILE));
        map.put("devin_ignore",         root.resolve(".devinignore"));
        map.put("replit",               root.resolve("replit.md"));
        map.put("goose",                root.resolve(".goosehints"));
        // Context-packer ignore files
        map.put("repomix_ignore",    root.resolve(".repomixignore"));
        map.put("gitingest_ignore",  root.resolve(".gitingestignore"));
        map.put("gpt_ignore",        root.resolve(".gptignore"));
        map.put("ghostcoder_ignore", root.resolve(".ghostcoderignore"));
        map.put("pieces_ignore",     root.resolve(".piecesignore"));
        // AI pull-request reviewers
        map.put("coderabbit",    root.resolve(".coderabbit.yaml"));
        map.put("pr_agent",      root.resolve(".pr_agent.toml"));
        map.put("ellipsis",      root.resolve("ellipsis.yaml"));
        // Gemini Code Assist for GitHub (PR reviewer) — distinct from the Gemini CLI's GEMINI.md
        map.put("gemini_styleguide", root.resolve(".gemini/styleguide.md"));
        // Greptile (PR reviewer). When both exist in the root, Greptile reads .greptile/ and
        // ignores greptile.json entirely; docs/PLATFORMS.md says so.
        map.put("greptile",       root.resolve("greptile.json"));
        map.put("greptile_rules", root.resolve(".greptile/rules.md"));
        map.put("greptile_config", root.resolve(".greptile/config.json"));
        // Editors & modes
        map.put("void",          root.resolve(".void/rules.md"));
        map.put("roo_modes",     root.resolve(".roomodes"));
        // Machine-readable @AILocked report (no extension → hash markers → multi-module merge)
        map.put("locks_report",  root.resolve(".vibetags-locks"));
        // Lean indexed root aggregate opt-in (multi-module). No renderer: presence only flips the
        // reactor-root CLAUDE.md/.cursorrules/.windsurfrules/copilot-instructions.md merge from
        // embedding each module's guardrails to linking the module's own scoped rule files.
        map.put("root_index",    root.resolve(".vibetags-root-index"));
        // Routing target for test-code guardrails. A .md file, so HTML markers and the ordinary
        // multi-module merge; its renderer writes nothing outside a test round.
        map.put("testing",       root.resolve("TESTING.md"));
        return map;
    }

    /**
     * Resolves which primary services should have their files written. Only "signal" files
     * (e.g. CLAUDE.md, .cursorrules) are checked; their presence is the opt-in.
     *
     * <p>Special case for {@code AGENTS.md} (the {@code codex} service): it doubles as a
     * near-universal agent-instructions file, and projects that use several AI tools frequently
     * keep {@code AGENTS.md} only as a thin pointer to another tool's file (e.g. {@code CLAUDE.md}).
     * To avoid clobbering such a pointer, {@code AGENTS.md} is treated as a write target only when
     * it is the <em>sole</em> AI config file present. If any other service opted in, {@code codex}
     * is dropped here, which also disables the Codex sidecar config it would otherwise drive.
     *
     * <p><strong>Marker escape hatch.</strong> The sole-file rule exists to protect hand-authored
     * pointers, not to forbid a generated {@code AGENTS.md} outright — a Claude + Codex project
     * could not have one at all. A file that already contains a VibeTags block
     * ({@link GuardrailFileWriter#MARKER_START_MD}) was written by VibeTags in the first place, and
     * {@code GuardrailFileWriter} only ever replaces the region between the markers, so refreshing
     * it cannot destroy anything the user wrote by hand. Such a file therefore stays an active
     * write target even alongside {@code CLAUDE.md}, {@code GEMINI.md} and friends. Paste a
     * {@code VIBETAGS-START} / {@code VIBETAGS-END} comment pair into {@code AGENTS.md} to opt a
     * multi-tool project into a generated Codex file.
     */
    public static Set<String> resolveActiveServices(Messager messager, Map<String, Path> allServiceFiles) {
        boolean codexOptedIn = allServiceFiles.containsKey("codex") && Files.exists(allServiceFiles.get("codex"));
        Set<String> active = resolveActiveServices(allServiceFiles);

        // AGENTS.md is only managed when it is the only AI config file present (see Javadoc); the
        // quiet overload dropped it, so re-emit the explanatory note here for the root resolution.
        if (codexOptedIn && !active.contains("codex")) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: AGENTS.md left untouched because other AI config files are present; "
                + "it is treated as a pointer rather than a generated file. Keep only AGENTS.md "
                + "(remove the other AI config files), or paste a "
                + GuardrailFileWriter.MARKER_START_MD + " / " + GuardrailFileWriter.MARKER_END_MD + " pair into it, "
                + "to have VibeTags manage it.");
        }

        // Deprecated outputs are still written; this is where an opted-in consumer hears that they
        // will stop being written (#641). Raised here rather than in the processor because this
        // method already runs exactly once per compilation for the root, in generateFiles() and in
        // check mode alike, and generateFiles() is @AILocked.
        DeprecatedServices.warnIfOptedIn(messager, VibeTagsLogger.currentFor(rootOf(allServiceFiles)), active);

        if (active.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                "VibeTags: No AI config files found - nothing will be generated.\n" +
                "Create one or more of the following files in your project root to opt in:\n");
            // A deprecated output is left off: this list is what a new user copies from. A directory
            // service carries a trailing '/', because .clinerules is both a deprecated file and a
            // current directory, and a bare name would have a new user touch the deprecated one.
            // Paths are root-relative for the same reason: .greptile/config.json and the deprecated
            // .cody/config.json share a file name.
            Path root = rootOf(allServiceFiles);
            allServiceFiles.entrySet().stream()
                .filter(e -> OPT_IN_KEYS.contains(e.getKey()) && !"root_index".equals(e.getKey())
                    && !DeprecatedServices.keys().contains(e.getKey()))
                .forEach(e -> msg.append("  ").append(optInName(root, e.getValue()))
                    .append(writesDirectory(e.getKey()) ? "/" : "").append('\n'));
            messager.printMessage(Diagnostic.Kind.NOTE, msg.toString());
        }

        return active;
    }

    /**
     * How the opt-in note names {@code path}: relative to the root, with {@code /} separators, so
     * {@code .greptile/config.json} is not listed as a bare {@code config.json} that reads like
     * Cody's, and the two {@code SKILL.md} entries are told apart. Falls back to the file name for a
     * hand-built map with no root to relativise against.
     */
    private static String optInName(@Nullable Path root, Path path) {
        if (root != null && path.startsWith(root)) {
            return root.relativize(path).toString().replace('\\', '/');
        }
        return GuardrailFileWriter.fileName(path);
    }

    /**
     * The root a service map was built for, recovered from {@code CLAUDE.md}, which
     * {@link #buildServiceFileMap} always places directly under it. {@code null} for a hand-built
     * map without that entry, which only costs the log line, never the warning.
     */
    private static @Nullable Path rootOf(Map<String, Path> allServiceFiles) {
        Path claude = allServiceFiles.get("claude");
        return claude == null ? null : claude.getParent();
    }

    /**
     * Quiet resolution — same file-existence opt-in and AGENTS.md sole-file logic as
     * {@link #resolveActiveServices(Messager, Map)}, but emits no diagnostics. Used for per-module
     * output scans, where a module directory with no opt-in files is the common case and must not
     * spam a "nothing will be generated" note for every module in a reactor.
     */
    public static Set<String> resolveActiveServices(Map<String, Path> allServiceFiles) {
        Set<String> active = new HashSet<>();
        allServiceFiles.forEach((key, path) -> {
            if (OPT_IN_KEYS.contains(key) && isOptedIn(key, path)) {
                active.add(key);
            }
        });
        // AGENTS.md is only managed when it is the only AI config file present (see Javadoc),
        // unless it already carries a VibeTags block — a marked file is one VibeTags generated,
        // so refreshing it cannot clobber a hand-authored pointer.
        // TESTING.md does not count as company: no tool reads it as its instruction file, so it
        // is never what an AGENTS.md pointer points at, and counting it would stop a Codex-only
        // project's AGENTS.md being written the moment it opted in to test routing.
        int aiConfigFiles = active.size() - (active.contains("testing") ? 1 : 0);
        if (active.contains("codex") && aiConfigFiles > 1
                && !carriesGeneratedBlock(allServiceFiles.get("codex"))) {
            active.remove("codex");
        }
        return active;
    }

    /**
     * True when the service writes a directory of per-element rule files rather than a single file.
     *
     * <p>The one definition of that distinction, for everything that has to know which kind of entry
     * a service's path is without looking at the disk: opt-in resolution below, the CLI's
     * {@code init}, and the tests that count and fixture the outputs. The file name cannot answer it,
     * because one path is both: {@code .clinerules} is the {@code cline} file and the
     * {@code cline_granular} directory. The {@code _granular} suffix is already load-bearing
     * elsewhere ({@code PlatformRendererRegistry} routes on it and {@code GuardrailContentBuilder}
     * filters on it), so this names an existing convention rather than inventing a second one.
     */
    public static boolean writesDirectory(String key) {
        return key.endsWith("_granular");
    }

    /**
     * True when the service is an exclusion list: a {@code *_ignore} file or {@code .aiexclude}.
     * These are rewritten on every build, whether or not the round had annotations.
     *
     * <p>{@code AIGuardrailProcessor.generateFiles()} carries the same predicate inline because its
     * body is locked. A service added here has to be added there too.
     */
    public static boolean isIgnoreService(String key) {
        return key.endsWith("_ignore") || "aiexclude".equals(key);
    }

    /**
     * True when a test round's non-safety guardrails leave this service's file for
     * {@code TESTING.md}, once that file is present.
     *
     * <p>The rule picks out the instruction files an agent loads as prose: one rendered file, with
     * VibeTags markers, that is not YAML. The two sides are not symmetric, and the asymmetry is the
     * part to keep. Routing a file that should have stayed whole loses guardrails from it: an ignore
     * file would stop excluding a test file, and a JSON, TOML or YAML tool configuration has no
     * prose in which to say the rest is in {@code TESTING.md}. Failing to route a file only costs
     * the context the feature set out to save. So every doubtful case answers {@code false}.
     *
     * <p>{@code ServiceRoutingContractTest} pins the answer for every key by hand, so a new
     * service fails there until someone decides which side it is on.
     */
    public static boolean routesTestGuardrails(String key) {
        Path file = buildServiceFileMap(Path.of("")).get(key);
        // No platform means no renderer: root_index is a marker file whose extensionless name would
        // otherwise read as "hash markers, no merge shape" and qualify with nothing to route.
        if (file == null || Platform.fromServiceKey(key) == null
                || writesDirectory(key) || isIgnoreService(key)) {
            return false;
        }
        // Decisions rather than consequences of the format: the destination itself, the files that
        // hold only what never moves, the report a CI diff guard reads every lock from, and the
        // llms files, which describe the project to a reader rather than instruct an agent.
        if ("testing".equals(key) || key.endsWith("_safety") || "locks_report".equals(key)
                || key.startsWith("llms")) {
            return false;
        }
        Path name = file.getFileName();
        return name != null
            && GuardrailFileWriter.getMarkersFor(name.toString()) != null
            && PlatformRendererRegistry.mergeShapeFor(key) == null;
    }

    /**
     * True when {@code path} is the <em>kind</em> of filesystem entry service {@code key} writes,
     * i.e. when that entry is an opt-in to this service and not to another one at the same path.
     *
     * <p>This used to be a bare {@code Files.exists}, and the shape of that bug is worth keeping
     * written down. Cline reads {@code .clinerules} as a directory of rule files (its current
     * documented shape) and as a single file (the shape VibeTags wrote first, which its loader still
     * reads). A user following the current docs created the directory, {@code exists()} was true for
     * it, the single-file service activated, and the writer was handed a directory to write a regular
     * file over.
     *
     * <p>A path cannot be both, so the entry's type is an unambiguous signal for which of the two the
     * user meant, and the two services at that path can never both be active.
     */
    public static boolean isOptedIn(String key, Path path) {
        return writesDirectory(key) ? Files.isDirectory(path) : Files.isRegularFile(path);
    }

    /**
     * True when {@code path} already contains a VibeTags-generated markdown block. Unreadable
     * files fall back to {@code false}, i.e. to the conservative sole-file rule.
     */
    private static boolean carriesGeneratedBlock(@Nullable Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.readString(path).contains(GuardrailFileWriter.MARKER_START_MD);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
