# VibeTags in Practice

*How VibeTags is actually used across the projects in `../`, what each annotation is reached for, and what impact it has.*

Survey date: 2026-07-16. Sources: `async-test-lib`, `blindbean`, `codekarta`, `skill3`, and VibeTags itself.

---

## 1. The corpus

Five codebases use VibeTags. All are authored by the same developer, so this is a dogfooding corpus rather than a market sample — but the projects are genuinely different from each other in domain, and the annotation choices diverge sharply along those lines, which is the most interesting thing the survey found.

| Project | Domain | Files | VibeTags version | Uses | Distinct | Density |
|---|---|---|---|---|---|---|
| `async-test-lib` | JUnit 5 concurrency stress-testing (121 detectors) | 624 | 1.0.0-RC2 | 194 | 21 | 0.31/file |
| `blindbean` | Homomorphic encryption over SEAL/Panama FFM | 77 | 1.0.0-RC2 (annotations) / 0.9.7 (processor) | 84 | 30 | 1.09/file |
| `codekarta` | Java architecture diagram generator | 54 | 0.9.5 | 51 | 14 | 0.94/file |
| `skill3` | Local-first AI skill relearner | 95 | 1.0.0-RC1 | 16 | 7 | 0.17/file |
| `vibetags` (self) | The processor itself | ~60 core | self | 16 | 8 | — |

Two areas inside the VibeTags repo are **not** real usage and are excluded from all counts below:

- **`example/`** — 82 uses across all 39 annotation types, 26 of them exactly once. This is a coverage matrix, not a risk model: one annotation per formatter code path, wired into the `verify-generated-files` CI action that greps generated output for expected strings. Its heavy `@AIDraft` use (15 real sites) exists because demonstrating "AI, implement this" requires stub bodies.
- **`load-tests/`** — `SyntheticClassGenerator` emits annotations *as strings at runtime* on a modulo schedule (every class `@AIContext`, every 2nd `@AILocked`, every 5th `@AIPrivacy`…), sweeping N from 10 to 10,000 classes. Annotations as synthetic load, not guardrails.

### The headline finding

**Each project uses a different, largely disjoint quarter of the vocabulary, and the choice is legible from the domain in every case.**

- `codekarta` — a tool whose *product* is clean layering — spends 45% of its annotations on `@AIArchitecture` + `@AIContext`.
- `skill3` — a local-first tool holding four API tokens — spends 56% on `@AISecure` + `@AIPrivacy`, and uses neither `@AIArchitecture` nor `@AILocked` at all.
- `async-test-lib` — a published Maven Central library — is the only heavy user of `@AIContract` + `@AIPublicAPI`.
- `blindbean` — an FHE library — is the only user of `@AISecureLogging`, and pairs `@AIPrivacy` + `@AISchemaSafe` on all nine of its entities.

`codekarta` and `skill3` share exactly three annotations (`@AICore`, `@AIContext`, `@AIImmutable`). That's the whole overlap between a 51-use project and a 16-use one.

---

## 2. Wiring

No two projects wire VibeTags the same way, which is itself a finding.

**`async-test-lib`** — single `vibetags-processor` artifact at `provided` scope, chained *after* Error Prone in `annotationProcessorPaths` (`pom.xml:220-231`). Notably, a separate `default-testCompile` execution overrides `<compilerArgs combine.self="override"/>` to strip Error Prone from test sources but **deliberately re-adds vibetags-processor alone** — so VibeTags runs on test compilation where Error Prone does not. Zero `-A` options; root auto-detected. Opted into Claude only.

**`blindbean`** — the most deliberate setup, and the most broken. A single root-level `provided` dependency on `vibetags-annotations` inherited by all 9 reactor modules, matched by `requires static se.deversity.vibetags.annotations;` in every `module-info.java`. The pom comment explains the strategy:

> The guardrail GENERATOR (vibetags-processor) is deliberately NOT run in the reactor — per-module it would fragment the generated GEMINI.md; the committed copy stands.

The processor runs in exactly one place — the non-reactor `blindbean-example` — pinned at **0.9.7 while the annotations are 1.0.0-RC2**, bypassing the BOM with a hardcoded version (`blindbean-example/pom.xml:78`). That version skew is a straightforward bug.

**`codekarta`** — pre-1.0 style: no `annotationProcessorPaths` at all, the processor rides the compile classpath via `provided` scope and javac's default discovery. Dual Maven + Gradle. No `-A` options.

**`skill3`** — the only project using `-A` options (`build.gradle:64-70`):

```groovy
options.compilerArgs += ["-Avibetags.root=${rootDir}".toString(), '-Avibetags.project=Skill3']
```

