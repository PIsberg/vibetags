# Architecture: System Architecture

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## System Architecture

### Component Diagram

![Component Diagram](../diagrams/component-diagram.png)

*Figure 1: High-level system architecture showing component interactions*

**Technical Flow:**
1. Developer annotates Java source code with VibeTags annotations
2. Build system (Maven/Gradle) invokes `javac` compiler
3. `javac` discovers `AIGuardrailProcessor` via SPI (`META-INF/services/`)
4. Processor scans annotations during compilation
5. Processor generates platform-specific config files to project root
6. Compiled bytecode contains zero VibeTags artifacts

### Class Diagram

![Class Diagram](../diagrams/codekarta/class-diagram.svg)

*Figure 2: `se.deversity.vibetags.processor` — parsed from source by code-karta*

The hand-drawn PlantUML class diagram that used to stand here is [archived](../diagrams/archive/):
it drew 8 of the 44 annotations and named 8 internal helper classes, of which `processor/internal/`
now holds 24 at the top level alone (129 files counting its subpackages).
Hand-maintained structure drifts, and that one had. Both halves of what it showed are parsed
from source instead — the processor above, and
[the annotation surface](../ANNOTATIONS.md#the-annotation-surface) in the annotation reference.

### Parsed diagrams (code-karta)

The component, sequence, data-flow and platform-output diagrams in the [architecture deep dive](../ARCHITECTURE.md) are hand-drawn:
they say what the design *intends*, and they show actors — a developer, Maven, javac — that no
parser can see. The SVGs under [`diagrams/codekarta/`](../diagrams/codekarta/) are parsed from the
source by [code-karta](https://github.com/PIsberg/codekarta) and say what the code currently
*is*. Keeping both is deliberate — when they disagree, that gap is real drift, and it is the
kind nothing else in the build reports.

All five are produced by one script,
[`tools/generate-architecture-diagrams.sh`](../../tools/generate-architecture-diagrams.sh), which
is also where the input scope of each is pinned:

| Diagram | Parsed from | Embedded in | What it answers |
|---------|-------------|-------------|-----------------|
| [`class-diagram.svg`](../diagrams/codekarta/class-diagram.svg) | [`processor/`](../../vibetags/src/main/java/se/deversity/vibetags/processor) | this page, above | How the orchestrator, the internals and the model relate |
| [`model/class-diagram.svg`](../diagrams/codekarta/model/class-diagram.svg) | [`processor/model/`](../../vibetags/src/main/java/se/deversity/vibetags/processor/model) | [LOAD-BEARING.md](../LOAD-BEARING.md#the-compiler-boundary-internal--model--content) | The compiler-free data model the rendering layer reads |
| [`content/class-diagram.svg`](../diagrams/codekarta/content/class-diagram.svg) | [`internal/content/`](../../vibetags/src/main/java/se/deversity/vibetags/processor/internal/content) | [PLATFORMS.md](../PLATFORMS.md#the-rendering-layer) | Which renderer, formatter and registry a new platform plugs into |
| [`annotations/class-diagram.svg`](../diagrams/codekarta/annotations/class-diagram.svg) | [`annotations/`](../../vibetags-annotations/src/main/java/se/deversity/vibetags/annotations) | [ANNOTATIONS.md](../ANNOTATIONS.md#the-annotation-surface) | Every `@AI*` type that actually exists, counted by a parser rather than by hand |
| [`sequence/aiguardrailprocessor-sequence-diagram.svg`](../diagrams/codekarta/sequence/aiguardrailprocessor-sequence-diagram.svg) | [`AIGuardrailProcessor.java`](../../vibetags/src/main/java/se/deversity/vibetags/processor/AIGuardrailProcessor.java) | [Build Sequence](processing.md#build-sequence), [LOAD-BEARING.md](../LOAD-BEARING.md#core-processing-flow) | The orchestrator's real call order — the thing `<locked_files>` protects |

Regenerate with:

```bash
sh tools/generate-architecture-diagrams.sh
```

The CLI is resolved from Maven Central, so only the first run needs a network and nothing is
vendored into the repository. The diagrams are committed rather than built in CI: they describe
shape, shape changes rarely, and a diff in one of them is a signal worth reading in a pull
request. Adding a diagram means adding it to the script *and* linking it from the doc whose
question it answers — an unreferenced SVG in a repository is a file nobody regenerates.

**Scope, not settings.** Every diagram is aimed at one package on purpose. A stitched call graph
over `processor.internal` produced 986 nodes across roughly 36000×43700 pixels — technically a
diagram, practically a data dump — and `--max-depth` does not help, because the fan-out is
horizontal rather than deep. Scope the input instead. The script also pins `--layout elk`: the
default engine lays every node of one BFS depth into a single unbounded row, which for this
processor renders about 19500px wide against ELK's 2300px.

**Two of code-karta's modes do not fit this repository**, and the script says so in a comment so
the experiment isn't repeated. `--modules-only` needs `module-info.java`; VibeTags ships no JPMS
descriptors, because the processor has to load on whatever classpath a consumer's javac hands
it, so the parsed graph comes back empty. `--state-machine` reads enum constants as states, and
the two enums here — `content.Platform` and `model.ElementTag` — are catalogues rather than
machines: the generated SVG is sixty-odd boxes with zero transition edges. The tables in
[PLATFORMS.md](../PLATFORMS.md) and [ANNOTATIONS.md](../ANNOTATIONS.md) render that shape better.

**Key Components:**

**Annotations** — package `se.deversity.vibetags.annotations`, jar `vibetags-annotations` (see the [project facts](../../README.md#project-facts) for the count). Full list, targets, attributes, and semantics: [docs/ANNOTATIONS.md](../ANNOTATIONS.md).

**Processor** — package `se.deversity.vibetags.processor`, jar `vibetags-processor`:
- `AIGuardrailProcessor` — extends `AbstractProcessor` (JSR 269); orchestrator that wires the helpers below into the JSR 269 lifecycle and does none of the work itself
- `VibeTagsLogger` — SLF4J/Logback file logger, configurable via `-Avibetags.log.*`
- `LazyFileAppender` — the appender behind that logger; it opens `vibetags.log` on the first event that survives level filtering, so a build that logs nothing leaves no file behind
- `@SupportedAnnotationTypes("*")` — processes all annotations
- Overrides `getSupportedSourceVersion()` to return `SourceVersion.latestSupported()` instead of a fixed `@SupportedSourceVersion` — the library builds/tests against Java 21, but pinning e.g. `RELEASE_17` would make javac emit a "supported source version" warning on every newer JDK a consumer compiles with
- Compile-scope dependency on `vibetags-annotations` so the processor code can reference annotation classes (e.g. `roundEnv.getElementsAnnotatedWith(AILocked.class)`) and so legacy single-coordinate consumers still get the annotations transitively.

**Internal helpers** — package `se.deversity.vibetags.processor.internal` (single-responsibility classes that do the actual work, since 0.6.0):
- `AnnotationCollector` — owns one `LinkedHashSet<Element>` accumulator per annotation type (keyed by annotation class, driven by `model.GuardrailAnnotations.ALL`), aggregating annotated elements across all `javac` rounds; also tracks the `anyAnnotationsFound` flag used for the multi-module preservation check. `model()` snapshots the accumulators into the compiler-free `GuardrailModel` the rendering layer reads — memoized until the next `collect()`/`reset()`. Buckets are created once and only cleared **in place**, because the processor holds three of them as fields
- `AnnotationValidator` — the entry point for compile-time consistency warnings. The checks are individually testable rules in `internal/validation/`: `PairRule` (contradictory annotation pairs, as a table), `CoreRules` (attributes that leave an annotation instructing nobody), `ArchitectureRule` (the Tree-API import scan for `@AIArchitecture(cannotReference)`), `ModernJavaRules` (an annotation that contradicts the declaration it sits on — records, sealed types, virtual threads, the unnamed package). `ValidationRules` indexes rules by the annotation they scan so the round is queried once per annotation type rather than once per check. Full list in [ANNOTATIONS.md](../ANNOTATIONS.md)
- `OrphanWarner` — emits warnings when annotations are used but the corresponding ignore-file isn't present (e.g. `@AIIgnore` without `.cursorignore`)
- `ServiceRegistry` — maps logical service keys to file paths and resolves which services are "active" via the file-existence opt-in
- `ElementNaming` — pure helpers for `elementPath`, `elementDisplayName`, `owningElement`. Member signatures are derived structurally from `ExecutableElement` rather than taken from `Element.toString()`, whose format `javax.lang.model` leaves to the implementation: ECJ renders `public int getKeyRotationHours() ` where javac renders `getKeyRotationHours()`, and this string is the element's identity in `.vibetags-locks` and in granular rule filenames. The derivation reproduces javac's rendering exactly, since that is what every committed fixture and every consumer's generated files were produced by; `ElementNamingFormatParityTest` pins it and the `ecj-degradation` CI leg checks the two compilers agree
- `GuardrailContentBuilder` — A highly decoupled, thin coordinator (~150 lines) that builds AI guardrail files by delegating file rendering to specific `PlatformRenderer` implementations. Coordinates the build process, lazily allocates platform StringBuilders, and returns the final service-key → content map. No I/O.
- `GuardrailFileWriter` — atomic, marker-aware file writes, YAML front-matter preservation, legacy (pre-marker) block migration, and orphan cleanup for granular rule files. Since 0.7.1 also owns the cache-fast-path entry to `writeFileIfChanged` and a streaming byte-compare for non-marker files.
- `GranularRulesWriter` — writes per-class `.mdc`/`.md` files for Cursor / Trae / Roo / Windsurf / Continue / Tabnine / Amazon Q / Amazon Kiro / `.ai/rules` and orchestrates orphan cleanup via the file writer
- `WriteCache` — per-output-file content cache backed by a `.vibetags-cache` sidecar at the project root; lets `GuardrailFileWriter` skip the read+compare path on no-change rebuilds. **Detailed in [Design Decision 5](design-decisions.md#5-write-cache-since-071).** _(since 0.7.1)_
- `ModuleSidecar` — per-module rendered-body store (`.vibetags-mod-<moduleId>` files at the VibeTags root) enabling multi-module aggregation: every module persists its own contribution, and each compile merges all sidecars into the shared marker files using `VIBETAGS-MODULE:` sub-markers. Sidecar format v2; v1 files (written by processors that derived module identity from the working directory — issue #278) are pruned on read.
- `ModuleRootResolver` — resolves the compiled module's root directory *and source set* by walking up from a source file of a live round to the nearest `pom.xml`/`build.gradle(.kts)`, returning a `ModuleIdentity`; this is the module identity fed to `ModuleSidecar`. It reaches the source file through the javac Tree API when available and otherwise through `Elements.getFileObjectOf` (Java 18+) — necessary because Gradle wraps the `ProcessingEnvironment` for incremental processing and `Trees.instance` rejects anything but javac's own (issue #331). Falls back to the JVM working directory when neither is available or sources are in-memory.
- `ModuleIdentity` — record of `(module root, source set)`. The source set (`main`, `test`, `integrationTest`, …, read from the `src/<name>/` segment) gives each javac invocation over the same module its own sidecar file, so a `test-compile` round cannot overwrite what `compile` wrote (issue #330).
- `ModuleOutputWriter` — per-module (nested) output: writes a module's own guardrails into that module's own directory (opt-in by file/dir existence there), by re-running the single-module pipeline scoped to the module. It does not merge across *modules*; it does concatenate the sidecar bodies of this module's own source sets, and it spares their granular stems during cleanup. The reactor-root files are unaffected.
- `RoleConfig` — parses a `.vibetags-roles` config (name → globs/FQNs) and routes annotated owners into human-named topic files via `GranularRulesWriter`; first-match wins, unmatched owners keep their per-class file. Glob matching is done against a path reconstructed from the element FQN (separator-independent), so it works under non-javac/in-memory compilation. Null when the file is absent (per-class behavior); its content hash is folded into the build fingerprint. Lives in `processor.model` because the rendering layer reads it through `RenderingContext.roles()`.

**The model** — package `se.deversity.vibetags.processor.model` (the compiler-free seam between the javac-facing half and the rendering half):
- `GuardrailModel` — the immutable snapshot every `PlatformRenderer` reads: one insertion-ordered bucket of `TaggedElement` per annotation type, plus the `@AILocked` source positions and the `anyAnnotationsFound` flag
- `TaggedElement` — one annotated element as plain data: the five precomputed name forms (`path`, `qualifiedName`, `simpleName`, `displayName`, `granularQName`), its `ElementTag` kind, its owning type/package, and the `@AI*` annotation instances themselves. Equality is by path + kind — a value identity, so a granular-rules map can key on it without pinning javac's object graph past the round that produced it
- `ElementTag` — a name-for-name mirror of `javax.lang.model.element.ElementKind`, plus `UNKNOWN` for "the compiler reported no kind". The names are a published contract (the `kind` field in `.vibetags-locks`, and lower-cased granular headings), pinned by `ElementTagMappingTest`
- `GuardrailAnnotations` — the single ordered registry of collected annotation types; adding a guardrail annotation is one line here
- `SourceLocation` — file + 1-based inclusive line range for `.vibetags-locks`; best-effort, absent under non-javac compilers
- `ContentHash` — the 8-hex content hash shared by `BuildFingerprint`, `RoleConfig`, and `MirrorConfig`

Nothing in this package may import `javax.lang.model`, `javax.annotation.processing`, `javax.tools`, or `com.sun.source`, and nothing in it may depend on the processor or its internals. `ArchitectureRulesTest` enforces both directions, along with the matching rule for the content layer. See `docs/LOAD-BEARING.md` for why the seam exists — chiefly that an `Element` is only valid while its round is live, and the parallel write phase runs after the last one closes.

This split keeps each helper around 50–600 lines, well-tested in isolation, and makes the orchestrator's `generateFiles()` method a 50-line read.
