# Processor options, caching, and check mode

Build-time knobs and the machinery that decides whether the processor does any work at all. Split out
of `CLAUDE.md`.

## Processor options

Passed via `<compilerArg>-A...</compilerArg>` in Maven or `compilerArgs` in Gradle:

| Option | Default | Description |
|---|---|---|
| `vibetags.project` | `"This Project"` | Sets the `# H1` project name in llms.txt and llms-full.txt |
| `vibetags.root` | JVM working directory | Override the output directory for all generated files |
| `vibetags.module` | resolved from the sources | Name this module explicitly in multi-module output. Only needed when the module cannot be read off the compiled sources (no build file above them, or a compiler exposing neither the Tree API nor `Elements.getFileObjectOf`) — the processor warns when it has to fall back to a content hash |
| `vibetags.log.path` | `vibetags.log` in root | Custom log file path (relative to root, or absolute) |
| `vibetags.log.level` | `INFO` | Log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF` |
| `vibetags.cache` | `true` | Set to `false` to disable the per-file write cache (`.vibetags-cache`) |
| `vibetags.check` | `false` | Opt-in check mode: verify generated files are in sync with the annotations instead of writing them; drift is reported as a compile **error** (CI enforcement). Writes nothing — no output files, no sidecars, no cache |
| `vibetags.enforce` | (off) | Opt-in **enforcing mode**: comma-separated families (`locked`, `contract`, `publicapi`, or `all`) whose guarded elements must match `.vibetags-baseline`. A drift is a compile **error**. See below |
| `vibetags.baseline.update` | `false` | Record the current shapes into `.vibetags-baseline` instead of checking them. The only thing that writes that file |

## Enforcing mode (`-Avibetags.enforce`)

Guardrails are advisory by design: the enforcement in a consuming project comes from checkstyle/PMD/
SpotBugs, and VibeTags' job is to make the mistake less likely rather than impossible. That default
is unchanged. What this adds, opt-in and per family, is a hard stop for the guardrails whose promise
the processor can actually **prove** from the javac element model (issue #284).

- `locked` (`@AILocked`), `contract` (`@AIContract`), `publicapi` (`@AIPublicAPI`) — the element's
  structural signature must match the committed baseline. `ElementSignature` renders a method as
  name + parameter types + return type + checked exceptions, and a type as its supertypes plus its
  public/protected member signatures, sorted. Bodies, comments, formatting and private members are
  invisible: an enforcement that fires when someone reformats a locked file gets switched off.

  Those signatures are computed **only when this option (or `-Avibetags.baseline.update`) is set**.
  Rendering a type's visible member set and sorting it is the most expensive thing the collector
  does per element, and nothing but enforcement reads the result — on 400 wide classes it is
  36 MB of allocation, about 7 % of the processor's own overhead. It cannot be deferred instead:
  the javac element model is valid only while its round is live, and the model is read after the
  last round closes, so `AnnotationCollector.captureSignatures(boolean)` is set up front from these
  two options. A build that turns enforcement on therefore costs slightly more than one that does
  not, by design.
- **Not enforceable, and said so out loud.** `@AICallersOnly` and `@AIStrictClasspath` need
  call-graph and method-body analysis a processor cannot do portably — the Tree API is unavailable
  under Gradle (see `ModuleRootResolver`) — and `@AIThreadSafe`/`@AITestDriven` are semantic. Naming
  one produces a `[WARNING]` explaining the boundary rather than silent non-enforcement.

The baseline (`.vibetags-baseline`, committed) stores full signatures, sorted, one per line, keyed
by **module id** — so a reactor's modules merge into it instead of overwriting each other, the same
discipline as the sidecars. Full signatures rather than hashes because the point is that a pull
request shows *what* changed.

Two directions are checked, and the second is the one that matters: an element's path already
encodes its parameter types, so changing `charge(String,double)` to `charge(String,long)` abandons
the approved entry rather than editing it. An approved entry with no matching element in the
compilation is therefore a violation too — that covers renames, deletions and removed annotations.

Enforcement runs in `process()` before `generateFiles()`, deliberately: the generation path has a
fingerprint short-circuit that would let an unchanged-inputs build skip the check silently.
Switching the option on before a baseline exists warns and checks nothing, rather than failing every
build on day one.

## Top-level fingerprint short-circuit

The processor records a fingerprint of the build inputs — the processor version (`ProcessorVersion`),
every collected annotation (FQN + attribute values), plus the resolved active-services set — into
`.vibetags-cache` under a `# fingerprint: <hex>` header. Folding the version in means upgrading the
processor invalidates the previous fingerprint, so a release that renders different content from
unchanged annotations can never be skipped. The cache file also carries a `# format: <n>` header; a
cache written by a newer (unknown) format is discarded wholesale rather than mis-parsed.
The project name (`-Avibetags.project`) and module override (`-Avibetags.module`) shape rendered
output without being part of the annotation fingerprint, so they are bound separately as a *run
context* (`# context: <hex>` in the same cache file, via `WriteCache.bindContext`); a stored
fingerprint recorded under a different context is treated as absent and the build regenerates.

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