with a comment noting *"Gradle runs javac in a worker whose CWD differs"* — a real papercut the option exists to solve. It also needs an Error Prone workaround (`disable('UnsafeFinalization')`, `build.gradle:73-79`).

**`vibetags` itself** — builds with `-proc:none` by default and self-annotates only under an opt-in profile:

```
mvn compile -Pself-annotate   # requires `mvn install` once first to bootstrap
```

`vibetags/pom.xml:366-392` uses `<configuration combine.self="override">` to discard `-proc:none`, and passes `-Avibetags.root=${project.basedir}/..` so output lands at the repo root.

### Platform opt-in

| Project | Opted in |
|---|---|
| `async-test-lib` | `CLAUDE.md` (932 lines, 100% generated), `.claudeignore` |
| `blindbean` | `CLAUDE.md` (hybrid: 1-181 human, 182-586 generated), `GEMINI.md`, `.claudeignore`, `.aiexclude` |
| `codekarta` | `CLAUDE.md` (hybrid: 1-75 human, 76-114 generated) |
| `skill3` | `CLAUDE.md` (100% generated), `llms.txt`, `llms-full.txt` |
| `vibetags` | `CLAUDE.md`, `GEMINI.md`, `.aiexclude` |

Nobody has opted into Cursor, Copilot, Windsurf, Codex, or any of the other ~40 supported platforms. **`.vibetags-locks` is enabled in zero projects.** The generated files are git-tracked everywhere; the working files (`.vibetags-cache`, `.vibetags-mod-*`, `vibetags.log`) are gitignored everywhere.

`blindbean`'s `AGENTS.md` is hand-written and deliberately *not* generated — it's a pointer that exercises the sole-file fallback rule from the other side:

> This file is intentionally a pointer. The authoritative, maintained guidance lives in the two platform files below — read the one for your platform, and do not duplicate their content here (it would drift).

---

## 3. Per-annotation analysis

Ordered by real-world usage. Counts exclude `example/` and `load-tests/`.

### `@AITestDriven` — 108 uses (async-test-lib 103, blindbean 4, codekarta 1)

The most-used annotation in the corpus, and 95% of that is one project applying it mechanically. All 103 `async-test-lib` uses are byte-identical modulo the class name:

```java
// diagnostics/AtomicNonAtomicUpdateDetector.java:31-35
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/AtomicNonAtomicUpdateDetectorTest.java"
)
```

**Why:** every detector has a strict 1:1 paired test file. The annotation formalizes a naming convention (`XDetector.java` → `XDetectorTest.java`) as machine-readable fact — "there is a test, it's exactly here, don't write a new one." `blindbean` uses it differently: only 4 sites, all `coverageGoal = 90`, applied to the crypto/lifecycle core rather than blanket-applied.

**Impact:** the honest read is that this is the corpus's *lowest*-information annotation per use. 103 identical instances tell an AI one fact it could derive from the file naming. Strip this layer and `async-test-lib` becomes a small, surgical 91-use deployment concentrated in ~20 files. Its value is real but thin, and it distorts the usage statistics badly.

### `@AIThreadSafe` — 33 (async-test-lib 27, blindbean 5, skill3 1)

Strategy distribution in `async-test-lib`: `OTHER` 24, `SYNCHRONIZED` 2, `THREAD_LOCAL` 1. **The skew to `OTHER` is the finding.** The standard taxonomy doesn't fit lock-free designs, so the author falls back to `OTHER` and puts the real information in prose:

```java
// diagnostics/HighContentionAtomicDetector.java:61
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in
  ConcurrentHashMap with get-then-computeIfAbsent hot path; counters are LongAdder;
  thread-id/name sets are ConcurrentHashMap.newKeySet().")
```

That note is precise enough to be actionable — it names the exact `get-then-computeIfAbsent` idiom so an AI doesn't "simplify" it to plain `computeIfAbsent` and add allocation. **This suggests a missing `Strategy.LOCK_FREE` constant**, or that the enum should be open.

`blindbean`'s five all guard SEAL's non-thread-safe C++ context (`FheContext.java:32`, `SYNCHRONIZED`, *"All native FFM operations are guarded by nativeLock"*). `skill3`'s single use is the longest annotation in that project and pre-emptively answers a question an AI would ask:

> The opt-in `sequential` mode only removes concurrency (fetches run on the caller thread); it cannot weaken the invariant — serial execution is strictly safer than the parallel default it replaces.

**Why it matters existentially for `async-test-lib`:** the detectors are concurrent code observing concurrent code. A detector that races corrupts the evidence it collects, producing false positives in a tool whose entire value is trustworthy verdicts.

