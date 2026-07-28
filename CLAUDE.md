# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VibeTags is a **compile-time Java annotation processor** (`se.deversity.vibetags.processor.AIGuardrailProcessor`) that generates AI platform-specific guardrail configuration files from Java annotations. Zero runtime overhead — all annotations use `RetentionPolicy.SOURCE`.

Independent Maven (and where noted, Gradle) subprojects:

- `vibetags-annotations/` — the 39 `@interface` classes, zero deps. On the consumer's compile classpath. **Build first.**
- `vibetags/` — the processor itself (`AIGuardrailProcessor` + `VibeTagsLogger`). On the consumer's annotation-processor path only.
- `vibetags-bom/` — pom-only BOM managing both versions. Maven only; Gradle reads it via `mavenLocal()` / `platform(...)`.
- `example/`, `example-multimodule/`, `example-multimodule-indexed/` — demo consumers (the last two are reactors, asserted in CI).
- `load-tests/` — standalone benchmark harness; pins `<processor.version>` directly (intentional — cross-version comparison is the wrong workload for a BOM).
- `action/locked-files/` — GitHub Action consuming `.vibetags-locks`.

Build order matters: `vibetags-annotations` → `vibetags` → `vibetags-bom` → `example` (or `load-tests`). CI installs them in this order; do the same locally.

## Build Commands

Run from the relevant subproject's own directory — the processor uses the JVM working directory for output unless `vibetags.root` is set.

```bash
# Build and install the library, in order
cd vibetags-annotations && mvn install          # or: gradle clean build publishToMavenLocal
cd ../vibetags         && mvn clean install     # or: gradle clean build publishToMavenLocal
cd ../vibetags-bom     && mvn install           # Maven only

# Tests (from vibetags/)
mvn test                                   # unit tests only
mvn test -Drun.integration.tests=true      # include integration tests
mvn test -Dtest=AnnotationProcessorEndToEndTest
mvn test -Dtest=AIGuardrailProcessorUnitTest#methodName

# Example (triggers annotation processing; library must be installed first)
cd example && mvn clean compile            # or: gradle clean build

# Dogfood: regenerate this repo's own guardrail files
cd vibetags && mvn compile -Pself-annotate
```

## Architecture — load-bearing behaviors

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

39 `@AI*` annotations, all `RetentionPolicy.SOURCE`. Full table, semantics, and validation-warning list: `docs/ANNOTATIONS.md`.

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

## Reference docs (read on demand)

- `docs/MULTI-MODULE.md` — reactors: sidecar merge, per-module output, `.vibetags-root-index`, `.vibetags-roles`, `.vibetags-mirror`, granular file layout.
- `docs/PROCESSOR.md` — processor options, write cache + fingerprint short-circuit, check mode, `.vibetags-locks`, SPI/Gradle incremental.
- `docs/ANNOTATIONS.md` — adding or changing an annotation: full table, semantics, validation warnings.
- `docs/PLATFORMS.md` — adding a platform, or a question about a specific output file.
- `docs/TESTS.md` — which test class covers what.
- `docs/ARCHITECTURE.md` — deep dive: system diagram, data flow, design decisions, limitations.
- `USAGE.md` — consumer-facing usage (how to add VibeTags to a project).

## Pre-commit Hooks

The repo uses `pre-commit` with Checkstyle, gitleaks (secret scanning), end-of-file fixer, and trailing-whitespace fixer. Run `pre-commit run --all-files` before committing.

<!-- VIBETAGS-START -->
<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->
<project_guardrails>
  <locked_files>
    <file path="se.deversity.vibetags.processor.AIGuardrailProcessor.generateFiles()">
      <reason>Step order is load-bearing: fingerprint check → sidecar write → sidecar read → merge → file write → cache flush; reordering steps silently skips regeneration or corrupts multi-module output</reason>
    </file>
  </locked_files>
  <core_elements>
    <element path="se.deversity.vibetags.processor.AIGuardrailProcessor">
      <sensitivity>critical</sensitivity>
      <note>JSR 269 entry point; orchestrates annotation discovery, fingerprint short-circuit, sidecar aggregation, and all file writes</note>
    </element>
    <element path="se.deversity.vibetags.processor.internal.GuardrailFileWriter">
      <sensitivity>high</sensitivity>
      <note>Atomic marker-aware file writer; invariant: hand-authored content outside VIBETAGS-START/END markers must never be overwritten or lost</note>
    </element>
    <element path="se.deversity.vibetags.processor.internal.ModuleSidecar">
      <sensitivity>high</sensitivity>
      <note>Per-module sidecar for multi-module Maven/Gradle builds; the .vibetags-mod-* file format is shared across independently compiled modules — format changes break backward compatibility</note>
    </element>
    <element path="se.deversity.vibetags.processor.internal.WriteCache">
      <sensitivity>high</sensitivity>
      <note>Per-file content cache backed by .vibetags-cache; false positives (wrongly treating stale output as unchanged) would silently corrupt generated files</note>
    </element>
  </core_elements>

<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>
  <scoped_rules>
    <note>Detailed per-element guardrails for the elements below live in scoped rule files that load automatically when the matching source file is opened. Consult the referenced file before modifying an element.</note>
    <element path="se.deversity.vibetags.processor.AIGuardrailProcessor" rules=".claude/rules/se-deversity-vibetags-processor-AIGuardrailProcessor.md"/>
    <element path="se.deversity.vibetags.processor.internal.AnnotationCollector" rules=".claude/rules/se-deversity-vibetags-processor-internal-AnnotationCollector.md"/>
    <element path="se.deversity.vibetags.processor.internal.GranularRulesWriter" rules=".claude/rules/se-deversity-vibetags-processor-internal-GranularRulesWriter.md"/>
    <element path="se.deversity.vibetags.processor.internal.ServiceRegistry" rules=".claude/rules/se-deversity-vibetags-processor-internal-ServiceRegistry.md"/>
    <element path="se.deversity.vibetags.processor.internal.GuardrailFileWriter" rules=".claude/rules/se-deversity-vibetags-processor-internal-GuardrailFileWriter.md"/>
    <element path="se.deversity.vibetags.processor.internal.ModuleSidecar" rules=".claude/rules/se-deversity-vibetags-processor-internal-ModuleSidecar.md"/>
    <element path="se.deversity.vibetags.processor.internal.WriteCache" rules=".claude/rules/se-deversity-vibetags-processor-internal-WriteCache.md"/>
    <element path="se.deversity.vibetags.processor.internal.BuildFingerprint" rules=".claude/rules/se-deversity-vibetags-processor-internal-BuildFingerprint.md"/>
  </scoped_rules>

<rule>When you work on any element listed in <scoped_rules>, open its referenced rule file and apply the guardrails there. The rule files are the authoritative source for those elements.</rule>
</project_guardrails>

<rule>Never propose edits to files listed in <locked_files>.</rule>
<!-- VIBETAGS-END -->
