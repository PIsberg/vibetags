# Processor options, caching, and check mode

Build-time knobs and the machinery that decides whether the processor does any work at all. Split out
of `CLAUDE.md`.

## Processor options

Passed via `<compilerArg>-A...</compilerArg>` in Maven or `compilerArgs` in Gradle:

| Option | Default | Description |
|---|---|---|
| `vibetags.project` | `"This Project"` | Sets the `# H1` project name in llms.txt and llms-full.txt |
| `vibetags.root` | JVM working directory | Override the output directory for all generated files |
| `vibetags.log.path` | `vibetags.log` in root | Custom log file path (relative to root, or absolute) |
| `vibetags.log.level` | `INFO` | Log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF` |
| `vibetags.cache` | `true` | Set to `false` to disable the per-file write cache (`.vibetags-cache`) |
| `vibetags.check` | `false` | Opt-in check mode: verify generated files are in sync with the annotations instead of writing them; drift is reported as a compile **error** (CI enforcement). Writes nothing — no output files, no sidecars, no cache |

## Top-level fingerprint short-circuit

The processor records a fingerprint of the build inputs — the processor version (`ProcessorVersion`),
every collected annotation (FQN + attribute values), plus the resolved active-services set — into
`.vibetags-cache` under a `# fingerprint: <hex>` header. Folding the version in means upgrading the
processor invalidates the previous fingerprint, so a release that renders different content from
unchanged annotations can never be skipped. The cache file also carries a `# format: <n>` header; a
cache written by a newer (unknown) format is discarded wholesale rather than mis-parsed.

On the next compile, if the fingerprint still matches AND every previously written file is
byte-stable on disk (size + mtime unchanged), the entire generate phase is skipped: no
`GuardrailContentBuilder.build()`, no per-file compares, no writes. The two-part guard means a
manually deleted granular file still triggers regeneration on the next compile (its `size`/`mtime` no
longer matches the cache entry).

Where that check sits relative to everything else the orchestrator does — the fingerprint test
runs *before* the sidecar write, which runs before the merge, which runs before any file write —
is visible in the parsed call order,
[`diagrams/codekarta/sequence/aiguardrailprocessor-sequence-diagram.svg`](diagrams/codekarta/sequence/aiguardrailprocessor-sequence-diagram.svg)
([why it exists](LOAD-BEARING.md#core-processing-flow)).

### Watched inputs

Config files VibeTags *reads* rather than writes can also gate the short-circuit. `.vibetags-mirror`
lives in a sibling module, so it can never reach the compiling module's active-service set; it is
registered with `WriteCache.recordInput` instead, which stores size+mtime under the `input---`
sentinel in the hash column. Such an entry can never satisfy a write-skip comparison, and
`allCachedFilesStable()` prunes it when the file disappears — otherwise a removed opt-in would
suppress the short-circuit forever, since nothing would ever re-record it.

## Check mode (CI drift enforcement)

With `-Avibetags.check=true`, `process()` routes to `checkFiles()` instead of `generateFiles()`. It
runs the same service resolution, content build, and multi-module merge (the module's sidecar save is
simulated in memory), but uses a dry-run `GuardrailFileWriter` (`dryRun=true` constructor flag) that
records every would-be write/scrub/delete into `dryRunChanges()` instead of touching disk. Any
recorded path fails the build via `Messager.ERROR`. The fingerprint short-circuit and write cache are
bypassed so the verdict never depends on cache state; internal failures in check mode fail closed
(ERROR, not the usual downgrade-to-WARNING).

## Machine-readable lock report (`.vibetags-locks`)

An opt-in pseudo-platform (service key `locks_report`, touch `.vibetags-locks` to enable) that emits
one JSON object per `@AILocked` element: element path, kind, source file, 1-based
`startLine`/`endLine`, and reason. The first JSON record is `{"type":"format","version":N}` so
consumers can reject reports written in a future, incompatible schema (filter on `type == "locked"`
to skip it). The format is JSON Lines wrapped in `# VIBETAGS` hash markers — deliberately *not* a
`.json` file, so it rides the module-sidecar merge in multi-module builds instead of
last-writer-wins. Positions come from `SourcePositionResolver` (javac Compiler Tree API, resolved in
`process()` while rounds are live) as `model.SourceLocation`; under non-javac compilers entries omit
position fields.

The `kind` field is `ElementTag.name()`, which mirrors `javax.lang.model.element.ElementKind`
name-for-name (`CLASS`, `METHOD`, `FIELD`, …), plus `UNKNOWN` when the compiler reports no kind.
Renaming a constant is a breaking change to this format, not a refactor — `ElementTagMappingTest`
pins the two enums together.

Consumed by the locked-files GitHub Action in `action/locked-files/`, which fails PRs whose diffs
touch locked line ranges, strip `@AILocked` annotations, or delete locked files.

## SPI registration

The processor is discovered via `META-INF/services/javax.annotation.processing.Processor`. The
wildcard `@SupportedAnnotationTypes("*")` means new annotations are picked up automatically without
touching the processor configuration.

Note that javac only invokes a `"*"` processor for a compilation round that actually contains
annotations — a source set with no annotation of any kind (not even `@Override`) never runs VibeTags,
so nothing is regenerated or cleaned up for it.

## Gradle incremental annotation processing

The processor is declared as **aggregating** in
`META-INF/gradle/incremental.annotation.processors`. Gradle therefore re-runs it only when
annotations change anywhere in the compile unit, not on every unrelated `.java` edit. The category is
`aggregating` (not `isolating`) because the generated files (`CLAUDE.md`, `.cursorrules`, `llms.txt`,
etc.) are aggregated from annotations across the entire compilation unit — a per-source-file
`isolating` declaration would produce stale output.