### `@AIPublicAPI` — 24 (async-test-lib 17, blindbean 6, codekarta 1)

Only used by projects that publish artifacts. In `async-test-lib` it's an attribute-less marker, always paired with apiguardian's `@API(status = Status.STABLE)` and stacked under `@AIContract` — the coarse "this is the boundary" flag, with `@AIContract` next to it carrying the *why*.

`blindbean`'s two reasoned uses both turn on the word **silently**:

```java
// blindbean-junit/.../BlindBeanTest.java:38
@AIPublicAPI(reason = "Attribute names (scheme, polyModulusDegree, ckksScale) and their
  defaults are written into consumer test classes; renaming or removing one silently
  changes which context those suites boot")
```

That's the real insight: annotation attributes get copied into downstream source, so a rename compiles fine locally and breaks strangers.

### `@AIContext` — 20 (codekarta 11, vibetags 3, blindbean 2, skill3 2, async-test-lib 2)

The most broadly-used annotation and, judged by content, the highest-value one. Its `avoids` field is consistently used to name **one specific tempting refactor**, not to give generic advice.

`codekarta`'s 11 are the best examples in the corpus. This one documents a coupling that `@AIArchitecture` makes structurally invisible:

```java
// code-karta-input/.../parser/CallSequenceParser.java:24-27
focus = "Produces integer-labelled CALLS edges in textual call order... These integer
  labels are the contract read by SequenceDiagramRenderer to order messages."
avoids = "Changing the CALLS edge label format — SequenceDiagramRenderer.isInteractionGraph()
  and orderMessages() depend on labels parsing as integers."
```

Input and render *cannot reference each other* (enforced by `@AIArchitecture`), yet they share an implicit string-format contract through the IR. `@AIContext` is the only place that coupling is written down anywhere.

Others pre-empt the exact optimization an AI would attempt — `ExceptionFlowParser.java:52`: *"avoids: Merging both passes into one — Pass 2 needs the complete caller map from Pass 1."* And `SimpleLayoutEngine.java:20` cross-references another annotation: *"those constants are @AILocked."*

`async-test-lib`'s two are numbered procedures for adding a detector — the most common change in that codebase — and both `avoids` name the same failure mode, **silent partial success**:

```java
// DetectorRegistry.java:148-151
focus = "Each new detector requires exactly three steps in this class: (1) a final field
  declaration, (2) conditional construction in the constructor keyed on the config flag,
  (3) an analyzeAll() call in the correct phase block. All three steps must be added together."
avoids = "partial patterns — a field without construction or analysis silently skips detection"
```

Miss step 3 and the detector compiles, registers, and never fires. No test fails. The tool just quietly stops catching a class of bug.

`skill3`'s two both defend generality against the AI's instinct to special-case — `QueryPlanner.java:22` names the exact string an assistant would append: *"avoids: hardcoding per-topic search terms or a fixed query suffix like \" documentation\""*.

**Impact:** this is the annotation that most consistently encodes knowledge genuinely unrecoverable from the source. Every other annotation says *what kind of thing this is*; `@AIContext` says *what you are about to do wrong*.

### `@AIContract` — 18 (async-test-lib 8, vibetags 4, blindbean 3, codekarta 3)

Used for two distinct kinds of frozen signature, and the reasons distinguish them cleanly:

**Outbound** — signatures consumers bind to. `GuardrailFileWriter.writeFileIfChanged` (`vibetags`): *"Public API since v0.1; tests and the processor both bind to the (String path, String content, boolean hasNewRules) signature."*

**Inbound** — signatures a third party mandates:

```java
// async-test-lib extension/AsyncTestExtension.java:20
@AIContract(reason = "JUnit 5 TestTemplateInvocationContextProvider SPI. The two overridden
  methods (supportsTestTemplate, provideTestTemplateInvocationContexts) must preserve their
  exact signatures as mandated by JUnit.")
```

That second category is the sharpest use in the corpus. An AI has no way to infer from the code alone that renaming `supportsTestTemplate` silently unregisters the extension rather than causing a compile error. Same shape in `vibetags`' own `process()`: *"must return false so peer annotation processors can claim the same annotations."*

`codekarta` uses it for behavioral rather than structural contracts — `InputParser.java:8`: *"parse(Path) must never throw — all parsers wrap JavaParser calls in try/catch... ensures the pipeline always produces some output."*

### `@AICore` — 17 (async-test-lib 4, codekarta 4, vibetags 4, blindbean 3, skill3 2)

