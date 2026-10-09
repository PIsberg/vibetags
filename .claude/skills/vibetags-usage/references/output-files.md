# VibeTags: Supported output files

Part of the `vibetags-usage` skill; [SKILL.md](../SKILL.md) holds setup and the element cheat sheet.

## Supported Output Files

| File(s) | Platform |
|---|---|
| `CLAUDE.md` | Claude / Claude Code |
| `CLAUDE.local.md` | Claude Code (local override) |
| `.claude/rules/*.md` | Claude Code (granular per-class rules) |
| `.claude/skills/vibetags-guardrails/SKILL.md` | Claude Code (Skill) |
| `.cursorrules`, `.cursorignore` | Cursor (traditional) |
| `.cursor/rules/*.mdc` | Cursor (granular per-class rules) |
| `.windsurfrules` | Devin Desktop, formerly Windsurf (traditional, legacy) |
| `.windsurf/rules/*.md` | Devin Desktop, formerly Windsurf (granular per-class rules, fallback directory) |
| `.devin/rules/*.md` | Devin Desktop (granular per-class rules, preferred directory, `trigger: glob`) |
| `.devin/rules/+vibetags-safety.md`, `.windsurf/rules/+vibetags-safety.md` | Devin Desktop (written with each directory: the always-on safety tier, `trigger: always_on`) |
| `.devinignore` | Devin Desktop (exclusion list) |
| `.trae/rules/*.md` | TraeCode, formerly Trae IDE (granular per-class rules; can also import `AGENTS.md` and `CLAUDE.md` behind a settings toggle) |
| `CONVENTIONS.md`, `.aider.conf.yml`, `.aiderignore` | Aider |
| `.roo/rules/*.md`, `.rooignore` | Zoo Code (fork of the retired Roo Code; reads the same paths) |
| `CONVENTIONS.md`, `.aiderignore` | Aider |
| `QWEN.md`, `.qwen/commands/refactor.md`, `.qwenignore` | Qwen |
| `GEMINI.md`, `.aiexclude` | Gemini |
| `.gemini/styleguide.md` | Gemini Code Assist (GitHub PR reviewer) |
| `.greptile/rules.md` | Greptile (AI PR reviewer) |
| `.greptile/config.json` | Greptile (`@AIIgnore` paths; VibeTags owns only a span inside `ignorePatterns`) |
| `greptile.json` | Greptile (legacy form; VibeTags owns only a span inside `instructions` and `ignorePatterns`) |
| `AGENTS.md`, `.codex/rules/` | Codex CLI |
| `.github/copilot-instructions.md` | GitHub Copilot |
| `.github/instructions/*.instructions.md` | GitHub Copilot (granular per-class rules) |
| `.rules` | Zed Editor |
| `.continue/rules/*.md` | Continue (granular per-class rules) |
| `.tabnine/guidelines/*.md` | Tabnine (granular per-class rules) |
| `llms.txt` | Windsurf Cascade / all LLM agents |
| `llms-full.txt` | Large-context LLMs (Claude, Gemini) |
| `.codeiumignore` | Codeium (Devin Desktop reads it under this legacy name) |
| `.clinerules/*.md` | Cline AI assistant (granular per-class rules, `paths:` front matter) |
| `.clinerules/+vibetags-safety.md` | Cline AI assistant (written with the directory: the always-loaded safety tier, no front matter) |
| `.junie/AGENTS.md` | JetBrains Junie (checked first; not the root `AGENTS.md`) |
| `.junie/guidelines.md` | JetBrains Junie (legacy, still supported) |
| `.kiro/steering/*.md` | Amazon Kiro (granular per-class rules) |
| `.grok/rules/*.md` | Grok Build (granular per-class rules) |
| `.agents/rules/*.md` | Antigravity (granular per-class rules) |
| `.aiassistant/rules/*.md` | JetBrains AI Assistant (granular per-class rules) |
| `.augment/rules/*.md` | Augment Code (granular per-class rules) |
| `.goosehints` | goose (Block) |
| `DESIGN.md` | AI design agents (Cursor, Claude, Copilot, etc.) |
| `TESTING.md` | No tool reads it by name. Routing target: a round that compiles test code writes its non-safety guardrails here instead of into the always-loaded files, which keep the six safety annotations and gain a pointer |
| `.coderabbit.yaml` | CodeRabbit (AI PR reviewer) |
| `.pr_agent.toml` | Qodo/Codium PR-Agent (AI PR reviewer) |
| `.roomodes` | Zoo Code (fork of the retired Roo Code; reads the same paths), "VibeTags Architect" custom mode |
| `.repomixignore` | Repomix (context packer) |
| `.gitingestignore` | Gitingest (context packer) |
| `.gptignore` | GPT context packer |
