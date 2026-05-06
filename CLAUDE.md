# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VibeTags is a **compile-time Java annotation processor** (`se.deversity.vibetags.processor.AIGuardrailProcessor`) that generates AI platform-specific guardrail configuration files from Java annotations. Zero runtime overhead — all annotations use `RetentionPolicy.SOURCE`.

The repo has these independent Maven (and where noted, Gradle) subprojects:
- `vibetags-annotations/` — the 8 `@interface` classes, zero deps. Goes on the consumer's compile classpath. **Build first** — `vibetags/` depends on it.
- `vibetags/` — the annotation processor itself (`AIGuardrailProcessor` + `VibeTagsLogger`). Pulls in slf4j/logback. Goes on the consumer's annotation-processor path only.
- `vibetags-bom/` — pom-only BOM that manages `vibetags-annotations` + `vibetags-processor` versions. Maven only; Gradle consumers read it via `mavenLocal()` / `platform(...)`.
- `example/` — a demo e-commerce app that consumes the library through the BOM (annotations on compile, processor on AP path).
- `load-tests/` — standalone benchmark harness; pins `<processor.version>` directly (intentional — cross-version comparison is the wrong workload for a BOM).

Build order matters: `vibetags-annotations` → `vibetags` → `vibetags-bom` → `example` (or `load-tests`). CI installs them in this order; do the same locally.

## Build Commands

All commands must be run from the correct subdirectory. The processor uses `Paths.get("")` (JVM working directory) for output, so always build from the project root of the relevant subproject.

### Build and install the library

```bash
# 1. Annotations first — vibetags/ depends on it
cd vibetags-annotations
mvn install                                # Maven
gradle clean build publishToMavenLocal     # Gradle

# 2. Then the processor
cd ../vibetags
mvn clean install                          # Maven
gradle clean build publishToMavenLocal     # Gradle

# 3. Then the BOM (Maven only — Gradle reads it via mavenLocal)
cd ../vibetags-bom
mvn install
```

### Run tests

```bash
cd vibetags
mvn test                                   # unit tests only
mvn test -Drun.integration.tests=true      # include integration tests
gradle test                                # Gradle
```

### Run a single test class

```bash
cd vibetags
mvn test -Dtest=AnnotationProcessorEndToEndTest
mvn test -Dtest=AIGuardrailProcessorUnitTest#methodName
```

### Build the example (triggers annotation processing)

```bash
cd example
mvn clean compile     # Maven (library must be installed first)
gradle clean build    # Gradle
```

## Architecture

### Core processing flow

`AIGuardrailProcessor.process()` runs during `javac` compilation of the **consumer** project (not the library itself — the library disables annotation processing with `-proc:none`):

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

### Marker-based updates

Generated content is written between markers so the file can contain hand-authored content alongside generated guardrails:

- **HTML comments** (CLAUDE.md, llms.txt, llms-full.txt): `<!-- VIBETAGS-START -->` / `<!-- VIBETAGS-END -->`
- **Hash comments** (.cursorrules, .aiexclude, ignore files): `# VIBETAGS-START` / `# VIBETAGS-END`
- **No markers** (JSON/TOML config files): complete overwrite of the file

YAML front-matter in `.mdc` and `.md` files (Cursor, Trae, Roo granular rules) is preserved — markers are placed after the front-matter block.

Files written by an older version of VibeTags (without markers) are automatically migrated to the marker format on the next compile.

### Output files

| File | Platform | Format |
|---|---|---|
| `.cursorrules` | Cursor IDE | Markdown |
| `.cursor/rules/*.mdc` | Cursor IDE (granular) | YAML front-matter + Markdown |
| `CLAUDE.md` | Claude | XML + Markdown |
| `.aiexclude` | Gemini | Glob patterns |
| `AGENTS.md` | Codex CLI | Markdown |
| `.codex/` | Codex CLI | Config + Starlark |
| `gemini_instructions.md` | Gemini | Markdown |
| `.github/copilot-instructions.md` | GitHub Copilot | Markdown |
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

#### Granular rules

Cursor, Windsurf, Continue, Tabnine, Amazon Q, Trae, Roo Code, and the universal `.ai/rules/` standard all support per-class rule files. When a class or method is annotated, the processor writes one rule file per annotated class (filename derived from the fully-qualified class name). Orphaned granular files — for classes that have had annotations removed — are cleaned up **after** new files are written to prevent delete-then-recreate cycles.

#### llms.txt vs llms-full.txt