The only annotation used by all five projects. `sensitivity` is free text and used as a genuine ladder — `codekarta` grades its IR classes Critical → High → High → Medium tracking blast radius exactly.

The single most valuable annotation site in the corpus:

```java
// async-test-lib extension/AsyncTestInvocationInterceptor.java:16-19
@AICore(sensitivity = "Critical", note = "invocation.skip() is intentional — ConcurrencyRunner
  owns the full N×M execution and must never call invocation.proceed(). Restoring proceed()
  would run the test body once outside the CyclicBarrier, bypassing all detectors.")
```

`invocation.skip()` looks *exactly* like a bug — an interceptor that never proceeds. It's the highest-probability target for an AI "fix" in that entire codebase, and restoring `proceed()` compiles, passes a naive review, and silently defeats the whole library.

`skill3`'s two are the inverse case — the classes that **don't trust the LLM**, annotated so an AI doesn't dissolve its own supervisor (`SkillMdPostProcessor.java:17`: *"model output is never trusted"*).

### `@AISecure` — 18 (skill3 7, blindbean 7, async-test-lib 4)

`aspect` is free text, and each project builds a different taxonomy with it.

`blindbean` uses it as a **map** of the crypto surface — `key-generation`, `key-management`, `key-serialization`, `key-deserialization`, `key-rotation`, `fhe-encryption`, `paillier-encryption`. An agent editing `loadKeys` learns it's touching deserialization (an RCE-adjacent sink), not just file I/O.

`skill3` uses it as a **ratchet**. Three of seven `aspect` strings contain "must not be weakened":

```java
// pipeline/HttpPageFetcher.java:35
@AISecure(aspect = "outbound page fetch egress for partly-untrusted URLs; SSRF guard must not be weakened")
```

It even annotates a 20-line static factory (`DiscoveryProvider.java:14`) purely because a token passes through it — taint-propagation reasoning.

`async-test-lib`'s four name the *security property at risk* rather than the mechanism: `"cryptography (RNG quality)"`, `"cryptography (hash integrity / MAC / signature state)"`.

### `@AISchemaSafe` — 15 (blindbean 11, codekarta 4)

**Always bare — zero attributes at all 15 sites, in both projects.** The author evidently felt the annotation name carried the meaning alone.

In `blindbean` it's perfectly correlated with `@AIPrivacy` on all 9 entities (always adjacent, always `@AIPrivacy` first — a house template), plus `Ciphertext`, `KeyBundle`, `PaillierKeyPair`. Every one is a serialization boundary whose layout is load-bearing: a field reorder an AI would consider harmless tidying **silently breaks every key file already on disk**. Belt-and-braces evidence sits right underneath — `KeyBundle.java:22` also carries `@AILocked` on `serialVersionUID`.

`codekarta` stacks it with `@AIStrictTypes` on the four Jackson-serialized IR classes.

### `@AIArchitecture` — 13 (codekarta 12, blindbean 1)

Effectively a single-project annotation, and `codekarta` uses it to encode its entire layering as compiler-visible metadata. Every use has `belongsTo` == its Gradle module name and `cannotReference` listing every sibling:

```java
// code-karta-core/.../model/Graph.java:12
@AIArchitecture(belongsTo = "core", cannotReference = {"input", "layout", "render", "cli"})
// the 6 input-tier uses:
@AIArchitecture(belongsTo = "input", cannotReference = {"layout", "render", "cli"})  // note: "core" absent
```

The union describes a strict fan-in star: `core ← {input, layout, render} ← cli`. Sibling tiers are mutually invisible and communicate *only* through the `Graph` IR. **`cli` is deliberately unannotated** — it's the composition root that legally wires all three tiers.

**Why:** the tool that maps architecture must have a clean architecture. The annotation makes the invariant survive AI edits — an assistant asked to "just import SvgRenderer into the parser to fix the labels" hits an explicit prohibition rather than a convention buried in a README.

`blindbean`'s single use is a targeted layering guard: `BlindMath.java:13`, `belongsTo = "math-layer", cannotReference = {"...fhe.FheNativeBridge"}`.

**Notable contrast:** neither `async-test-lib` nor `skill3` uses it — both enforce layering with ArchUnit tests instead, which is a real mechanism rather than an advisory one.

### `@AIPrivacy` — 13 (blindbean 11, skill3 2)

The reason strings converge on one fear, stated repeatedly: **"never log the decrypted value"** and **"never put a real balance in a fixture."**

```java
// blindbean-runtime/.../context/KeyBundle.java:18
@AIPrivacy(reason = "Contains serialized Paillier private key material and SEAL key bytes —
  never log, transmit in plaintext, or expose field values in suggestions or test fixtures")
```