**In a reactor, check mode needs a normal build first.** It reads the same `.vibetags-mod-*`
sidecars the merge does, and those are gitignored, so on a fresh clone they do not exist yet. Run as
the *first* command after cloning, check mode sees each module in isolation, judges the committed
root files to be missing every other module's content, and fails at the first module:

```
$ git clone <repo> && cd <reactor> && mvn -B clean compile -Dvibetags.check=true
[ERROR] VibeTags: check failed — 306 guardrail file(s) are out of date with the annotations
[INFO] BUILD FAILURE          (module [2/6])
```

Nothing is wrong with the tree. Build once, then check:

```
mvn -B clean compile && mvn -B compile -Dvibetags.check=true
```

CI does exactly this — the check step runs after the ordinary build, not instead of it. Single-module
projects are unaffected: one module is the whole project, so the first compile already sees everything.

## Machine-readable lock report (`.vibetags-locks`)

An opt-in pseudo-platform (service key `locks_report`, touch `.vibetags-locks` to enable) that emits
one JSON object per `@AILocked` element: element path, kind, source file, 1-based
`startLine`/`endLine`, and reason. The first JSON record is `{"type":"format","version":N}` so
consumers can reject reports written in a future, incompatible schema (filter on `type == "locked"`
to skip it). The format is JSON Lines wrapped in `# VIBETAGS` hash markers — deliberately *not* a
`.json` file, so it rides the module-sidecar merge in multi-module builds instead of
last-writer-wins. Positions come from `SourcePositionResolver` (javac Compiler Tree API, resolved in
`process()` while rounds are live) as `model.SourceLocation`. Gradle's incremental-processing
wrapper is reflectively unwrapped so positions survive Gradle-run javac (`treesFor`); under
genuinely non-javac compilers (ECJ) entries omit position fields.

The `kind` field is `ElementTag.name()`, which mirrors `javax.lang.model.element.ElementKind`
name-for-name (`CLASS`, `METHOD`, `FIELD`, …), plus `UNKNOWN` when the compiler reports no kind.
Renaming a constant is a breaking change to this format, not a refactor — `ElementTagMappingTest`
pins the two enums together.

Consumed by the locked-files GitHub Action in `action/locked-files/`, which fails PRs whose diffs
touch locked line ranges, strip `@AILocked` annotations, or delete locked files.

## SPI registration

The processor is discovered via `META-INF/services/javax.annotation.processing.Processor`. It
claims `@SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")` — the package wildcard,
not the universal `"*"` this section used to claim — so a new VibeTags annotation is picked up
automatically without touching the processor configuration, and a compilation carrying no VibeTags
annotations never pays for the processor at all.

The flip side: javac only invokes the processor when at least one VibeTags annotation is present
in the compiled sources. A source set whose last `@AI*` annotation was just removed does not run
VibeTags — nothing is regenerated or cleaned up for it, and `-Avibetags.check=true` verifies
nothing either, so a CI drift gate passes vacuously until some VibeTags annotation exists again.
The round universe is also declaration-scoped: an `@AI*` annotation inside a method body (a local
class, an anonymous class member) neither triggers the processor nor reaches it. When the
processor runs for some other reason, `MethodBodyGuardrailScanner` spots those through the Tree
API and warns instead of letting them be a silent no-op; a compilation whose only guardrails sit
inside bodies stays wholly invisible.

## Gradle incremental annotation processing

The processor is declared as **aggregating** in
`META-INF/gradle/incremental.annotation.processors`, the truthful category: the generated files
(`CLAUDE.md`, `.cursorrules`, `llms.txt`, etc.) aggregate annotations from the entire compilation
unit, and an `isolating` declaration would produce stale output.

Do not read that declaration as a promise of incremental behaviour. Gradle's incremental
annotation processing contract says aggregating processors "can only read `CLASS` or `RUNTIME`
retention annotations", and every VibeTags annotation is deliberately `SOURCE` retention (zero
runtime footprint) — which puts VibeTags outside the documented support envelope. What Gradle
does with a rule-breaking processor is version-dependent; its userguide promises nothing better
than "silent failures". The output stays correct whenever the processor re-sees every annotated
source (the full-recompilation case); treat a Gradle build where only a subset of sources
recompiled as suspect and prefer a clean build before trusting regenerated guardrails. The
"re-runs only when annotations change" incrementality this section previously claimed is not
something a consumer should count on.
