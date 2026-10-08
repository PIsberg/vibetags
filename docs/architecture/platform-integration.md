# Architecture: AI Platform Integration

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## AI Platform Integration

### Qwen

**Files:** `QWEN.md` + `.qwen/commands/refactor.md` + `.qwenignore`

**Behavior:** Qwen reads `QWEN.md` as comprehensive project context, including locked files, contextual rules, security audit requirements, and ignored elements. `.qwen/settings.json` is Qwen Code's own settings file and is deliberately not written (#650). `.qwen/commands/refactor.md` is its own opt-in, regenerated only when it exists (#655).

Sample `QWEN.md` output and the `.qwen/commands/refactor.md` / `.qwenignore` roles are documented in one place: [USAGE.md § Qwen Configuration](../../USAGE.md#-qwen-configuration).

### Cursor

**Files:** `.cursorrules` + `.cursorignore`

**Behavior:** Cursor reads `.cursorrules` for core instructions and respects the `.cursorignore` glob patterns for excluding entire files from its context window.

### Claude

**Files:** `CLAUDE.md` (`.claudeignore` was removed in 1.4, #667, #720)

**Behavior:** Claude treats `CLAUDE.md` as foundational context. XML tags appeal to Claude's parsing strengths. Enforces `<rule>` elements strictly.

### Gemini

**Files:** `.aiexclude` + `GEMINI.md`

**Behavior:** `.aiexclude` is a binary blocklist (hard guardrail). `GEMINI.md` provides detailed persona and audit guidance. (`gemini_instructions.md` did the same until 1.4 removed it, #645.)

### Codex CLI

**Files:** `AGENTS.md` + `.codex/config.toml` + `.codex/rules/vibetags.rules`

**Behavior:** Codex CLI automatically reads `AGENTS.md` from the project root. The `.codex/config.toml` defines tool behavior, and `vibetags.rules` defines security-conscious command permissions using Starlark.

### GitHub Copilot

**Files:** `.github/copilot-instructions.md` (`.copilotignore` was removed in 1.4, #668, #720)

**Behavior:** Copilot uses the instructions file to guide its completions. Copilot's documented exclusion is the Content exclusion setting, which no file controls.

### Windsurf Cascade & LLM Agents (llms.txt Standard)

**Files:** `llms.txt` + `llms-full.txt`

**Standard:** [llms.txt](https://llmstxt.org/) — a Markdown-based format analogous to `robots.txt` but for content rather than crawling rules. Instead of parsing HTML, LLM agents read a clean Markdown file that tells them what the project contains and where to look. `llms.txt` is the **map** (concise directory); `llms-full.txt` is the **book** (fully expanded reference).

The format hierarchy, a sample `llms.txt` output, opt-in commands, and the `vibetags.project` naming option are documented in one place: [USAGE.md § llms.txt Standard](../../USAGE.md#-llmstxt-standard-windsurf-cascade--llm-agents).