This defends *the invariant the product sells*, not generic PII hygiene. FHE's whole value proposition is that plaintext never exists outside a narrow decrypt boundary, and the easiest way for an AI to destroy that is a helpful `log.debug(wallet.getBalance())`.

`skill3`'s two are field-level on API keys, identical modulo the credential name. The `"or fixtures"` clause is the tell — it's aimed at AI *writing tests*, the classic path by which a real key gets hardcoded into a mock.

### `@AIIdempotent` — 12 (async-test-lib 6, blindbean 6)

Both projects use it exclusively on cleanup paths, and every reason gives a **mechanism-level proof**:

```java
// blindbean FheCiphertextNative.java:181 (close())
reason = "Guarded by freed flag; calling close() on an already-freed handle is a no-op"
```

For `blindbean` this is a **double-free guard** — an AI "simplifying away" a redundant-looking `freed` flag crashes the JVM, it doesn't fail a test. For `async-test-lib`, cleanup runs in `finally` across N×M invocations; a non-idempotent `close()` throws during unwind and masks the real failure.

Two reasons stand out for being *falsifiable*: `AsyncTestListenerRegistry.java:262` names the exact test that proves the claim (`registrationClose_isIdempotent`), and `AsyncTestContext.java:548` cites CLAUDE.md as a normative source — which is circular in a nice way, since CLAUDE.md is generated from these annotations.

### `@AIPerformance` — 11 (blindbean 5, async-test-lib 3, vibetags 2, codekarta 1)

Two of `vibetags`' own uses encode history rather than policy:

```java
// internal/WriteCache.java:161
@AIPerformance(constraint = "O(1): one stat(2) syscall plus one 8-char string compare;
  must not allocate byte[] — the prior CRC32C implementation did and was removed for this reason")
```

That records a *reverted decision* — it stops an AI from re-proposing something already tried and rejected. There's no other place in a codebase where that fact naturally lives.

`async-test-lib`'s use at `LegacyDetectorAdapter.java:39` is the most interesting shape: an **anti-**performance annotation that pre-emptively answers a critique — *"only invoked once per round... If profiling shows reflection overhead, cache the Method handles"* — heading off an unnecessary optimization rather than demanding one.

The underlying concern in `async-test-lib` is observer-effect management: allocating on the instrumented path perturbs timing and changes which races manifest. The measurement destroys the phenomenon.

### `@AIImmutable` — 10 (async-test-lib 5, blindbean 2, codekarta 1, skill3 1, vibetags 1)

Notes usefully separate **language-guaranteed** from **effective** immutability — `Violation.java:34`: *"Java record — fields are final by language. Collection fields are deep-copied to immutable views in the canonical constructor."* That flags the non-obvious part: records are only shallowly immutable.

### `@AILocked` — 8 (blindbean 3, codekarta 3, async-test-lib 1, vibetags 1)

**Strikingly rare** — the flagship annotation is the 12th most used, and every project places it with precision on exactly the constant-holders and formats whose edit has invisible ripple.

`codekarta`'s three all guard shared vocabulary that `@AIArchitecture` prevents tiers from negotiating directly (`EdgeType`, `NodeType`, `NodeDimensions` — all in `core/model/`). `blindbean`'s guard two `serialVersionUID` fields and the FFM bridge.

`async-test-lib`'s single lock is the most carefully constructed thing in the corpus:

```java
// DetectorType.java:12
@AILocked(reason = "Each enum constant requires synchronized changes in five places:
  (1) @AsyncTest attribute, (2) AsyncTestConfig field, (3) AsyncTestConfig.Builder default,
  (4) both branches of AsyncTestConfig.build()..., and (5) DetectorRegistry constructor.
  Adding a value here in isolation breaks the system.")
```

It's the tripwire at the head of a six-place ripple that `@AICore` on `AsyncTestConfig:20` enumerates and `@AIContext` on `DetectorRegistry:148` turns into a procedure. **Three annotations, three files, one invariant** — deliberate redundancy, so whichever file the AI opens first, it learns about the other five.

**But:** no project has `.vibetags-locks` enabled and none runs the locked-files action, so every one of these 8 locks is advisory prose in a markdown file. See §5.

### `@AIIgnore` — 6 (blindbean 5, async-test-lib 1)

Both uses are **noise suppression**, and the phrasing matters:

```java
// async-test-lib NoopAsyncTestListener.java:29
reason = "Trivial no-op implementation of AsyncTestListener. All methods are intentionally
  empty — no logic to review or change here."
```

