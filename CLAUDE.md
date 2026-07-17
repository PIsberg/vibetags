# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VibeTags is a **compile-time Java annotation processor** (`se.deversity.vibetags.processor.AIGuardrailProcessor`) that generates AI platform-specific guardrail configuration files from Java annotations. Zero runtime overhead — all annotations use `RetentionPolicy.SOURCE`.

The repo has these independent Maven (and where noted, Gradle) subprojects:
- `vibetags-annotations/` — the 39 `@interface` classes, zero deps. Goes on the consumer's compile classpath. **Build first** — `vibetags/` depends on it.
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

For the full technical deep dive (system diagram, data flow, design decisions, limitations), see `docs/ARCHITECTURE.md`. Summary of the load-bearing behaviors:

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

#### AGENTS.md sole-file fallback

`AGENTS.md` (the `codex` service) is a special case. Because it doubles as a near-universal
agent-instructions file and is frequently kept only as a thin pointer to another tool's file
(e.g. `CLAUDE.md`), `resolveActiveServices()` treats it as a write target **only when it is the
sole AI config file present**. If any other opt-in service is active, `codex` is dropped — which
also disables the Codex sidecar config (`.codex/config.toml`, `.codex/rules/`) it would otherwise
drive — and `AGENTS.md` is left untouched. Keep only `AGENTS.md` (remove the other AI config
files) to have VibeTags manage it.

### Marker-based updates

Generated content is written between markers so the file can contain hand-authored content alongside generated guardrails:

- **HTML comments** (CLAUDE.md, llms.txt, llms-full.txt): `<!-- VIBETAGS-START -->` / `<!-- VIBETAGS-END -->`
- **Hash comments** (.cursorrules, .aiexclude, ignore files): `# VIBETAGS-START` / `# VIBETAGS-END`
- **No markers** (JSON/TOML config files): complete overwrite of the file

YAML front-matter in `.mdc` and `.md` files (Cursor, Trae, Roo granular rules) is preserved — markers are placed after the front-matter block.

Files written by an older version of VibeTags (without markers) are automatically migrated to the marker format on the next compile.

### Output files

The processor can generate configuration for 50+ AI platforms (Cursor, Claude, Gemini, Codex, Copilot, Windsurf, granular per-class rules, AI PR reviewers, context packers, and more). Full file/platform/format table and granular-rules/llms.txt notes: `docs/PLATFORMS.md`.

### Processor options

Passed via `<compilerArg>-A...</compilerArg>` in Maven or `compilerArgs` in Gradle:

| Option | Default | Description |
|---|---|---|
| `vibetags.project` | `"This Project"` | Sets the `# H1` project name in llms.txt and llms-full.txt |
| `vibetags.root` | JVM working directory | Override the output directory for all generated files |
| `vibetags.log.path` | `vibetags.log` in root | Custom log file path (relative to root, or absolute) |
| `vibetags.log.level` | `INFO` | Log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF` |
| `vibetags.cache` | `true` | Set to `false` to disable the per-file write cache (`.vibetags-cache`) |
| `vibetags.check` | `false` | Opt-in check mode: verify generated files are in sync with the annotations instead of writing them; drift is reported as a compile **error** (CI enforcement). Writes nothing — no output files, no sidecars, no cache |

### Check mode (CI drift enforcement)

With `-Avibetags.check=true`, `process()` routes to `checkFiles()` instead of `generateFiles()`. It runs the same service resolution, content build, and multi-module merge (the module's sidecar save is simulated in memory), but uses a dry-run `GuardrailFileWriter` (`dryRun=true` constructor flag) that records every would-be write/scrub/delete into `dryRunChanges()` instead of touching disk. Any recorded path fails the build via `Messager.ERROR`. The fingerprint short-circuit and write cache are bypassed so the verdict never depends on cache state; internal failures in check mode fail closed (ERROR, not the usual downgrade-to-WARNING).

### Machine-readable lock report (`.vibetags-locks`)

An opt-in pseudo-platform (service key `locks_report`, touch `.vibetags-locks` to enable) that emits one JSON object per `@AILocked` element: element path, kind, source file, 1-based `startLine`/`endLine`, and reason. The first JSON record is `{"type":"format","version":N}` so consumers can reject reports written in a future, incompatible schema (filter on `type == "locked"` to skip it). The format is JSON Lines wrapped in `# VIBETAGS` hash markers — deliberately *not* a `.json` file, so it rides the module-sidecar merge in multi-module builds instead of last-writer-wins. Positions come from `SourcePositionResolver` (javac Compiler Tree API, resolved in `process()` while rounds are live); under non-javac compilers entries omit position fields. Consumed by the locked-files GitHub Action in `action/locked-files/`, which fails PRs whose diffs touch locked line ranges, strip `@AILocked` annotations, or delete locked files.

