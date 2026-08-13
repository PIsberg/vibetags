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
| `vibetags.check` | `false` | Opt-in check mode: verify generated files are in sync with the annotations instead of writing them; drift is reported as a compile **error** (CI enforcement). Writes none of the files VibeTags manages in the project — no output files, no sidecars, no cache. Dependency manifests still go to `CLASS_OUTPUT`; see below |
| `vibetags.enforce` | (off) | Opt-in **enforcing mode**: comma-separated families (`locked`, `contract`, `publicapi`, or `all`) whose guarded elements must match `.vibetags-baseline`. A drift is a compile **error**. See below |
| `vibetags.baseline.update` | `false` | Record the current shapes into `.vibetags-baseline` instead of checking them. The only thing that writes that file |
| `vibetags.manifest.origin` | read from `.vibetags-manifest` | The `group:artifact:version` stamped into published manifests, overriding the marker file's first line |
| `vibetags.manifest.dir` | (off) | Read dependency manifests from a directory of extracted `*.json` files instead of, or in addition to, the compile classpath |
| `vibetags.manifest.packages` | (off) | Comma-separated package names to look up explicitly, for builds where the compiler exposes no Tree API |
| `vibetags.manifest.max` | (no limit) | Cap on inherited **advisory** rules. Safety-tier rules are never dropped, and a cap that drops anything says so as a `NOTE` |

## Transitive guardrails (dependency tree propagation)

Package-level `@AI...` annotations can travel from a library into the projects that depend on it.
Both halves are file-presence opt-ins, like every other VibeTags output.

| Marker at the VibeTags root | Effect |
|---|---|
| `.vibetags-manifest` | **Publish.** Each module writes one manifest per annotated package into its own `CLASS_OUTPUT`, at `vibetags/manifests/<package>.json`, which the JAR task packages with no further configuration. The file's first non-blank, non-comment line is the artifact coordinate stamped as `origin` |
| `.vibetags-transitive` | **Consume.** Manifests found on the compile classpath are rendered into the aggregate instruction files under `## Inherited Guardrails (dependencies)` (safety tier) and `## Inherited Context (dependencies)` (advisory tier), after everything the project says about itself |

Only annotations on `package-info.java` propagate. Type-level and method-level guardrails stay
local: propagating them would scale a manifest with the library's whole API surface, and a consumer
cannot act on a rule about a class it never sees. Thirteen annotations accept `ElementType.PACKAGE`
— see the target column in [`ANNOTATIONS.md`](ANNOTATIONS.md).

### Why the resource path is `vibetags/manifests/`, not `META-INF/`

Measured, not chosen. javac's `CLASS_PATH` location skips archive directories whose names are not
valid Java package identifiers, so a resource under `META-INF/` is unreadable from an annotation
processor: `Filer.getResource` throws `FileNotFoundException`, and javac's own file manager lists
zero entries there even with `--add-exports` granted. `TransitiveGuardrailLifecycleE2ETest`
repacks a fixture JAR under `META-INF/` and asserts the rules do **not** arrive, so moving the
manifest to the conventional location fails the build rather than silently disabling the feature.

### How discovery works, and what it costs

There is no supported way for a processor to *enumerate* the compile classpath.
`ClassLoader.getResources` sees the processor path, not the classpath, and returns nothing under the
documented `annotationProcessorPaths` setup. `Filer.getResource` works, but needs the exact name up
front — so manifests are keyed by the package they govern, and the keys come from the packages this
compilation actually imports (read through the Tree API, as `@AIArchitecture` already does).

Three things follow. A package the project never imports is never looked up, so inherited volume is
bounded by what the project uses rather than by the size of the dependency graph. A library cannot
publish a rule under another library's key, because the key is a filename inside its own JAR. And
the cost is one lookup per distinct imported package prefix — `java.*`, `javax.*`, `jdk.*`, `sun.*`
and the JVM languages' runtimes are skipped, and the total is capped at 4096 per compilation, with
the cap logged when reached.

A candidate is a *package*, so the type and member an import names are dropped before the prefix
walk: `import a.b.C` probes `a.b` and `a`-rooted parents but never `a.b.C`, and
`import static a.b.C.m` drops both `C` and `m`. A manifest is keyed by the package it governs, so a
class-name lookup is a guaranteed miss — one per import in the project, which is bounded but
entirely avoidable.

Where the Tree API is absent (kapt, ECJ, some Gradle workers) or the dependencies live on the
module path rather than the classpath, discovery finds nothing and says so as a `NOTE` — unchecked
is reported distinctly from clean. `-Avibetags.manifest.dir` and `-Avibetags.manifest.packages` are
the supported ways through.

### Check mode still publishes

Check mode writes none of the files VibeTags manages in the project, but it does publish manifests
into `CLASS_OUTPUT`. That is the compiler's own output directory, which javac is filling with class
files regardless, and skipping the write would break the check rather than tighten it: in a reactor
that both publishes and consumes, one module's manifest is what the next module reads off the
classpath. Without it every consuming module inherits nothing, compares that against committed files
that correctly carry the inherited rules, and reports drift on a build where nothing is wrong.

### Split packages

Two JARs shipping a manifest for the same package is a split package. `Filer.getResource` returns
the first on the classpath and the processor cannot see the second, so the collision is undetectable
on that path and the documentation says so rather than promising detection. `-Avibetags.manifest.dir`
*can* see both, because the build tool did the resolving.

When both rules do reach the model — combining the directory with classpath discovery, which is
supported and additive — the rendered output groups by package *and* origin, so each rule sits under
its own artifact. Grouping by package alone would print the second under the first one's coordinate
and misattribute a constraint to a dependency that never made it.

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

Guardrails inherited from dependencies are folded into the fingerprint alongside the annotation
set. They have to be: a dependency upgrade changes what the generated files should say while every
annotation in the consuming project stays byte-identical, so a fingerprint blind to them would
match, the generate phase would be skipped, and the committed files would keep describing the
previous version with nothing anywhere reporting a problem. `TransitiveFingerprintTest` asserts it
directly rather than through the end-to-end path, because that path also rewrites the module
sidecar every run and would pass either way.

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
`startLine`/`endLine`, and reason. `file` is **relative to the VibeTags root**, because the report is
meant to be committed and an absolute path would make it differ on every machine and every CI runner;
a source the root cannot claim (generated output, another drive, an in-memory JSR 199 unit) is
reported verbatim instead. The first JSON record is `{"type":"format","version":N}` so
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