"Intentionally empty" is the point — empty method bodies read as unfinished work, so this pre-empts an AI helpfully filling them in.

This annotation drove a nice observable loop in `async-test-lib`'s log: the first run (v0.8.0, 2026-05-07 23:03) warned *"@AIIgnore used but .claudeignore is missing"*; the user created the file; the next run at 23:04 showed `Active services (2)`. **The orphan warning worked exactly as designed** — one of the few places in this survey where a VibeTags feature demonstrably changed behavior.

### `@AIAudit` — 6 (blindbean 3, async-test-lib 2, codekarta 1)

`checkFor` lists are concrete and domain-shaped rather than boilerplate. `blindbean`'s are native-flavored: `{"Resource Leaks", "Memory Segment lifecycle", "Double-free"}`. `codekarta`'s single use is on the only class that touches the filesystem: `{"Path traversal", "Unauthorized file write"}`.

### `@AISecureLogging` — 4 (blindbean only)

The tightest annotation work in the corpus. All four are `MaskingPolicy.OMIT` — **never `MASK`** — on `KeyBundle`'s `paillierKeyPair` / `nativeFhePayload` and `PaillierKeyPair`'s `lambda` / `mu`.

`OMIT` is the correct and deliberate choice: λ and μ are Paillier *private* key components, and a partial mask of a `BigInteger` still leaks exploitable bits. These four fields are the field-level enforcement of the class-level `@AIPrivacy` promise — the class says "never log this," the fields name exactly which members must never reach a `toString()`.

### `@AIFeatureFlag` — 5 (async-test-lib 3, blindbean 2)

Both projects independently discovered the same pattern: **declare the flag at both ends**. `async-test-lib` puts `async-test.benchmarking.enabled` on the config field *and* the consuming class; `blindbean` puts `blindbean.apt.async` on the runtime *and* the processor that emits it. Discoverable from either end without a call-graph traversal.

Both projects' other flag is a test-only bypass of something important (`license.mock.mode` gating a paid license check) — exactly the kind of flag you don't want an AI flipping to `true` "to make the tests pass."

### `@AIInputSanitized` — 4 (async-test-lib 2, blindbean 2)

The only parameter-level annotations in either codebase. `async-test-lib` marks `XSS` on the report strings that land in CI dashboards (a stored-XSS path — violation data originates from user code). `blindbean` marks `PATH_TRAVERSAL` on the `filePath` params of `exportKeys`/`loadKeys`.

### `@AIStrictClasspath` — 3 (async-test-lib 1, blindbean 1, codekarta 1)

Used exactly once per project, always at the one place it applies — a good signal the author reaches for annotations on evidence rather than sprinkling them. The sharpest security annotation in the corpus:

```java
// async-test-lib benchmark/BenchmarkComparator.java:194
@AIStrictClasspath(reason = "Java native deserialization sink. The BASELINE_FILTER allow-list
  (ending in !*) must resolve every class in the stream and reject all others, preventing
  arbitrary class loading (CWE-502 RCE). Never widen the filter or remove setObjectInputFilter.")
```

It names the CWE, explains the easy-to-get-wrong `!*` terminator semantics, and issues two explicit prohibitions. An AI adding a field to `BenchmarkResult` would hit a filter rejection and be strongly tempted to "fix" it by loosening the filter.

### `@AIObservability` — 2 (async-test-lib 1, blindbean 1)

Both uses protect strings that are **an API surface disguised as output**:

```java
// async-test-lib benchmark/BenchmarkRecorder.java:24-28
logs = {"[BENCHMARK] Baseline created", "[BENCHMARK] Baseline updated", "[BENCHMARK] STABLE",
        "[BENCHMARK] REGRESSION", "[BENCHMARK] IMPROVEMENT"}
```

These five prefixes are parsed downstream by the baseline regression check. "Improving" the wording of `[BENCHMARK] REGRESSION` breaks CI silently. `blindbean`'s guards `fhe.noise_budget` — noise exhaustion silently corrupts FHE results.

### `@AICallersOnly` — 2 (async-test-lib 1, blindbean 1)

A genuinely precise placement in `async-test-lib`:

```java
// AsyncTestContext.java:542
@AICallersOnly({"se.deversity.asynctest.runner.ConcurrencyRunner"})
public static void install(AsyncTestContext ctx) { CURRENT.set(ctx); }
```