### Annotations

39 `@AI*` annotations, all `RetentionPolicy.SOURCE`. Full table, semantics, and validation-warning list: `docs/ANNOTATIONS.md`.

### Top-level fingerprint short-circuit

The processor records a fingerprint of the build inputs — the processor version (`ProcessorVersion`), every collected annotation (FQN + attribute values), plus the resolved active-services set — into `.vibetags-cache` under a `# fingerprint: <hex>` header. Folding the version in means upgrading the processor invalidates the previous fingerprint, so a release that renders different content from unchanged annotations can never be skipped. The cache file also carries a `# format: <n>` header; a cache written by a newer (unknown) format is discarded wholesale rather than mis-parsed. On the next compile, if the fingerprint still matches AND every previously written file is byte-stable on disk (size + mtime unchanged), the entire generate phase is skipped: no `GuardrailContentBuilder.build()`, no per-file compares, no writes. The two-part guard means a manually deleted granular file still triggers regeneration on the next compile (its `size`/`mtime` no longer matches the cache entry).

### Multi-module safety

In multi-module builds, if a module has **no new annotations**, the processor skips updating shared files (`.cursorrules`, `llms.txt`, etc.) to avoid overwriting annotations contributed by sibling modules.

### Internal class responsibilities

Beyond what the VibeTags-generated section below describes, three additional internal classes handle cross-cutting concerns:

- `AnnotationValidator` — all compile-time consistency checks (contradictory combinations, no-op annotations, invalid values) extracted from `AIGuardrailProcessor` into a single class; add new warnings here
- `ElementNaming` — constructs fully-qualified element paths (e.g., `com.example.Foo.bar`) for use in generated output; handles TYPE, METHOD, FIELD, and PACKAGE kinds
- `OrphanWarner` — emits warnings when an annotation is present but the corresponding platform opt-in file is absent (e.g., `@AIIgnore` with no `.cursorignore`)

### SPI registration

The processor is discovered via `META-INF/services/javax.annotation.processing.Processor`. The wildcard `@SupportedAnnotationTypes("*")` means new annotations are picked up automatically without touching the processor configuration.

### Gradle incremental annotation processing

The processor is declared as **aggregating** in `META-INF/gradle/incremental.annotation.processors`. Gradle therefore re-runs it only when annotations change anywhere in the compile unit, not on every unrelated `.java` edit. The category is `aggregating` (not `isolating`) because the generated files (`CLAUDE.md`, `.cursorrules`, `llms.txt`, etc.) are aggregated from annotations across the entire compilation unit — a per-source-file `isolating` declaration would produce stale output.

## Reference docs (read on demand)

- `docs/ANNOTATIONS.md` — adding or changing an annotation? Full annotation table, semantics, and validation warnings.
- `docs/PLATFORMS.md` — adding a platform, or a question about a specific output file.
- `docs/TESTS.md` — which test class covers what.
- `docs/ARCHITECTURE.md` — deep dive: system diagram, data flow, design decisions, limitations.
- `USAGE.md` — consumer-facing usage (how to add VibeTags to a project).

## Pre-commit Hooks

The repo uses `pre-commit` with Checkstyle, gitleaks (secret scanning), end-of-file fixer, and trailing-whitespace fixer. Run `pre-commit run --all-files` to check before committing.

