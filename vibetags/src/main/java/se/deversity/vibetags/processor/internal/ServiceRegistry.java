package se.deversity.vibetags.processor.internal;

import se.deversity.vibetags.annotations.AIContext;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
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
        // v0.9.6 platforms
        "gemini_md", "antigravity_ignore",
        // v0.9.7 platforms
        "cline", "junie", "kiro_granular",
        // Firebase AI
        "firebase",
        // Claude Code local override, Skill, and granular rules; Copilot granular instructions
        "claude_local", "claude_skill", "claude_granular", "copilot_granular",
        // Context-packer ignore files
        "repomix_ignore", "gitingest_ignore", "gpt_ignore", "ghostcoder_ignore", "pieces_ignore",
        // AI pull-request reviewers
        "coderabbit", "pr_agent", "ellipsis",
        // Editors & modes
        "void", "roo_modes",
        // Machine-readable @AILocked report for CI diff guards
        "locks_report"
    );

    private ServiceRegistry() {}

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
        map.put("qwen_settings",  root.resolve(".qwen/settings.json"));
        map.put("qwen_refactor",  root.resolve(".qwen/commands/refactor.md"));
        map.put("llms",           root.resolve("llms.txt"));
        map.put("llms_full",      root.resolve("llms-full.txt"));
        map.put("aider_conventions", root.resolve("CONVENTIONS.md"));
        map.put("aider_ignore",      root.resolve(".aiderignore"));
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
        // v0.9.6 platforms
        map.put("gemini_md",          root.resolve("GEMINI.md"));
        map.put("antigravity_ignore", root.resolve(".antigravityignore"));
        // v0.9.7 platforms
        map.put("cline",         root.resolve(".clinerules"));
        map.put("junie",         root.resolve(".junie/guidelines.md"));
        map.put("kiro_granular", root.resolve(".kiro/steering"));
        // Firebase AI
        map.put("firebase",      root.resolve(".idx/airules.md"));
        // Claude Code local override, Skill, and granular rules; Copilot granular instructions
        map.put("claude_local",     root.resolve("CLAUDE.local.md"));
        map.put("claude_skill",     root.resolve(".claude/skills/vibetags-guardrails/SKILL.md"));
        map.put("claude_granular",  root.resolve(".claude/rules"));
        map.put("copilot_granular", root.resolve(".github/instructions"));
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
        // Editors & modes
        map.put("void",          root.resolve(".void/rules.md"));
        map.put("roo_modes",     root.resolve(".roomodes"));
        // Machine-readable @AILocked report (no extension → hash markers → multi-module merge)
        map.put("locks_report",  root.resolve(".vibetags-locks"));
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
     */
    public static Set<String> resolveActiveServices(Messager messager, Map<String, Path> allServiceFiles) {
        Set<String> active = new HashSet<>();

        allServiceFiles.forEach((key, path) -> {
            if (!OPT_IN_KEYS.contains(key)) return;
            if (Files.exists(path)) {
                active.add(key);
            }
        });

        // AGENTS.md is only managed when it is the only AI config file present (see Javadoc).
        if (active.contains("codex") && active.size() > 1) {
            active.remove("codex");
            messager.printMessage(Diagnostic.Kind.NOTE,
                "VibeTags: AGENTS.md left untouched because other AI config files are present; "
                + "it is treated as a pointer rather than a generated file. Keep only AGENTS.md "
                + "(remove the other AI config files) to have VibeTags manage it.");
        }

        if (active.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                "VibeTags: No AI config files found - nothing will be generated.\n" +
                "Create one or more of the following files in your project root to opt in:\n");
            allServiceFiles.entrySet().stream()
                .filter(e -> OPT_IN_KEYS.contains(e.getKey()))
                .forEach(e -> msg.append("  ").append(e.getValue().getFileName()).append("\n"));
            messager.printMessage(Diagnostic.Kind.NOTE, msg.toString());
        }

        return active;
    }
}