`install()` *must* be public (it's called cross-package), but only `ConcurrencyRunner` may legitimately call it — because only `ConcurrencyRunner` has the matching `uninstall()` in its outermost `finally`. **Java's visibility modifiers cannot express this.** Note the deliberate asymmetry with its neighbor: `install()` gets `@AICallersOnly` (dangerous, restricted), `uninstall()` at `:548` gets `@AIIdempotent` (safe, call freely). Two annotations document the whole symmetry protocol in four lines.

### Long tail

| Annotation | Uses | Where |
|---|---|---|
| `@AIStrictTypes` | 4 | codekarta 3 (IR wire format), blindbean 1 |
| `@AIParallelTests` | 4 | codekarta 3 (its only test-source annotations), blindbean 1 |
| `@AIStrictExceptions` | 3 | blindbean — *"a genuine SEAL failure must not be disguised as a missing-library problem"* |
| `@AIMemoryBudget` | 3 | The enforceable restatement of an `@AIPerformance` prose constraint on the same hot path |
| `@AIPure` | 3 | One each; async-test-lib's is stacked on an already-`@AISecure` sanitizer |
| `@AIExtensible` | 2 | async-test-lib, both `STRATEGY_PATTERN`, both directly beneath an `@AIContract` |
| `@AIExplain` | 2 | blindbean, both `ComplexityLevel.HIGH`, on the Paillier math |
| `@AILegacyBridge` | 1 | async-test-lib, attribute-less — package name already says it |
| `@AIDomainModel` | 1 | blindbean `Ciphertext` |
| `@AIInternationalized` | 1 | blindbean's APT processor — `Messager` diagnostics are user-facing |

### Never used in real code — 7 annotations

**`@AIDraft`, `@AIPrototype`, `@AITemporary`, `@AISunset`, `@AIDeprecated`, `@AIRegulation`, `@AISandboxOnly`** appear only in `example/` fixtures. Zero real sites across five projects.

The first five are **lifecycle/maturity markers for code in flux**. All five projects are libraries or tools with a published-artifact discipline (`async-test-lib` uses apiguardian `@API(status=STABLE)`); `blindbean` is greenfield 0.1.0 with no legacy to bridge and nothing yet deprecated. Nothing is provisional, so nothing is marked provisional.

`@AIRegulation` is the interesting absence. `blindbean` is a *privacy-preserving crypto library* — the natural home for GDPR/HIPAA tags — and doesn't use it. The reason is sound: BlindBean is **infrastructure, not an application**. It never sees regulated data; it ships the primitive its consumers use to protect theirs. Its concerns are correctly `@AIPrivacy`/`@AISecure` (protect the mechanism), not `@AIRegulation` (comply with a regime). **`@AIRegulation` needs an application-tier consumer to be validated at all, and this corpus has none.**

`@AISandboxOnly` is arguably a missed fit — `blindbean-example`'s entities are demo fixtures nobody should copy into production, and their `@AIPrivacy` reasons already gesture at it (*"never put a real balance in a fixture"*).

---

## 4. VibeTags on itself

The processor carries **16 real annotation sites across 8 classes**. `AnnotationValidator` (516 lines) and `OrphanWarner` are unannotated despite being in the core list.

The division of labor is clean and worth copying:

- **`@AICore`** marks blast radius — 4 uses, and the only `sensitivity = "critical"` in any project is on `AIGuardrailProcessor` itself.
- **`@AIContext`** names one concrete forbidden refactor each — `AnnotationCollector`: *"avoids: Replacing LinkedHashSet with HashSet — insertion order stability is required for deterministic fingerprints."*
- **`@AIContract`** freezes 4 signatures.
- **`@AIPerformance`** records regressions that already happened once (the CRC32C revert).
- **`@AILocked`** — exactly one, on `generateFiles()`: *"Step order is load-bearing: fingerprint check → sidecar write → sidecar read → merge → file write → cache flush."*

### The round-trip

The `<project_guardrails>` block in the repo's `CLAUDE.md` is generated from these annotations, and the content matches verbatim. Element paths are FQN-resolvable back to source (`...BuildFingerprint.fingerprint(java.lang.String)`), so the loop is fully traceable.

The recursion is load-bearing, not decorative: `ModuleSidecar.mergeFor`'s `@AIContract` reason says the sub-marker constants *"are embedded in generated CLAUDE.md and .cursorrules"* — and that reason text is itself rendered into the generated CLAUDE.md. **The annotation describing the output format is published through that format.**

Caveat: `self-annotate` is not run in CI (it's documented only in `docs/CHANGELOG.md:408-412`), so the root guardrails block is regenerated manually and can silently drift from the source.

---

## 5. Impact assessment

### What demonstrably works

**The `@AIContext` / `@AILocked` / `@AICore` triangulation.** `async-test-lib`'s six-place `DetectorType` invariant is documented from three angles in three files. This is the pattern that most clearly buys something no other tool provides: it survives the AI opening any one of the three files first.

**Encoding knowledge that is unrecoverable from source.** The strongest sites all share this shape — `invocation.skip()` looking like a bug, the CRC32C revert, `install()`'s single legal caller, the `[BENCHMARK] REGRESSION` string being a parsed contract, JUnit mandating `supportsTestTemplate`'s exact name. None of these facts have another natural home in a codebase.

**Reason strings that cite external authorities.** The best ones are falsifiable, not decorative: a named test (`registrationClose_isIdempotent`), a CWE number (CWE-502), the JUnit spec, CLAUDE.md itself.

**The orphan warning.** `async-test-lib`'s log shows the `@AIIgnore`-without-`.claudeignore` warning firing and the user creating the file one minute later. Rare direct evidence of a feature changing behavior.

### What doesn't

**Nothing is enforced anywhere.** Zero of five projects use check mode (`-Avibetags.check=true`) or the locked-files action. This is the survey's central finding, and it's most damning in `async-test-lib`, which gates CI on **PMD, SpotBugs, Checkstyle, Error Prone, CodeQL, dependency-review, fuzzing, SBOM, and OpenSSF Scorecard** — and not VibeTags. Given that author's demonstrated CI maximalism, this reads as an unclosed loop rather than a considered choice.

The concrete cost, in order of severity:

1. **`blindbean` says the quiet part out loud** (`CLAUDE.md:68-70`): *"the committed GEMINI.md/CLAUDE.md guardrail blocks are now hand-maintained until regenerated once."* The generated blocks and the annotations they came from can diverge with nothing to catch it.
2. **`codekarta`'s `.vibetags-mod-_root_` is measurably stale** — its `claude=` payload decodes to empty `<locked_files>` while the committed `CLAUDE.md:76-114` has populated `<audit_requirements>` and `<contract_signatures>`.
3. **All 8 `@AILocked` sites in the corpus are advisory prose.** With no `.vibetags-locks` and no CI action, `DetectorType.java:12` — protecting the single most fragile invariant in `async-test-lib` — binds Claude via a markdown rule and nothing else. Not a human, not another AI tool, not a Claude session that ignores it.
4. **VibeTags does not use its own two enforcement mechanisms on itself.** Its CI runs a home-grown `verify-generated-files` action that asserts files exist and grep for expected strings — verifying output *shape*, not *sync with current annotations*. That is precisely the gap check mode was built to close.

**The multi-module story is a real product gap, not user error.** `blindbean` runs the processor exactly once, out-of-band, at the aggregate root, because *"per-module it would fragment the generated GEMINI.md."* Evidence: its sidecar is named `.vibetags-mod-_root_`. A reactor-aware aggregate mode would let the largest multi-module consumer drop a documented workaround and turn check mode on — the two are causally linked, since the manual workaround is *why* check mode can't run.

**`@AITestDriven` is diluting.** 108 of ~300 real uses, 103 of them identical, all encoding one fact derivable from file naming. It's more than a third of the corpus by volume and near the bottom by information density.

### Signals for the product

- **`Strategy.OTHER` is 24 of 33 `@AIThreadSafe` uses.** The taxonomy doesn't fit lock-free designs. A `LOCK_FREE` constant is the obvious fix.
- **`@AISchemaSafe` is bare at 15 of 15 sites** across two independent projects. Either the `reason` is genuinely unnecessary, or it should be required.
- **7 annotations have zero real-world traction.** Five are lifecycle markers with no in-flux code to mark; `@AIRegulation` needs an application-tier consumer this corpus lacks.
- **Nobody has opted into more than 3 platforms.** ~40 supported, 5 in actual use across five projects: `CLAUDE.md`, `GEMINI.md`, `llms.txt`, `llms-full.txt`, plus the ignore files. Claude is universal; everything else is incidental.
- **`skill3` is alone in needing `-Avibetags.root`**, and its comment explains why: Gradle runs javac in a worker with a different CWD. The other two Gradle projects don't set it and rely on auto-detection.

### Concrete fixes

1. **`blindbean-example/pom.xml:78`** — processor pinned at 0.9.7 while annotations are 1.0.0-RC2, hardcoded rather than inherited from the BOM. Straightforward bug.
2. **Turn on check mode in VibeTags' own CI.** The `vibetags-check` Maven profile is already documented at `USAGE.md:84` and invoked by nothing.
3. **Run `self-annotate` in CI**, or the root `<project_guardrails>` block drifts.
4. **Ship a reactor-aware aggregate mode**, which unblocks (2) for `blindbean`.