VibeTags follows the [llms.txt standard](https://llmstxt.org/) for LLM agent discovery:

- **`llms.txt`** — The Map: A concise directory listing all guardrail rules with links to the annotated class. Intended for LLM agents (e.g., Windsurf Cascade) to quickly understand the project's AI interaction rules without bloating the context window.
- **`llms-full.txt`** — The Book: A single expanded file with all rule details. Intended for large-context LLMs (Claude 4.6, Gemini 1.5 Pro) that can ingest the entire ruleset at once.

Both files follow the llms.txt format hierarchy: `# Title`, `> Summary blockquote`, informational text, and `## H2` resource sections.

### Processor options

Passed via `<compilerArg>-A...</compilerArg>` in Maven or `compilerArgs` in Gradle:

| Option | Default | Description |
|---|---|---|
| `vibetags.project` | `"This Project"` | Sets the `# H1` project name in llms.txt and llms-full.txt |
| `vibetags.root` | JVM working directory | Override the output directory for all generated files |
| `vibetags.log.path` | `vibetags.log` in root | Custom log file path (relative to root, or absolute) |
| `vibetags.log.level` | `INFO` | Log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF` |

### Annotations (all `RetentionPolicy.SOURCE`)

| Annotation | Targets | Key Attributes |
|---|---|---|
| `@AILocked` | TYPE, METHOD, FIELD | `reason: String` |
| `@AIContext` | TYPE, METHOD | `focus: String`, `avoids: String` |
| `@AIDraft` | TYPE, METHOD | `instructions: String` |
| `@AIAudit` | TYPE, METHOD | `checkFor: String[]` |
| `@AIIgnore` | TYPE, METHOD, FIELD | `reason: String` |
| `@AIPrivacy` | TYPE, METHOD, FIELD | `reason: String` |
| `@AICore` | TYPE, METHOD, FIELD | `sensitivity: String`, `note: String` |
| `@AIPerformance` | TYPE, METHOD | `constraint: String` |
| `@AIContract` | TYPE, METHOD | `reason: String` |

**Annotation semantics:**

- `@AILocked` — code is visible but must not be modified by AI
- `@AIIgnore` — code is excluded from AI context entirely (treat as non-existent); unlike `@AILocked`, the AI should not even be aware of it
- `@AIPrivacy` — element handles PII; AI must never include its runtime values in logs, test fixtures, mock data, or API suggestions (GDPR/HIPAA/PCI-DSS use cases)
- `@AICore` — marks well-tested, sensitive core logic (e.g., months to stabilize); AI is instructed to treat changes with extreme care
- `@AIPerformance` — enforces strict time/space complexity on hot-path code; AI must not introduce O(n²) or worse solutions
- `@AIContract` — freezes the public signature (method name, parameter types, parameter order, return type, checked exceptions); AI may change internal logic but must not alter the visible API surface

**Compile-time validation warnings:**

- `@AIDraft` + `@AILocked` on the same element — contradictory (locked but needs drafting)
- `@AIAudit` with empty `checkFor[]` — no-op; nothing to audit
- `@AIPrivacy` + `@AIIgnore` on the same element — redundant; ignore already excludes
- `@AIContract` + `@AIDraft` on the same element — contradictory (signature frozen but needs drafting)
- `@AIContract` + `@AILocked` on the same element — overlapping intent (`@AILocked` already prohibits all changes; `@AIContract` is redundant)
- `@AIIgnore` present but no `.cursorignore` / `.claudeignore` / `.copilotignore` / `.qwenignore` / `.aiexclude` exists — orphaned ignore annotation
- `@AILocked` present but no `.aiexclude` — Gemini/Codex lock not active

### Multi-module safety

In multi-module builds, if a module has **no new annotations**, the processor skips updating shared files (`.cursorrules`, `llms.txt`, etc.) to avoid overwriting annotations contributed by sibling modules.

### SPI registration

The processor is discovered via `META-INF/services/javax.annotation.processing.Processor`. The wildcard `@SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")` means new annotations are picked up automatically without touching the processor configuration.

### Gradle incremental annotation processing

The processor is declared as **aggregating** in `META-INF/gradle/incremental.annotation.processors`. Gradle therefore re-runs it only when annotations change anywhere in the compile unit, not on every unrelated `.java` edit. The category is `aggregating` (not `isolating`) because the generated files (`CLAUDE.md`, `.cursorrules`, `llms.txt`, etc.) are aggregated from annotations across the entire compilation unit — a per-source-file `isolating` declaration would produce stale output.

## Test Structure

All tests live in `vibetags/src/test`.

| Class | Coverage |
|---|---|
| `AnnotationDefinitionsTest` | Annotation structure and defaults (all 9 annotations) |
| `AIGuardrailProcessorTest` | Processor configuration |
| `AIGuardrailProcessorUnitTest` | Processor logic, opt-in, warning emission |
| `AIIgnoreProcessorUnitTest` | `@AIIgnore` annotation definition and opt-in behaviour |
| `AIPrivacyProcessorTest` | `@AIPrivacy` annotation definition, validation, and per-platform output |
| `AIContractProcessorTest` | `@AIContract` annotation definition, validation (contradictory/overlap combinations), and per-platform output |
| `AIGuardrailProcessorProcessTest` | `process()` method, `checkOrphanedAnnotations()`, `buildServiceFileMap()`, `writeFileIfChanged()` |
| `AnnotationProcessorEndToEndTest` | Generated file content |
| `GranularRulesEndToEndTest` | Cursor/Trae/Roo granular rule file generation |
| `NewPlatformsEndToEndTest` | Windsurf, Zed, Cody, Supermaven, Continue, Tabnine, Amazon Q, universal `.ai/rules/` |
| `QwenEndToEndTest` | Qwen-specific output |
| `QwenProcessorUnitTest` | Qwen processor options |
| `VibeTagsLoggerUnitTest` | File logging |
| `MultiModuleStabilityTest` | Multi-module safety (no-annotation module doesn't wipe shared files) |
| `AIGuardrailProcessorIntegrationTest` | Full workflow (requires `-Drun.integration.tests=true`) |

## Pre-commit Hooks

The repo uses `pre-commit` with Checkstyle, gitleaks (secret scanning), end-of-file fixer, and trailing-whitespace fixer. Run `pre-commit run --all-files` to check before committing.
