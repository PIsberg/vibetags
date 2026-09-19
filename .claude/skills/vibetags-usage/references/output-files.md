# VibeTags: Supported output files

Part of the `vibetags-usage` skill; [SKILL.md](../SKILL.md) holds setup and the element cheat sheet.

## Supported Output Files

| File(s) | Platform |
|---|---|
| `CLAUDE.md`, `.claudeignore` (deprecated) | Claude / Claude Code |
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
| `.trae/rules/*.md` | Trae IDE (granular per-class rules) |
| `CONVENTIONS.md`, `.aider.conf.yml`, `.aiderignore` | Aider |
| `.roo/rules/*.md`, `.rooignore` | Zoo Code (fork of the retired Roo Code; reads the same paths) |
| `CONVENTIONS.md`, `.aiderignore` | Aider |
| `QWEN.md`, `.qwen/commands/refactor.md`, `.qwenignore` | Qwen |
| `GEMINI.md`, `.aiexclude`, `gemini_instructions.md` (deprecated) | Gemini |
| `.gemini/styleguide.md` | Gemini Code Assist (GitHub PR reviewer) |
| `.greptile/rules.md` | Greptile (AI PR reviewer) |
| `.greptile/config.json` | Greptile (`@AIIgnore` paths; VibeTags owns only a span inside `ignorePatterns`) |
| `greptile.json` | Greptile (legacy form; VibeTags owns only a span inside `instructions` and `ignorePatterns`) |
| `.antigravityignore` | Antigravity AI (deprecated) |
| `AGENTS.md`, `.codex/config.toml`, `.codex/rules/` | Codex CLI |
| `.github/copilot-instructions.md`, `.copilotignore` (deprecated) | GitHub Copilot |
| `.github/instructions/*.instructions.md` | GitHub Copilot (granular per-class rules) |
| `.rules` | Zed Editor |
| `.cody/config.json`, `.codyignore` (deprecated) | Sourcegraph Cody |
| `.supermavenignore` (deprecated) | Supermaven |
| `.continue/rules/*.md` | Continue (granular per-class rules) |
| `.tabnine/guidelines/*.md` | Tabnine (granular per-class rules) |
| `.amazonq/rules/*.md` | Amazon Q (granular per-class rules; deprecated) |
| `.ai/rules/*.md` | Universal AI standard (granular; deprecated) |
| `llms.txt` | Windsurf Cascade / all LLM agents |
| `llms-full.txt` | Large-context LLMs (Claude, Gemini) |
| `.pearai/rules/*.md` | PearAI (granular per-class rules; deprecated) |
| `.mentatconfig.json` | Mentat (deprecated) |
| `sweep.yaml` | Sweep (GitHub App; deprecated) |
| `.plandex.yaml` | Plandex (deprecated) |
| `.doubleignore` | Double.bot (deprecated) |
| `.interpreter/profiles/vibetags.yaml` | Open Interpreter (deprecated) |
| `.codeiumignore` | Codeium (Devin Desktop reads it under this legacy name) |
| `.clinerules` (deprecated) | Cline AI assistant (single file) |
| `.clinerules/*.md` | Cline AI assistant (granular per-class rules, `paths:` front matter; same path as the file, so a project has one or the other) |
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
| `.void/rules.md` | Void Editor (deprecated) |
| `.coderabbit.yaml` | CodeRabbit (AI PR reviewer) |
| `.pr_agent.toml` | Qodo/Codium PR-Agent (AI PR reviewer) |
| `ellipsis.yaml` | Ellipsis (AI PR reviewer; deprecated) |
| `.roomodes` | Zoo Code (fork of the retired Roo Code; reads the same paths), "VibeTags Architect" custom mode |
| `.repomixignore` | Repomix (context packer) |
| `.gitingestignore` | Gitingest (context packer) |
| `.gptignore` | GPT context packer |
| `.ghostcoderignore` | Ghostcoder (deprecated) |
| `.piecesignore` | Pieces for Developers (deprecated) |
