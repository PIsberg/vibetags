# Proposal: Dependency Tree Propagation (Transitive Guardrails)

Status: **implemented** on `feat/transitive-guardrails`, 2026-08-13. Feasibility spike run against
JDK 26 (`javac 26`); results in [Appendix A](#appendix-a-the-classpath-discovery-spike).

This document is kept as the design record: the spike below is why the manifest sits where it does,
and re-deriving it is expensive. Where the implementation departed from this plan, the reason is in
[§7](#7-where-the-implementation-departed-from-this-plan). For how to *use* the feature, read
[USAGE.md](../../USAGE.md#transitive-guardrails-rules-that-travel-with-a-dependency); for how it
works, [PROCESSOR.md](../PROCESSOR.md#transitive-guardrails-dependency-tree-propagation).

## 1. Verdict

The feature is buildable, but **not by the architecture in the original sketch**. Two of its four
load-bearing mechanisms do not work as described, and one of them cannot be made to work at all
through a supported API.

| Component | Verdict | Basis |
| --- | --- | --- |
| Package-level annotations on `package-info.java` | Feasible, small change | Read: no annotation targets `PACKAGE` today (0 of 44); `ElementTag.PACKAGE` already exists in the model |
| Manifest emission into the library JAR | Feasible via `Filer`, but **not at `META-INF/`** | Measured |
| Consumer-side discovery via `ClassLoader.getResources` | **Does not work** | Measured: 0 results under the documented VibeTags setup |
| Consumer-side discovery via `Filer` on `CLASS_PATH` | Works, **only from a valid Java package path** | Measured |
| Enumerating all manifests on the classpath | **Not possible** through any supported API | Measured |
| Used-package filtering | Feasible, and it is also the fix for the enumeration gap | Read: `ArchitectureRule` already reads imports via the Tree API |
| Merge, scoping, precedence, rendering | Feasible, largely existing machinery | Read: `ModuleSidecar` already merges contributions from independently compiled units |

The design below keeps the goal (a library author's constraints reach an agent working in a
consuming project) and replaces the transport.

## 2. What the spike measured

Four findings, all reproduced in Appendix A.

**Finding 1: `META-INF/` is invisible to javac's `CLASS_PATH` location.**
`Filer.getResource(CLASS_PATH, "META-INF", ...)` fails with `FilerException: Illegal name META-INF`
because `META-INF` is not a legal Java package name. Rewriting it as
`getResource(CLASS_PATH, "", "META-INF/vibetags-manifest.json")` gets past the name check and then
fails with `FileNotFoundException`, even with the JAR provably on the compile classpath. The
internal file manager confirms why: `list(CLASS_PATH, "META-INF", ...)` returns 0 entries. javac
skips archive directories that are not valid package names. **The proposed
`META-INF/vibetags-manifest.json` path cannot be read by an annotation processor.**

**Finding 2: the proposed discovery snippet returns nothing.**
`processingEnv.getClass().getClassLoader().getResources(...)` returned `count=0` in every
configuration tested, including the one where the JARs were on the classpath. That classloader is
javac's own, not the compile classpath. `this.getClass().getClassLoader().getResources(...)` also
returned 0 under `-processorpath` (the setup VibeTags documents). It returned 2 only when the
processor was placed on the compile classpath itself with `-proc:full`, which is a configuration
VibeTags does not use and which JDK 23+ requires an explicit flag to enable.

**Finding 3: `Filer.getResource` on `CLASS_PATH` works from a valid package path.**
With the manifest at `vibetags/manifests/com.example.lib-a.json` inside the JAR,
`getResource(CLASS_PATH, "vibetags.manifests", "com.example.lib-a.json")` read the file
successfully **with the processor on `-processorpath` only**, which is the documented VibeTags
consumer setup. Both dependency JARs resolved, and a name present in neither threw
`FileNotFoundException` cleanly. This is standard `javax.annotation.processing` API, no internals.

**Finding 4: enumeration requires javac internals and a JVM flag.**
`JavaFileManager.list(CLASS_PATH, "vibetags.manifests", ...)` does enumerate both JARs correctly,
but reaching the file manager needs `com.sun.tools.javac.processing.JavacProcessingEnvironment`,
which throws `IllegalAccessException` unless the build passes
`--add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED`. Requiring a JVM flag from
every consumer is not acceptable for a library whose selling point is zero configuration.

The consequence of 3 and 4 together is the central design constraint:

> The processor can read a manifest **whose exact name it can already guess**, and cannot list what
> is there. Discovery must therefore be driven by names the processor derives from the consumer's
> own source, not by scanning the classpath.

## 3. Revised architecture

Name the manifests by the package they govern, and derive the lookup keys from the consuming
project's import statements. Enumeration is then never needed, and the "used-package filtering"
listed in the original sketch as a mitigation for context inflation stops being an optimisation and
becomes the discovery mechanism itself.

```
[ Library source ]
   package-info.java carrying @AI... annotations
        |
        v  library build, opt-in via .vibetags-manifest
   Filer(CLASS_OUTPUT) -> vibetags/manifests/<package>.json   ---> packaged into the JAR
                                                                        |
[ Consumer source ]                                                     |
   imports com.company.crypto.api.CryptoManager                         |
        |                                                               |
        v  Tree API (already used by ArchitectureRule)                  |
   candidate packages: com.company.crypto.api, com.company.crypto, ...  |
        |                                                               |
        v  Filer.getResource(CLASS_PATH, "vibetags.manifests", "<pkg>.json")
   TransitiveRule set  -->  ModuleSidecar (~trans~ key)  -->  merged aggregate files
```

Three properties fall out of this that the original design had to solve separately:

- **No enumeration.** Each lookup is a name the processor constructed from an import it saw.
- **No context inflation.** A package the project never imports is never looked up, so its rules
  cannot reach the output. The original design's mitigation is structural here, not a filter
  applied afterwards.
- **No global-rule injection.** A manifest is keyed by the package it governs; a library physically
  cannot publish a rule that lands under another package's key, because that key is the filename it
  would have to occupy in its own JAR.

## 4. Specification

### 4.1 Manifest format

Path inside the JAR: `vibetags/manifests/<governed-package>.json`, one file per annotated package.
`vibetags` and `manifests` are both valid Java identifiers, which Finding 1 makes non-negotiable.

```json
{
  "manifestVersion": 1,
  "origin": "com.company.crypto:crypto-core:2.4.0",
  "package": "com.company.crypto.api",
  "producedBy": "vibetags/1.2.0",
  "rules": [
    {
      "annotation": "AISecure",
      "tier": "SAFETY",
      "members": {
        "level": "critical",
        "note": "Never instantiate javax.crypto.Cipher directly. Use CryptoManagerFactory."
      }
    },
    {
      "annotation": "AIPerformance",
      "tier": "ADVISORY",
      "members": { "note": "Prefer virtual threads for non-blocking key exchange." }
    }
  ]
}
```

Rules on the format:

- `manifestVersion` is an integer. An unknown value is skipped with a `WARNING` naming the origin,
  never a build failure. This mirrors the `ModuleSidecar` compatibility stance.
- `rules` is sorted by `annotation` then by serialised `members`, and object keys are emitted in a
  fixed order. Non-deterministic output would defeat `WriteCache` and produce spurious diffs in
  check mode.
- `members` carries the annotation's own attributes verbatim. **No new vocabulary is invented.**

### 4.2 Severity: reuse the existing safety buckets

The original sketch proposes a new `STRICT` / `ADVISORY` enum on a new `@AIGuardrail` annotation.
Do not add either. VibeTags already has a two-tier vocabulary that the codebase enforces: the
safety buckets (`@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure`) are
exactly the annotations that stay inline when an aggregate collapses to a scoped-rules index, per
`GranularIndexSection.governingGranularKey`.

- `tier: "SAFETY"` is derived, not authored: it is set for the six safety-bucket annotations.
- `tier: "ADVISORY"` is everything else.

A second severity axis would be a twin of the safety-bucket list that nothing keeps in agreement,
and the list is already load-bearing in the index-collapse path.

### 4.3 Package-level annotations

Widen `@Target` to include `ElementType.PACKAGE` for the annotations where a package-wide statement
is meaningful. Proposed initial set, to be settled during design review:

`@AISecure`, `@AIPrivacy`, `@AICore`, `@AIAudit`, `@AIRegulation`, `@AIArchitecture`,
`@AIPublicAPI`, `@AIBannedApi`, `@AIThreadSafe`, `@AIImmutable`, `@AIDeprecated`, `@AIContext`,
`@AIStrictClasspath`.

Widening `@Target` is source-compatible, so this is additive. Element-kind handling already exists:
`ElementTag.PACKAGE` is a declared constant and `AnnotationCollector` maps `ElementKind` generically
via `ElementTag.fromName`, so a `PackageElement` will snapshot without a new code path. What does
need checking is `ElementNaming`, which precomputes five name forms and has never been given a
package.

Note for the docs: javac does not emit `package-info.class` for a package whose only annotations are
`SOURCE`-retention. That is irrelevant here, because the manifest is the carrier and it is written
through the `Filer`, not read from a class file.

### 4.4 Emission (library side)

Opt-in follows the file-presence invariant. The processor must not start writing manifests into
every library that upgrades VibeTags.

- Marker file `.vibetags-manifest` at the library root activates emission.
- Output goes to `StandardLocation.CLASS_OUTPUT` under `vibetags/manifests/`, so the normal
  resource-packaging step puts it in the JAR with no build configuration.
- Emission happens in `processingOver()` alongside the existing writes, from the
  `GuardrailModel` snapshot, so the rendering half stays compiler-free.
- Only elements whose owning package carries a package-level annotation contribute. Type-level and
  method-level annotations do **not** propagate transitively in v1: they would multiply manifest
  volume by the size of the library's API surface for a benefit nobody has measured.

### 4.5 Discovery (consumer side)

Opt-in marker: `.vibetags-transitive` at the consumer root.

Primary path, javac:

1. Collect the distinct imported packages of every compilation unit in the round through
   `Trees`, the same access `ArchitectureRule` already uses.
2. For each, generate candidate keys: the package itself, then each ancestor down to a floor of
   two segments. `com.company.crypto.api` yields `com.company.crypto.api`, `com.company.crypto`,
   `com.company`.
3. Deduplicate across the whole build, then call
   `Filer.getResource(CLASS_PATH, "vibetags.manifests", key + ".json")` once per key.
4. Cache both hits and misses for the run. A miss is an expected outcome, not a diagnostic.

Fallbacks, in order:

- `-Avibetags.manifest.dir=<path>`: a directory of pre-extracted manifests. This is the escape
  hatch for a Maven or Gradle plugin that resolves artifacts itself, and the only path that works
  when the Tree API is unavailable.
- `-Avibetags.manifest.packages=a.b,c.d`: an explicit key list.
- No Trees and no option: emit one `NOTE` saying transitive discovery was skipped and why, then
  carry on. `ArchitectureRule.unavailableReason` is the precedent for how to word it.

Both new options must be added to `@SupportedOptions`, or the processor's own unknown-option
warning will fire on them.

### 4.6 Conflict resolution

Because the key is the governed package, a genuine conflict means two JARs ship a manifest for the
same package, which means a split package. `Filer` returns the first on the classpath. The
processor cannot see the second, so it cannot report the conflict, and the spec should say so
plainly rather than promise detection it cannot deliver.

What it can do: record `origin` in the rendered output so the reader can see which artifact a rule
came from, and let the `-Avibetags.manifest.dir` path (where the build tool did the resolving and
therefore does see duplicates) report them as a `WARNING`.

The original design's "halts compilation" on conflicting `STRICT` rules should be dropped.
`@AIArchitecture` is the only rule in the codebase that reports `ERROR`, and it does so because it
proves the violation from the source in front of it. A dependency-graph conflict is not that.

### 4.7 Precedence and rendering

Transitive rules are project-scoped, not per-module. In a reactor they must not be repeated once
per module.

- Store them in `ModuleSidecar` under a new reserved key prefix `~trans~`. The reserved `~` prefix
  is already ignored by older readers, so this does not bump `FORMAT_VERSION`.
- Deduplicate at merge time by `origin` plus `package`.
- Render into the aggregate files as two sections, after all application-level content:
  `## Dependency Constraints (safety)` for `SAFETY`, `## Dependency Context (advisory)` for
  `ADVISORY`. Application content stays first, which is the precedence statement: the app's own
  rules are what the agent reads first, and a library cannot displace them.
- Any renderer emitting these sections into a marker-free file must declare `wholeFileMerge()`, and
  any YAML renderer must declare `mergeShape()`. `MultiModuleWholeFileMergeTest` and
  `YamlMergeShapeContractTest` will fail the build otherwise, which is the intended outcome.

### 4.8 Fingerprint

`BuildFingerprint` must incorporate the resolved transitive rule set. Without it, upgrading a
dependency changes the correct output while the element set is unchanged, the fingerprint matches,
and the short-circuit skips regeneration. This is the single most likely way to ship this feature
broken and green, because the failure is silent and only appears on a dependency bump.

### 4.9 Volume control

Discovery-by-import bounds the volume structurally, but a project importing 400 packages from
instrumented libraries can still bloat the output. Add:

- `-Avibetags.manifest.max=<n>`, defaulting to a value chosen from a measurement, capping rendered
  transitive rules. `SAFETY` is never dropped; `ADVISORY` is dropped first.
- When the cap truncates, log what was dropped and emit a `NOTE`. A silent cap reads as full
  coverage. This is the same rule the load-test skill applies to sampling.

## 5. Implementation plan

Each phase ships with tests in the existing suite and is independently reviewable.

**Phase 0: prove the transport end to end.** A single test that compiles a fixture library with a
package-level annotation, jars it, then compiles a fixture consumer against it with the processor on
`-processorpath` only, and asserts a rule from the library appears in the consumer's `CLAUDE.md`.
Write this first and watch it fail. Every later phase is refactoring behind a test that already
means something. `ProcessorTestHarness` and the existing e2e tests are the model.

**Phase 1: package targets.** Widen `@Target` on the chosen annotations. Verify `ElementNaming` and
`AnnotationCollector` handle `PackageElement`, and that `ArchitectureRulesTest` still passes.
Tests: a package-annotated fixture reaches `GuardrailModel` with `ElementTag.PACKAGE`.

**Phase 2: manifest emission.** `TransitiveManifestWriter` in `processor/internal/`, serialising
from the model snapshot. Marker-file opt-in. Tests: byte-stable output across two runs (determinism
is a correctness requirement, not a nicety); absent marker writes nothing; a JAR built from the
fixture actually contains the entry at the expected path.

**Phase 3: discovery.** `TransitiveManifestReader`, import collection, candidate generation, the
`Filer` lookups, and both fallback options. Tests: a manifest under `META-INF/` is **not** found
(pin Finding 1 so nobody helpfully moves the path back); an unimported package's manifest is not
read; missing Trees degrades to a `NOTE`.

**Phase 4: merge and render.** Sidecar `~trans~` key, dedup, the two output sections, renderer
declarations. Tests: a three-module reactor renders each transitive rule exactly once.

**Phase 5: fingerprint and caps.** Test: changing only a dependency's manifest content regenerates
the output. Break the fix deliberately once and confirm the test goes red, because this is the
phase whose bug is invisible.

**Phase 6: docs and the real consumer.** `USAGE.md`, `docs/PROCESSOR.md`, `docs/ARCHITECTURE.md`,
`docs/LOAD-BEARING.md`, the `README.md` facts pinned by `ProjectFactsConsistencyTest`, and the
`add-annotation` skill if the `@Target` widening changes its checklist. Then run
`consumer-regression-suite` against the five downstream repos.

## 6. Risks

- **Gradle incremental correctness.** The processor is declared `aggregating` in
  `META-INF/gradle/incremental.annotation.processors`. Reading `CLASS_PATH` resources through the
  `Filer` is not something Gradle tracks as a processor input. Verify against a real Gradle build
  before merging, not from documentation.
- **kapt, ECJ, Groovy stubs.** kapt has no Tree API, so Kotlin consumers land on the
  `-Avibetags.manifest.dir` fallback. The existing JVM-language matrix in `USAGE.md` needs a row
  per language for this feature; do not assume it inherits.
- **JPMS.** The spike used the classpath only. Under `--module-path`, `CLASS_PATH` is not where the
  dependency lives, and `getResource` will not find it. Untested. Either add `MODULE_PATH` lookups
  or document the limitation.
- **Lookup cost.** One `Filer.getResource` per candidate key per compilation. Unmeasured. The
  `load-tests` harness should get a case before this ships.
- **Split packages.** First on the classpath wins, silently. Documented in 4.6, not solved.
- **Trust.** A dependency now injects text into instructions an agent follows. `SAFETY` rules from
  a transitive dependency are, in effect, prompt content authored by a third party. The rendered
  output must attribute every rule to its `origin` coordinate so a reader can see where it came
  from, and the `.vibetags-transitive` opt-in must stay opt-in.

## 7. Where the implementation departed from this plan

Five changes, all of them things the build taught rather than things the plan got wrong on paper.

**A fourth finding, not in the spike: a processor with no matching annotations is never invoked.**
This plan assumed the consumer's compilation would call `process()`. It does not — javac skips a
processor entirely when nothing in the round matches its supported types, and a project that
inherits *all* of its guardrails annotates nothing itself. The feature appeared to do nothing, and
the only clue was javac's unrelated "options were not recognized by any processor" warning.
`getSupportedAnnotationTypes()` now returns `"*"`, but only for projects carrying the
`.vibetags-transitive` marker: claiming it unconditionally would run VibeTags on every compilation
of every consumer, including those with opt-in files and no annotations, which today produce nothing
and would start producing empty scaffolding.

**The `~trans~` sidecar key was dropped.** §4.7 planned to store inherited rules in the module
sidecar and deduplicate at merge. Unnecessary: the appendix is rendered into each module's own body,
which the existing reactor merge already carries into that module's region. That is also the more
correct answer — a module inherits what *its* sources import, which is per module rather than per
repository — and it avoids editing `generateFiles()`, whose step order is locked.

**The volume cap moved upstream.** §4.9 put it in the renderer. Applying it there would have left
the fingerprint tracking rules that were never written, and put the same limit in two places. The
cap now applies where rules enter the model, so what is fingerprinted is what is rendered, and the
build reports the drop as a `NOTE` rather than a line in the file.

**`anyAnnotationsFound()` had to change meaning.** Not anticipated at all. It gates `hasNewRules`,
which decides whether an *existing* generated file may be rewritten; counting only local annotations
meant a project whose guardrails all come from dependencies wrote its files exactly once and then
refused every update. Inherited rules now count.

**Phase 5's test could not be written as specified.** The plan called for an end-to-end test proving
a dependency upgrade defeats the fingerprint short-circuit. That test passes whether or not the
fingerprint contribution exists, because the same path rewrites the module sidecar on every run and
the sidecar's mtime feeds the other half of the same short-circuit. Deliberately breaking the fix
confirmed it: the end-to-end test stayed green. The assertion moved to `TransitiveFingerprintTest`,
where the same sabotage produces six failures.

## Appendix A: the classpath discovery spike

Reproduction: two JARs each containing a manifest at both `META-INF/vibetags-manifest.json` and
`vibetags/manifests/<coords>.json`; a probe processor trying nine strategies; three javac
invocations. Run on `javac 26`.

Case A is the documented VibeTags setup: `-cp libA.jar;libB.jar -processorpath out`.

```
CASE A: processor on -processorpath ONLY
  S1 processingEnv.getClass().getClassLoader().getResources()   count=0
  S2 this.getClass().getClassLoader().getResources()            count=0
  S3 Filer.getResource(CLASS_PATH, "", META-INF/...)            FileNotFoundException
  S5 internal Context -> JavaFileManager.list()                 IllegalAccessException
  S7 Filer.getResource(CLASS_PATH, "vibetags", "manifest.json")
       -> {"origin":"com.example:lib-a:1.0","via":"valid-package"}
  S8 Filer.getResource(CLASS_PATH, "vibetags.manifests", "<coords>.json")
       com.example.lib-a.json -> {"origin":"com.example:lib-a:1.0","via":"per-artifact"}
       com.example.lib-b.json -> {"origin":"com.example:lib-b:2.0","via":"per-artifact"}
       nope.json              -> FileNotFoundException
  S9 internal list() of a valid package                          IllegalAccessException

CASE B: processor on the compile classpath, -proc:full
  S2 count=2   (both manifests found; configuration VibeTags does not use)
  S3 FileNotFoundException      S7/S8 as Case A

CASE C: Case A plus --add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
  S5 list(CLASS_PATH, "META-INF", ...)            count=0
  S9 list(CLASS_PATH, "vibetags.manifests", ...)  count=2
       jar:file:///.../libA.jar!/vibetags/manifests/com.example.lib-a.json
       jar:file:///.../libB.jar!/vibetags/manifests/com.example.lib-b.json
```

S5 in Case C is the proof for Finding 1: with internal access granted and the JARs on the
classpath, javac's own file manager still lists zero entries under `META-INF`, while the same call
against `vibetags.manifests` lists both.
