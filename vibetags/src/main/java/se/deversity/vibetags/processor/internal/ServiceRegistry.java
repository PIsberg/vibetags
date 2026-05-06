package se.deversity.vibetags.processor.internal;

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
        "double_ignore", "interpreter", "codeium_ignore"
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
        return map;
    }

    /**
     * Resolves which primary services should have their files written. Only "signal" files
     * (e.g. CLAUDE.md, .cursorrules) are checked; their presence is the opt-in.
     */
    public static Set<String> resolveActiveServices(Messager messager, Map<String, Path> allServiceFiles) {
        Set<String> active = new HashSet<>();

        allServiceFiles.forEach((key, path) -> {
            if (!OPT_IN_KEYS.contains(key)) return;
            if (Files.exists(path)) {
                active.add(key);
            }
        });

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
