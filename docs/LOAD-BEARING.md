# Load-bearing behaviors

> Moved out of `CLAUDE.md` so the always-loaded context stays small. The invariants that must
> not be broken are summarised there; this file is the reasoning behind each one.

Full technical deep dive (system diagram, data flow, design decisions, limitations): `docs/ARCHITECTURE.md`.

### Core processing flow

`AIGuardrailProcessor.process()` runs during `javac` compilation of the **consumer** project (not the library itself — the library disables annotation processing with `-proc:none`, apart from the `self-annotate` profile):

1. Collects all annotation elements across **all compilation rounds** into `LinkedHashSet`s (one per annotation type)
2. Returns `false` from `process()` so other processors can still see the annotations
3. On `processingOver() == true`, calls `resolveActiveServices()` — only files that already exist on disk are regenerated (file presence = opt-in)
4. Writes to `Paths.get("").toAbsolutePath()` (project root of the consumer), or to `vibetags.root` if set

### File-existence opt-in

The processor never creates new files. To activate a platform, create the file first, then compile:

```bash
touch CLAUDE.md .cursorrules   # in the consumer project root
mvn compile
```

To deactivate, delete the file — it will never come back.

**AGENTS.md sole-file fallback.** `AGENTS.md` (the `codex` service) doubles as a near-universal agent-instructions file and is frequently kept only as a thin pointer to another tool's file, so `resolveActiveServices()` treats it as a write target **only when it is the sole AI config file present** — otherwise `codex` is dropped, which also disables the Codex sidecar config (`.codex/config.toml`, `.codex/rules/`). Escape hatch: a file that already contains a `VIBETAGS-START`/`VIBETAGS-END` pair was written by VibeTags in the first place, so it stays an active write target alongside other config files.

### Marker-based updates

Generated content is written between markers so a file can hold hand-authored content alongside generated guardrails:

- **HTML comments** (CLAUDE.md, llms.txt, llms-full.txt): `<!-- VIBETAGS-START -->` / `<!-- VIBETAGS-END -->`
- **Hash comments** (.cursorrules, .aiexclude, ignore files): `# VIBETAGS-START` / `# VIBETAGS-END`
- **No markers** (JSON/TOML config files): complete overwrite

YAML front-matter in `.mdc`/`.md` files is preserved — markers go after the front-matter block. Files written by an older VibeTags (without markers) are migrated on the next compile.

### Output files

50+ AI platforms (Cursor, Claude, Gemini, Codex, Copilot, Windsurf, granular per-class rules, AI PR reviewers, context packers, …). Full file/platform/format table: `docs/PLATFORMS.md`.

### Aggregate ↔ granular de-duplication (scoped-rules index)

Four platforms have both an always-loaded aggregate file and a glob-scoped granular directory: `CLAUDE.md`↔`.claude/rules/`, `.cursorrules`↔`.cursor/rules/`, `.windsurfrules`↔`.windsurf/rules/`, `.github/copilot-instructions.md`↔`.github/instructions/`. When **both** are opted in, the aggregate renderer collapses to a **scoped-rules index**: only the always-loaded safety buckets stay inline (`@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure`), plus one pointer line per element to its scoped file (`GranularIndexSection`); every other bucket moves to the granular files.

Gating is per platform via `GranularIndexSection.governingGranularKey` — `CLAUDE_LOCAL` maps to `claude_granular` (so `CLAUDE.local.md` mirrors `CLAUDE.md`), while renderers that reuse the Cursor/Claude format but read no scoped directory (Cline, Firebase, Junie, Void, the Claude skill) map to `null` and always render in full. Single-opt-in output is unchanged. The owner set is computed once in `GuardrailContentBuilder.build()` and passed on `RenderingContext.granularOwners()`.

**This repo dogfoods it**: the block at the bottom of this file is a scoped-rules index; the per-element detail lives in `.claude/rules/`, which your tooling loads on demand by glob.

### Annotations

44 `@AI*` annotations, all `RetentionPolicy.SOURCE`. Full table, semantics, and validation-warning list: `docs/ANNOTATIONS.md`.

### Internal class responsibilities

Beyond what the generated section below describes:

- `AnnotationValidator` — all compile-time consistency checks (contradictory combinations, no-op annotations, invalid values); add new warnings here
- `ElementNaming` — fully-qualified element paths (`com.example.Foo.bar`) for generated output; handles TYPE, METHOD, FIELD, PACKAGE
- `OrphanWarner` — warns when an annotation is present but its platform opt-in file is absent (e.g. `@AIIgnore` with no `.cursorignore`)

### Content rendering subsystem (`internal/content/`)

`GuardrailContentBuilder` delegates all content generation here:

- `Platform` (enum) + `PlatformRendererRegistry` — one entry per output file; the registry maps each platform to its `PlatformRenderer` in `content/platforms/` (~30 renderers)
- `AnnotationFormatter` + `FormatterRegistry` — one `AI*Formatter` per annotation in `content/annotations/`; renderers pull per-annotation text from here rather than formatting inline
- `SectionCatalog` / `AnnotationSections` — shared driver that walks annotation buckets into titled sections
- `GranularBody` / `GranularSections` — structured stanzas for granular rule files, so a file hoists the constant rule sentence a section shares instead of repeating it per element

Adding a platform touches `Platform` + registry + a renderer; adding an annotation touches a formatter + registry + any bespoke renderers. Step-by-step checklists: the `add-platform` and `add-annotation` skills in `.claude/skills/`.