<!-- VIBETAGS-START -->
<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->
<project_guardrails>
  <locked_files>
    <file path="se.deversity.vibetags.processor.AIGuardrailProcessor.generateFiles()">
      <reason>Step order is load-bearing: fingerprint check → sidecar write → sidecar read → merge → file write → cache flush; reordering steps silently skips regeneration or corrupts multi-module output</reason>
    </file>
  </locked_files>
  <contextual_instructions>
    <file path="se.deversity.vibetags.processor.internal.AnnotationCollector">
      <focus>Accumulates annotated elements across multiple javac processing rounds; one LinkedHashSet per annotation type preserves insertion order for stable BuildFingerprint output</focus>
      <avoids>Replacing LinkedHashSet with HashSet — insertion order stability is required for deterministic fingerprints across recompiles</avoids>
    </file>
    <file path="se.deversity.vibetags.processor.internal.GranularRulesWriter">
      <focus>Writes per-class granular rule files for Cursor, Windsurf, Trae, Roo, and similar platforms; cleanup runs AFTER write to avoid delete-then-recreate cycles</focus>
      <avoids>Running cleanup before write — would delete files that are about to be recreated, causing spurious filesystem events and empty windows for incremental build tools</avoids>
    </file>
    <file path="se.deversity.vibetags.processor.internal.ServiceRegistry">
      <focus>Maps platform service keys to output file paths; resolves active services by checking file existence on disk</focus>
      <avoids>Creating output files that do not already exist — file presence on disk is the user's explicit opt-in signal</avoids>
    </file>
  </contextual_instructions>
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
  <performance_constraints>
    <element path="se.deversity.vibetags.processor.internal.BuildFingerprint.fingerprint(java.lang.String)">
      <constraint>O(N) in string length; uses String.hashCode() which HotSpot intrinsifies on x86; must not allocate intermediate byte[]</constraint>
    </element>
    <element path="se.deversity.vibetags.processor.internal.WriteCache.isUnchanged(java.nio.file.Path,java.lang.String)">
      <constraint>O(1): one stat(2) syscall plus one 8-char string compare; must not allocate byte[] — the prior CRC32C implementation did and was removed for this reason</constraint>
    </element>
  </performance_constraints>

<rule>Elements listed in <performance_constraints> are on a hot path. Never introduce O(n²) or worse complexity. Always reason about time and space complexity before suggesting changes.</rule>
  <contract_signatures>
    <element path="se.deversity.vibetags.processor.AIGuardrailProcessor.process(java.util.Set<? extends javax.lang.model.element.TypeElement>,javax.annotation.processing.RoundEnvironment)">
      <reason>JSR 269 contract: must return false so peer annotation processors can claim the same annotations; return type is fixed by AbstractProcessor</reason>
    </element>
    <element path="se.deversity.vibetags.processor.internal.BuildFingerprint.compute(se.deversity.vibetags.processor.internal.AnnotationCollector,java.util.Set<java.lang.String>)">
      <reason>Same inputs must always produce the same 8-hex output across JVM restarts; changing the algorithm silently invalidates all existing .vibetags-cache files</reason>
    </element>
    <element path="se.deversity.vibetags.processor.internal.GuardrailFileWriter.writeFileIfChanged(java.lang.String,java.lang.String,boolean)">
      <reason>Public API since v0.1; tests and the processor both bind to the (String path, String content, boolean hasNewRules) signature and return semantics</reason>
    </element>
    <element path="se.deversity.vibetags.processor.internal.ModuleSidecar.mergeFor(java.lang.String,java.util.List<se.deversity.vibetags.processor.internal.ModuleSidecar>,boolean)">
      <reason>Sub-marker format constants (SUB_MARKER_*_FORMAT) are embedded in generated CLAUDE.md and .cursorrules; changing them silently corrupts multi-module merged output on the next compile</reason>
    </element>
  </contract_signatures>

<rule>You may refactor the internal logic of elements listed in <contract_signatures>, but you MUST NOT change their public signatures: method names, parameter types, parameter order, return types, or checked exceptions.</rule>
  <immutable_types>
    <type path="se.deversity.vibetags.processor.internal.BuildFingerprint">
      <note>Purely stateless; private constructor prevents instantiation; all computation results are returned as values</note>
    </type>
  </immutable_types>

<rule>Types listed in <immutable_types> are immutable by design. Never introduce non-final fields, setters, or methods that mutate instance state.</rule>
</project_guardrails>

<rule>Never propose edits to files listed in <locked_files>.</rule>
<!-- VIBETAGS-END -->
