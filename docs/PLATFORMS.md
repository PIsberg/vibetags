# Platform Output Files

Every file VibeTags can generate, which AI platform it targets, and its format — consult this when adding a new platform or checking what a given output file is for.

### Output files

| File | Platform | Format |
|---|---|---|
| `.cursorrules` | Cursor IDE | Markdown |
| `.cursor/rules/*.mdc` | Cursor IDE (granular) | YAML front-matter + Markdown |
| `CLAUDE.md` | Claude | XML + Markdown |
| `CLAUDE.local.md` | Claude Code (local override) | XML + Markdown |
| `.claude/rules/*.md` | Claude Code (granular) | YAML front-matter + Markdown |
| `.claude/skills/vibetags-guardrails/SKILL.md` | Claude Code (Skill) | YAML front-matter + Markdown |
| `.aiexclude` | Gemini | Glob patterns |
| `AGENTS.md` | Codex CLI | Markdown |
| `.codex/` | Codex CLI | Config + Starlark |
| `gemini_instructions.md` | Gemini | Markdown |
| `.github/copilot-instructions.md` | GitHub Copilot | Markdown |
| `.github/instructions/*.instructions.md` | GitHub Copilot (granular) | YAML front-matter + Markdown |
| `CONVENTIONS.md` | Aider | Markdown |
| `.aiderignore` | Aider | Glob patterns |
| `QWEN.md` | Qwen | Markdown |
| `.qwenignore` | Qwen | Glob patterns |
| `.qwen/settings.json` | Qwen | JSON config |
| `.qwen/commands/refactor.md` | Qwen | Markdown command template |
| `.trae/rules/*.md` | Trae IDE (granular) | YAML front-matter + Markdown |
| `.roo/rules/*.md` | Roo Code (granular) | Markdown |
| `llms.txt` | Windsurf Cascade, all LLM agents | Markdown (concise map/directory) |
| `llms-full.txt` | Windsurf Cascade, large-context LLMs | Markdown (full reference book) |
| `.windsurfrules` | Windsurf IDE | Markdown |
| `.windsurf/rules/*.md` | Windsurf IDE (granular) | YAML front-matter + Markdown |
| `.rules` | Zed Editor | Markdown |
| `.cody/config.json` | Sourcegraph Cody | JSON (custom commands) |
| `.codyignore` | Sourcegraph Cody | Glob patterns |
| `.supermavenignore` | Supermaven | Glob patterns |
| `.continue/rules/*.md` | Continue (granular) | YAML front-matter + Markdown |
| `.tabnine/guidelines/*.md` | Tabnine (granular) | Markdown |
| `.amazonq/rules/*.md` | Amazon Q (granular) | Markdown |
| `.ai/rules/*.md` | Universal AI standard (granular) | Markdown |
| `.pearai/rules/*.md` | PearAI (granular) | YAML front-matter + Markdown |
| `.kiro/steering/*.md` | Amazon Kiro (granular) | Markdown |
| `.mentatconfig.json` | Mentat | JSON config |
| `sweep.yaml` | Sweep (GitHub App) | YAML rules list |
| `.plandex.yaml` | Plandex | YAML guardrails |
| `.doubleignore` | Double.bot | Glob patterns |
| `.interpreter/profiles/vibetags.yaml` | Open Interpreter | YAML profile |
| `.codeiumignore` | Codeium | Glob patterns |
| `GEMINI.md` | Google Gemini (official markdown) | Markdown |
| `.antigravityignore` | Antigravity AI | Glob patterns |
| `.clinerules` | Cline AI assistant | Markdown |
| `.junie/guidelines.md` | JetBrains Junie | Markdown |
| `.idx/airules.md` | Firebase AI | Markdown |
| `DESIGN.md` | AI design agents (Cursor, Claude, Copilot, etc.) | Markdown |
| `.void/rules.md` | Void Editor | Markdown |
| `.coderabbit.yaml` | CodeRabbit (AI PR reviewer) | YAML (`reviews.path_instructions`) |
| `.pr_agent.toml` | Qodo/Codium PR-Agent (AI PR reviewer) | TOML (`extra_instructions`) |
| `ellipsis.yaml` | Ellipsis (AI PR reviewer) | YAML (`pr_review.rules`) |
| `.roomodes` | Roo Code (custom "VibeTags Architect" mode) | YAML |
| `.repomixignore` | Repomix (context packer) | Glob patterns |
| `.gitingestignore` | Gitingest (context packer) | Glob patterns |
| `.gptignore` | GPT context packer | Glob patterns |
| `.ghostcoderignore` | Ghostcoder | Glob patterns |
| `.piecesignore` | Pieces for Developers | Glob patterns |
| `.vibetags-locks` | CI tooling (locked-files GitHub Action) | JSON Lines between hash markers |

#### Granular rules

Cursor, Windsurf, Continue, Tabnine, Amazon Q, Trae, Roo Code, PearAI, Amazon Kiro, Claude Code, GitHub Copilot, and the universal `.ai/rules/` standard all support per-class rule files. When a class or method is annotated, the processor writes one rule file per annotated class (filename derived from the fully-qualified class name). Orphaned granular files — for classes that have had annotations removed — are cleaned up **after** new files are written to prevent delete-then-recreate cycles.

Claude Code's granular rules (`.claude/rules/*.md`) scope with a `paths:` front-matter glob list rather than Cursor's `globs:`/`alwaysApply:` pair. GitHub Copilot's granular files (`.github/instructions/*.instructions.md`) use a single `applyTo:` glob string and, unlike every other granular platform, a two-part `.instructions.md` extension.

**Dual opt-in de-duplicates.** Four platforms have both an aggregate file and a granular directory: `CLAUDE.md` ↔ `.claude/rules/`, `.cursorrules` ↔ `.cursor/rules/`, `.windsurfrules` ↔ `.windsurf/rules/`, `.github/copilot-instructions.md` ↔ `.github/instructions/`. If you opt into **both** for one platform, the aggregate no longer repeats every element's guardrails: it keeps the always-loaded safety guardrails inline (`@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure`) and adds a **scoped-rules index** — one pointer line per element to its scoped rule file — while the full per-element detail lives in the scoped files. Opting into only the aggregate keeps the complete inline output as before. (`CLAUDE.local.md` follows `CLAUDE.md`; the other eight granular platforms have no aggregate counterpart, so nothing is de-duplicated for them.)

**Per-module (nested) output.** In a multi-module reactor build, opt into a file (or granular directory) *inside a module's own directory* — e.g. `touch module-a/CLAUDE.md` — and VibeTags writes that module's own guardrails there, scoped to that module's annotations. This is the context-optimal layout for tools that auto-load nested config (Claude Code nested `CLAUDE.md`, Cursor nested rules). It is additive: the reactor-**root** file still merges every module (unchanged), and a module that doesn't opt in gets no file. The scoped-rules index composes here too — a module that opts into both its aggregate and its granular dir gets an indexed module aggregate.

#### llms.txt vs llms-full.txt

VibeTags follows the [llms.txt standard](https://llmstxt.org/) for LLM agent discovery:

- **`llms.txt`** — The Map: A concise directory listing all guardrail rules with links to the annotated class. Intended for LLM agents (e.g., Windsurf Cascade) to quickly understand the project's AI interaction rules without bloating the context window.
- **`llms-full.txt`** — The Book: A single expanded file with all rule details. Intended for large-context LLMs (Claude 4.6, Gemini 1.5 Pro) that can ingest the entire ruleset at once.

Both files follow the llms.txt format hierarchy: `# Title`, `> Summary blockquote`, informational text, and `## H2` resource sections.
