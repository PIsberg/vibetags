# GitHub Actions Workflows

This document describes what happens during CI builds in `.github/workflows/`. Thirteen workflows: seven run on push, pull request, schedule, or release; mutation testing runs only when someone asks for it; the weekly perf ring runs on its own schedule; the demo recording runs when the code it demonstrates changes; the Copilot review lane requests an advisory reviewer on every PR and skips loudly when Copilot has no quota; and the two Anthropic-backed workflows (Inquisitor, Instruction Evals) run on pull requests but skip themselves, loudly, when the `ANTHROPIC_API_KEY` secret is absent.

## Overview

| Workflow | File | Trigger |
|---|---|---|
| Build and Test | `build.yml` | Push to `main`/`master`; every pull request, whichever branch it targets |
| CodeQL | `codeql.yml` | Push/PR to `main`, weekly cron (Mondays 00:00 UTC) |
| Dependency Review | `dependency-review.yml` | Pull requests |
| Scorecard | `scorecards.yml` | Push to `main`, branch-protection-rule, weekly cron (Tuesdays 07:20 UTC) |
| Publish to Maven Central | `publish.yml` | GitHub Release `created` |
| Fuzz Smoke (Jazzer) | `fuzz.yml` | Push/PR to `main` |
| Mutation Testing (PIT) | `mutation.yml` | Manual only (`workflow_dispatch`) |
| Demo GIF | `demo.yml` | Push to `main` touching the processor or the demo, manual |
| Gradle Wrapper Validation | `gradle-wrapper-validation.yml` | Push to `main`; PRs touching a wrapper |
| Inquisitor (adversarial AI review) | `inquisitor.yml` | Pull requests; needs `ANTHROPIC_API_KEY` |
| Instruction Evals | `instruction-evals.yml` | PRs touching `CLAUDE.md`/`AGENTS.md`/`GEMINI.md`/`.claude/**`/`evals/**`, manual |
| Copilot Review | `copilot-review.yml` | PR opened/reopened/ready-for-review |
| Nightly Perf Ring | `nightly-perf.yml` | Weekly cron (Mondays 03:17 UTC), manual |

All jobs run on `ubuntu-latest` and start with the StepSecurity `harden-runner` action in `audit` mode, which records every outbound network call. The default token permission for every workflow except Scorecard is `contents: read`; Scorecard declares `read-all`, as its action requires. Jobs that need more (e.g. CodeQL writes `security-events`, publish writes `contents` to attach release assets) escalate explicitly.

All third-party actions are pinned by full commit SHA with the version as a trailing comment — required by the OpenSSF Scorecard `Pinned-Dependencies` check.

---

## 1. Build and Test (`build.yml`)

The main CI workflow. Jobs run in parallel except `load-tests`, which waits on `build-maven`.

''It runs once per change, not twice.'' The `push` trigger is `main` and `master` only; every
branch is tested through the `pull_request` trigger, which is deliberately unfiltered so a
stacked PR - one targeting the branch it was built on - is tested too. Until #573 the push
trigger also listed the branch prefixes, so a branch with an open PR ran the whole matrix
twice, identically except `Locked Files Guard`, which skips on a push because it needs a PR
base to diff against. The cost of the narrowing, stated plainly: a branch pushed with no PR
open runs nothing.

''A superseded run is cancelled.'' Every workflow except `publish.yml` and `dependency-review.yml`
now declares a `concurrency` group keyed on the ref, cancelling in progress everywhere except
`main` and `master` - a run there is the record of that branch's health and must finish.
`inquisitor.yml` and `instruction-evals.yml` key theirs on the pull request number instead and
always cancel, which is the same thing for workflows that never run on a push.
`publish.yml` is excluded on purpose: cancelling a release mid-flight is worse than queueing
behind it.

### Job: `build-maven`

Matrix over **JDK 21, 25, 26** (Temurin distribution, Maven dependency cache). JDK 21 is the minimum supported version; the rest are forward-compatibility checks. Steps, in `build.yml` order:

1. **Harden runner** — egress audit.
2. **Checkout**.
3. **Set up JDK** — installs Temurin and primes the `~/.m2/repository` cache keyed on `pom.xml`.
4. **Install VibeTags Annotations** — `cd vibetags-annotations && mvn install -B`. Installs the zero-dependency annotations jar into the local Maven repo first, because `vibetags/pom.xml` declares it as a regular `<dependency>`. Runs the same static-analysis stack as the library (Checkstyle, PMD, CPD, SpotBugs with Find Security Bugs, Error Prone with NullAway) under the same JDK-21-only split for the source and bytecode tools.
5. **Build VibeTags Library** — `cd vibetags && mvn clean install -B`. Compiles the annotation processor, runs the fast test tier, and installs the artifact into the local Maven repo so the examples can resolve it. PMD, SpotBugs (with the Find Security Bugs detectors attached) and CPD are JDK-independent, so they run only on the JDK 21 leg (`-Dmaven.pmd.skip=true -Dspotbugs.skip=true` is passed on the other JDKs) to avoid repeating identical analysis on every leg. Error Prone still runs on every JDK because it is a compiler plugin and is JDK-sensitive, and it
carries NullAway with it — nullability is checked at `ERROR` on every matrix JDK, so a
`@Nullable` that stops being honoured fails the build rather than producing a warning nobody reads.
6. **Install VibeTags BOM** — `cd vibetags-bom && mvn install -B`. Installs `se.deversity.vibetags:vibetags-bom` (pom-only) into the local Maven repo. Required because `examples/basic/pom.xml` imports the BOM via `<dependencyManagement>` to resolve `vibetags-annotations` and `vibetags-processor` versions, and the BOM has to be resolvable before step 14 runs.
7. **Build VibeTags CLI** — `cd vibetags-cli && mvn clean install -B`. Runs after the processor install because the CLI consumes `vibetags-processor` as a library (`ServiceRegistry`, marker constants); its `InitCommandTest`/`DoctorCommandTest` are the gate on `init`/`doctor` behaviour. The `cross-platform` job repeats this build, which is the only place the CLI's filesystem behaviour runs on Windows and macOS path separators.
8. **Build VibeTags KSP Front End** — `cd vibetags-ksp && mvn clean install -B` (analysis on JDK 21 only, as for the library). Runs after the processor install because it drives `AIGuardrailProcessor` as a library. Its tests run real KSP2 in-process, including `StubParityTest`, which fails on any element path or generated byte that differs from a recorded kapt build.
9. **Run vibetags doctor Against The Groovy Example** (JDK 21 only) — runs the freshly built CLI's `doctor --dir examples/groovy` and asserts it exits **1** and names `InventoryService.groovy` and `field 'contactEmail'`. `doctor` is the only thing that reports the groovyc field drop that `examples/groovy/` gates, and until #533 that detection was exercised by the CLI's own unit tests alone, never against a real checkout. It runs from the module's classpath rather than `java -jar`: the published jar declares a `Main-Class` but carries no dependencies (#557), so this is the shape jbang resolves for a consumer. An exit 0 fails the step — it would mean `doctor` stopped seeing the drop.
10. **Run vibetags doctor Against The Kotlin Example** (JDK 21 only). The same shape as step 9, reusing its classpath file, against `examples/kotlin`. Asserts exit **1**, `kotlin sources:`, `AccountLedger.kt:26 @AILocked on fun balanceFor` and the `@JvmName("balanceFor")` remedy, and fails if the output names `fun settle` or `fun reconcile`: `balanceFor` takes a value class, so kapt drops its guardrail, while `settle` (takes `kotlin.Result`, not mangled) and `reconcile` (internal) keep theirs, so a finding for either is a false positive. Until #693 the value-class check (#688) ran only in `DoctorCommandTest`. The negative checks use `if grep ...; then exit 1; fi`, because a bare `! grep` does not fail a step under `bash -e` (#689).
11. **Run vibetags init --list Against The Basic Example** (JDK 21 only) — asserts the opt-in platform keys are printed and that `git status` for `examples/basic/` is still clean afterwards. `--list` prints; it must create nothing.
12. **Verify VibeTags' Own Guardrails Are Current (self-check)** — `bash tools/self-check.sh`, then a check
that the tree is clean, JDK 21 only. The script deletes the gitignored `.vibetags-mod-*` sidecars
and `.vibetags-cache`, runs `mvn clean test -Pself-annotate -Dtest=ProjectFactsConsistencyTest`
from `vibetags/`, and fails if that changed the working tree. The repo dogfoods its own
guardrails, and until this step nothing checked that the committed `CLAUDE.md` / `GEMINI.md` /
`.claude/rules` / `.vibetags-locks` matched what the processor writes. They had drifted.
Regenerating and diffing makes that a red build rather than something the next person to run the
profile by hand discovers. `ProjectFactsConsistencyTest` runs after the regeneration because it
pins README.md's line counts for the regenerated files. JDK 21 only because it compares file
content, which is JDK-independent. The same script is the `vibetags-self-check` pre-commit hook,
so this drift fails locally first; `SelfCheckGateWiringTest` pins both callers to it.

    It regenerates and diffs rather than using `-Dvibetags.selfcheck=true`, and the reason is worth
    keeping: check mode reports per compile round, and this project has two of them. Maven runs
    `default-compile` before `default-testCompile`, so on a clean clone the main round compares
    main-only output against committed files that also carry the test round's guardrails and fails
    before the test round runs. It passes on a machine that has built before, because a gitignored
    `.vibetags-mod-*` sidecar is still on disk — green everywhere except CI, which is the wrong way
    round for a gate. Issue #794 tracks the check-mode behaviour itself.

    Locally the script compares against the tree it started from rather than against `HEAD`, so
    uncommitted work is not reported as drift; under pre-commit the unstaged half is stashed, so it
    judges exactly what is being committed. Gitignored files, `vibetags/CLAUDE.md` among them, never
    show in that comparison.
13. **Reset AI Config Files** — `cd examples/basic && bash reset-ai-files.sh`. Truncates every generated AI config file in `examples/basic/` to zero bytes, deletes the write cache and any `*.bak` files, and removes the generated `.md`/`.mdc` files from each granular rule directory. The files themselves are kept (their existence is the opt-in signal for the processor), but their content is cleared so the next compile must regenerate everything from scratch.
14. **Build Example Project** — `cd examples/basic && mvn clean test-compile -B -Dvibetags.log.path=../../vibetags.log`. `test-compile`, not `compile`: the example's `src/test/java` is what gives VibeTags a test round and makes it write `TESTING.md`. The processor runs during `javac` of the example, sees the existing (now-empty) AI config files, and writes generated content back into them. The processor log goes to the repo root.
15. **Verify The Example's Committed Guardrails Match Its Annotations** — fails if `git status --porcelain -- examples/basic/` is non-empty after the rebuild, so an annotation edited without committing the regenerated files is red.
16. **Rebuild basic With The Write Cache Disabled** — `mvn clean test-compile -B -Dvibetags.cache=false` in `examples/basic`, then the same clean-tree check. The cache-off path otherwise ran only inside library tests.
17. **Multi-module example (`examples/multimodule`, Maven reactor)** — `mvn clean verify -B`, then ten verification steps: the committed files match a regeneration; the transitive manifest lands at `core/target/classes/vibetags/manifests/` and `engine/` and `cli/` inherit it; every module appears in the merged `CLAUDE.md` and `.cursorrules`; all 44 annotation families survive the merge (element sections counted against the annotation sources); exactly **58** active services, with a spot check that each marker-based file carries a VibeTags block and that `SKILL.md` has its front matter once (#684); the committed files pass check mode (`-Dvibetags.check=true`); the six reactor YAML files parse with duplicate top-level keys forbidden and a witness from every module surviving; `cli/CLAUDE.md` carries only `cli`'s guardrails; `.vibetags-mirror` copies each module's scoped rules into `tests/` without giving it a root region of its own (#312); and the `.vibetags-roles` file `reactor-spine.md` merges all three modules in eight granular directories and survives a one-module `mvn -pl core` rebuild byte-identical (#365).
18. **All-tiers example (`examples/all-tiers`)** — `mvn clean compile -B`, then asserts the tier split: the six safety buckets inline in the root `CLAUDE.md` and none of the verbose ones, a Tier-1 region and pointers per module, no cross-module leakage between `billing/CLAUDE.md` and `shipping/CLAUDE.md`, role-grouped Tier-3 files with `paths:` front matter, and a parameter-level guardrail. A second step runs check mode on it.
19. **Indexed multi-module example (`examples/multimodule-indexed`, #295, #319, #332)** — builds it, runs check mode, then asserts the indexed root: one pointer per module with the safety tier still inline, no verbose buckets, `GEMINI.md` still carrying the full merged block, `.github/copilot-instructions.md` collapsed to a scoped-rules index with every module's `.github/instructions/` file intact, the `.clinerules/` directory's per-element files and its always-loaded `+vibetags-safety.md` (#642, #648), and one `## Locked Files` heading rather than one per module.
20. **Enforcing-mode example (`examples/enforcing`, #284)** — builds with `-Avibetags.enforce=locked,contract,publicapi` against the committed `.vibetags-baseline`, checks the committed files match, then drifts the `@AILocked` method's parameter type and requires the build to fail with an `@AILocked violation` naming `computeTariff(java.lang.String,long)`. A green build there would mean enforcement never ran.
21. **Run Full Test Suite (VibeTags Library, incl. e2e)** — `cd vibetags && mvn test -B -Pe2e`. This is not the redundant second pass it looks like. Step 5's `mvn install` runs the fast tier only, because plain `mvn test` skips the classes tagged `@Tag("e2e")` (111 of the 307 test classes on 2026-09-26); `-Pe2e` adds them back, so this step is the only place the whole suite runs and the only one that can call the branch green. See `docs/TESTS.md` for what is tagged and why.
22. **Verify Generated AI Config Files** — the local composite action `.github/actions/verify-generated-files` (`working-directory: examples/basic`), two steps in one. The first checks that every expected file exists and is non-empty: the root aggregates (`.cursorrules`, `CLAUDE.md`, `GEMINI.md`, `QWEN.md`, `CONVENTIONS.md`, `TESTING.md`, `llms.txt`, `llms-full.txt`, `.github/copilot-instructions.md` and more), the ignore files, the PR-reviewer and context-packer configs, the skill files, and the granular `PaymentProcessor` rule file in each scoped-rules directory (plus `DatabaseConnector` under `.cursor/rules/` and the `+vibetags-safety.md` files for Devin and Windsurf). Failure means the processor skipped a platform or wrote nothing. The second greps for the platform-specific `@AIAudit` phrasing (e.g. `MANDATORY SECURITY AUDITS` in `.cursorrules`, `audit_requirements` in `CLAUDE.md`, `CONTINUOUS AUDIT REQUIREMENTS` in `gemini_instructions.md` and the collapsed `GEMINI.md`), which catches a file that is non-empty while its audit rendering has broken. It also asserts that the hand-authored root `AGENTS.md` was **not** generated into (the sole-file fallback, invariant 4), while `.junie/AGENTS.md` was.
23. **Verify the Central deploy script reports failures** (JDK 21 only) — `bash .github/scripts/deploy-to-central.test.sh`, the regression test for `deploy-to-central.sh` reading Maven's exit status rather than `tee`'s.
24. **Upload coverage to Codecov** — only on the JDK 21 matrix leg, to avoid duplicate uploads. Reads `vibetags/target/site/jacoco/jacoco.xml`. Passes `fail_ci_if_error: false`, so a Codecov outage doesn't fail the build.
25. **Upload failure logs** — `if: failure()` only. Keeps `vibetags.log` (the processor's diagnostic channel) and the surefire reports of the library, CLI and KSP modules as artifacts, because a CI-only failure whose log died with the runner has to be reproduced instead of read.

The generated-file and `@AIAudit` verification logic used to be duplicated inline in both `build-maven` and `build-gradle`; it now lives once in `.github/actions/verify-generated-files/action.yml` (a local composite action, `working-directory` input defaulting to `example`) and is invoked by both jobs — plus `cross-platform` (see below) — so the three call sites can never drift out of sync.

### Job: `cross-platform`

Matrix over **`windows-latest`, `macos-latest`** (`fail-fast: false`), JDK 21, `shell: bash`. The main matrix only runs on Linux, but the processor's file handling is OS-sensitive — path separators, CRLF line endings, the marker-aware `GuardrailFileWriter`, and `root.relativize()`. This job installs the annotations jar, builds the library, installs the BOM, builds the CLI, resets and rebuilds the example project the same way `build-maven` does, builds the Maven multi-module example (`mvn clean verify -B`), and runs the whole library suite including e2e (`cd vibetags && mvn test -B -Pe2e`) on Windows and macOS. It then calls the same `.github/actions/verify-generated-files` composite action used by `build-maven`/`build-gradle`, so a Windows- or macOS-only regression in the generated output is caught here too — previously this job only ran unit tests and did not build or verify the example project. It uses the default `JAVA_HOME` (the Linux-only `JAVA_HOME_21_X64` does not exist on Windows or arm64 macOS) and omits the `harden-runner` step, which only supports Linux runners. On failure it uploads `vibetags.log` and the surefire reports, same rationale as `build-maven` step 25 — the OS-specific file-handling paths are exactly why this job exists, so their evidence must survive it.

### Job: `load-tests`

Single JDK 21 leg, `needs: build-maven`. Steps:

1. **Harden runner**, **checkout**, **set up JDK 21** (Maven cache).
2. **Install VibeTags annotations** — `cd vibetags-annotations && mvn install -B`.
3. **Install VibeTags processor** — `cd vibetags && mvn install -DskipTests -B`.
4. **Run the load-test regression gates** — resolves the version this run just built with `mvn help:evaluate`, fails loudly unless it looks like `N.N.N` (an empty value would silently fall back to the pom's pin), then `cd load-tests && mvn verify -B -Dprocessor.version="$VT_VERSION" -Dtest="AnnotationVolumeStressTest,ConcurrentBuildTest,SignatureCaptureStressTest,TestingMdRoutingStressTest,PlatformBreadthStressTest,IncrementalRebuildStressTest" -Dstress.max.classes=500`, against the processor this run built rather than the pom's `<processor.version>` pin. `verify`, not `test`, because PMD, CPD and SpotBugs bind to that phase. These are gates, not measurements: each asserts that the feature it covers engaged (routing routes, scoped rules collapse the aggregate, the fingerprint short-circuit fires, enforcement-off still saves), and nothing here compares a timing against a baseline. Measurement lives in `load-tests/results/`, captured by hand.
5. **Upload stress-test results** — `if: always()`, so artifacts upload even on failure. Globs `load-tests/target/{stress-results,testing-md-routing,platform-breadth,incremental-rebuild}-*.txt`, retained as `stress-results-${{ github.run_id }}`.

### Job: `build-gradle`

Mirror of `build-maven` but with Gradle. Matrix over **JDK 21, 25, 26**. Differences:

- Uses Gradle dependency cache.
- Gradle wrappers (`gradlew`/`gradlew.bat`, Gradle 8.8) are committed in `vibetags-annotations/`, `vibetags/`, and `examples/basic/` — copied from `vibetags/`'s pre-existing wrapper. CI invokes `./gradlew` in every subproject; there is no on-the-fly `gradle wrapper` generation step.
- Annotations build: `cd vibetags-annotations && ./gradlew clean build publishToMavenLocal --no-daemon`. Runs first because the processor depends on it.
- Library build: `cd vibetags && ./gradlew clean build publishToMavenLocal --no-daemon`.
- BOM install: `cd vibetags-bom && mvn install -B`. The BOM is Maven-only; Gradle reads it from `mavenLocal()` when resolving `platform('se.deversity.vibetags:vibetags-bom:...')` in `examples/basic/build.gradle`. This step runs after the library build and before the example build.
- Example build: `cd examples/basic && ./gradlew clean build -PcompilerArgs="-Avibetags.log.path=../vibetags.log" --no-daemon`.
- Kotlin example build (JDK 21 leg only): `cd examples/kotlin && ./gradlew clean build --no-daemon`, then greps the regenerated `CLAUDE.md` / `.cursorrules` for the annotated Kotlin elements — the proof that kapt actually ran the processor. Gated to 21 because the Kotlin Gradle plugin trails new JDK releases and the example pins a 21 toolchain anyway.
- Kotlin KSP example build (JDK 21 leg only, #496): installs `vibetags-ksp` from Maven against the processor this leg published, builds `examples/kotlin-ksp` (the same sources through KSP), and fails unless its `CLAUDE.md` and `.cursorrules` equal `examples/kotlin`'s byte for byte, unless the build warns about the `balanceFor` guardrail kapt drops silently, or if the build changed any committed file. The Gradle exit code is read from `PIPESTATUS`, not from the `tee` it is piped into.
- Groovy example build (JDK 21 leg only, same shape): joint-compilation stubs with `javaAnnotationProcessing = true`; greps prove the processor saw the Groovy class.
- Scala example build (JDK 21 leg only): asserts the feature *and* the limitation — the annotated Java class must appear in the generated files, and the annotated Scala class must **not** (`! grep`), because scalac has no JSR 269 support. If the negative assertion ever fails, the docs' support matrix is wrong, not the build.
- **Gradle layout examples** (JDK 21 leg only), one step each, undocumented here until now although
  they have been running for several releases. Each builds a layout that Maven cannot express and
  asserts the specific failure it prevents: `gradle-multimodule` (reactor, one sidecar per module,
  never one collapsed onto the root), `gradle-shared-buildfile` (subprojects with no build file of
  their own; without `-Avibetags.module` they share one identity and the last to compile wins),
  `gradle-flat` (module beside the root; a hash-named sidecar means the module resolved out of tree,
  which the step greps for explicitly), `gradle-composite` (`includeBuild`, two builds writing into
  one root). All four compare the committed `CLAUDE.md` against what the build just produced.
- **Gradle granular assertions** (JDK 21 leg, inside the `gradle-multimodule` step). Dual opt-in
  collapses each module region to a scoped-rules index while the safety buckets stay inline. `core/`
  has its own granular directory, `app/` opts into nothing and must therefore have no `.claude`
  directory at all. Before this, no Gradle build in the repository exercised the granular path, so
  invariants 6 and 13 were verified on one of the two supported build tools. The same step now also
  asserts the reactor emits **no warnings at all** on a clean build of an in-sync tree, which is
  what caught a spurious "guardrails stated nowhere" report on builds that had written every file
  correctly.
- **Gradle reactor depth**, seven steps, JDK 21 leg (issue #443). The Maven reactor had eleven
  verification steps to Gradle's one while the three most recent multi-module defects all came from
  Gradle repositories, so these port the Maven assertions across: transitive manifests published to
  Gradle's `build/classes/java/main/` and actually read by the consuming module, with the origin
  coordinate asserted rather than just the key; all 58 services active, with Codex correctly
  dropped because `AGENTS.md` is not the sole config; the six generated YAML documents parsed with
  duplicate top-level keys forbidden and a per-module witness required to survive the parse;
  per-module nested output in both shapes (indexed and plain) with no sibling leakage; cross-module
  rule mirroring into a module with no annotations of its own, which must create no region in the
  root; role-based granular grouping across modules, including the issue #365 repro that building
  one subproject must leave the shared role file byte-identical; and every declared annotation
  having an element section in `annotations-showcase/CLAUDE.md`, which is the one full inline aggregate in a
  reactor whose root is indexed by design.
- **Gradle check mode**, two steps, JDK 21 leg. The first runs `./gradlew build -PvibetagsCheck`
  twice and fails if the second run reports `compileJava UP-TO-DATE`: check mode lives inside the
  annotation processor, so a skipped compile means the gate verified nothing. Measured with the
  example's `outputs.upToDateWhen { false }` removed and the committed `CLAUDE.md` drifted, the
  check reported `UP-TO-DATE` and exited 0. The second step drifts `CLAUDE.md` deliberately and
  requires a non-zero exit, then asserts the drift is still there, since check mode must never
  write. Every other check-mode gate in the pipeline is Maven.
- Tests: the library build above runs the fast tier, and a later step runs the whole suite with
  `cd vibetags && ./gradlew test -Pe2e --no-daemon`. That step is followed by an `if: failure()`
  upload of `vibetags/build/reports/tests/test` and `vibetags/build/test-results/test`. Before
  that upload existed, a failing Gradle test left only an exception class and a line number in
  the console, and the report carrying the assertion message died with the runner;
  `build.gradle` now also sets `exceptionFormat = "full"`. See `docs/TESTS.md` for why the
  Gradle worker's heap is pinned rather than left at Gradle's 512m default.
- Codecov reads `vibetags/build/reports/jacoco/test/jacocoTestReport.xml`, uploads under flag `unittests-gradle`, and passes `fail_ci_if_error: false`.

The same `.github/actions/verify-generated-files` composite action runs after the Gradle build, so any divergence between Maven and Gradle output paths is caught.

Mutation testing used to be a job here. It now lives in its own manually triggered workflow — see section 7.

### Job: `corpus`

Real third-party Java, compiled twice. `corpus/run-corpus.sh` clones six permissively licensed
libraries at pinned commit SHAs and compiles each one with and without VibeTags on the processor
path, with identical sources, classpath and flags.

It exists because every fixture in this repository was written by somebody who knew what VibeTags
does, and that is the wrong sample. The code VibeTags actually has to survive was written by
people who had never heard of it: deeper nesting, generic signatures nobody would invent for a
test, `package-info` files, records, and a module whose own annotation processor is already
running.

Four assertions, each **against the control** rather than a hard-coded expectation, so a repo
that does not compile on its own is reported as such instead of being blamed on VibeTags:

1. **The treatment exits exactly as the control did.** Adding VibeTags to a build must not fail it.
2. **No diagnostic the control did not raise.** A processor that turns a clean build noisy has
   broken the same promise more quietly.
3. **Nothing written to the VibeTags root.** File presence is the only opt-in and none of these
   repos opted in. `vibetags.log` is the documented exception.
4. **`ElementNaming` renders every member the way javac does.**
   `ElementNamingFormatParityTest` checks that against a 26-member fixture; this checks it
   against roughly 15,700 members nobody chose.

Assertion 0 comes before all of them: the control has to compile. Otherwise a repo that cannot
compile passes 1 and 2 trivially, both sides failing identically, while 3 and 4 run against a
model of error types.

A second phase then opts **Claude, Gemini and Codex** in, aggregate and granular, annotates two
real elements plus a showcase covering every level a guardrail attaches to (package, type, nested
type, field, method, parameter) in both tiers, and reads the output back. Ten more assertions,
including the tier split (invariant 6 on somebody else's code) and a richness floor that fails if
fewer than 17 distinct showcase guardrails reach a generated file (`SHOWCASE_FLOOR` in
`corpus/run-corpus.sh`).

A third phase opts in **every** service file the registry knows about, on one repo, and reads the
result back: each written non-empty, and every YAML, TOML and JSON file parsed with a real parser.
That last check is the one the fixture tests cannot make. They assert what a renderer *contains*;
none asserts that a parser accepts it, and a renderer emitting an unquoted `@` or a trailing comma
satisfies every `contains` assertion while being unloadable by the tool it targets. On commons-cli,
main run 36225192209 (2026-09-26) logged 63 of 84 files written, 12 parsed.

The checkouts are cached on `corpus/repos.tsv`'s hash, so most runs do no network at all, and
pins are SHAs so an upstream push cannot turn this repository red. Nothing is vendored.

Assertion 4 found a real divergence the first time it ran: javac includes JSR-308 type-use
annotations in its rendering and VibeTags deliberately does not, which moves output for any
consumer using jspecify or the Checker Framework. [corpus/README.md](../corpus/README.md) has
the reasoning and the full repo table.

### Job: `corpus-jvm`

The same question in Kotlin, Groovy and Scala. `corpus/run-corpus-jvm.sh` clones three
permissively licensed repositories at pinned SHAs and builds each **twice with its own Gradle
wrapper**, the only difference being `corpus/inject-vibetags.init.gradle` — an init script,
because that is the supported way to add a plugin to a build you do not own, and it means nothing
here edits a build file it did not write.

It is a separate job from `corpus` because none of these languages reaches the processor the way
Java does: Kotlin only through kapt, Groovy only when joint compilation's `javaAnnotationProcessing`
is switched on, Scala not at all. `--rerun-tasks` is passed on every invocation, because otherwise
Gradle's up-to-date checks let the treatment skip compilation entirely and the whole comparison
becomes a green run that compiled nothing.

The Scala member asserts a negative and its own anti-vacuity guard: annotated `.scala` must
generate nothing, the Java half of the same build must generate everything, and the Scala showcase
must have produced class files — otherwise "scalac generated nothing" and "scalac was never asked"
are the same result.

It found that **Groovy silently drops every field-level annotation**: groovyc's stubs carry types,
constructors, methods and parameters and no fields, so `@AIPrivacy` on a Groovy field generates
nothing at all. USAGE.md called that route "Full (joint compilation)" until this job ran.
[corpus/README.md](../corpus/README.md#the-other-three-jvm-languages) has the stub evidence, the
other findings, and the eight-repository survey behind the choice of Groovy member.

Two caches: the checkouts on the manifest and harness hashes, and `~/.gradle` on the manifest,
because six Gradle builds otherwise re-download a distribution each and that is most of the job's
wall clock.

### Job: `ecj-degradation`

The one leg that does not run javac. `tools/ecj-degradation-check.sh` compiles
`examples/basic` twice over — once with javac, once with the Eclipse Compiler for Java pinned by
`<ecj.version>` in `vibetags-parent/pom.xml` — and compares the results.

It exists because [docs/PROCESSOR.md](PROCESSOR.md) and [USAGE.md](../USAGE.md) both promise
that VibeTags *degrades* under a compiler with no Tree API, losing `@AILocked` line positions and
nothing else, and nothing verified either sentence. The code behind that promise —
`SourcePositionResolver`'s whole no-Tree-API branch and the `Trees.instance` guards around it — is
unreachable from a JUnit test running under javac, and reaching it from one would mean adding a
seam to production code purely so a test could fail it (issue #475). A real compiler needs no
seam.

Four assertions, in order of what they protect:

1. **ECJ compiles the fixture and exits 0.** The promise the whole design rests on is that
   VibeTags never fails somebody's build. A compiler it cannot fully serve must still compile.
2. **Both compilers report the same `@AILocked` elements** (9 at the time of writing). Degrading
   costs positions, not guardrails — a lock that vanishes under ECJ is a lock nobody enforces.
3. **Every javac entry carries a position and no ECJ entry does.** The javac half is the control:
   without it, assertion 3 passes just as well on a report that contains nothing at all.
4. **Every guardrail's prose and every type-level element path is identical** between the two.

Assertion 4 is deliberately not a byte-for-byte diff of the marker region, and the script says
why at the assertion: member element paths *do* differ between the compilers, because
`ElementNaming` leans on `Element.toString()`, whose format `javax.lang.model` leaves to the
implementation. This job is what found that.

### Job: `locked-files`

Pull requests only. Dogfood of the shipped `action/locked-files`: installs the annotations and
processor, then invokes the action with `build-command: mvn -B -q -f vibetags/pom.xml clean
test-compile -Pself-annotate`, which regenerates `.vibetags-locks` at the PR head (the action touches
the file first; file presence is the opt-in). The action then intersects the PR diff with the
locked line ranges and fails when a diff touches an `@AILocked` element, naming the element and
its recorded reason. The same guard consumers get, applied to the repository that ships it.

### Job: `diagrams`

Regenerates the code-karta architecture diagrams (`sh tools/generate-architecture-diagrams.sh`)
and fails when their structure drifts from the committed SVGs under `docs/diagrams/codekarta/`.
A generated diagram that only regenerates when someone remembers is documentation, not
telemetry; this job makes "the picture matches the code" a property of every commit. The
comparison is structural (`tools/diagram-structure.sh`: the sorted set of node titles per
file), not byte-level, because code-karta 0.1.0 walks directories in filesystem order - the
job's own first run proved the same node set renders at different positions on ext4 than on
the NTFS machine that committed the files. Edges and member labels are outside the
fingerprint; the script's header states that limitation. On failure the fresh SVGs upload as
the `regenerated-diagrams` artifact so the committer can take them without reproducing the
toolchain.

---

## 2. CodeQL (`codeql.yml`)

Static-analysis security scanning over Java sources.

- Triggers: push/PR to `main`, weekly cron `0 0 * * 1` (Mondays midnight UTC).
- Job permissions: `actions: read`, `contents: read`, `security-events: write`.
- Steps: harden runner → checkout → `codeql-action/init` (language `java`) → set up JDK 21 → `cd vibetags-annotations && mvn install -B -q` (the library declares it as a plain dependency) → `cd vibetags && mvn clean install -B -q` → `cd vibetags-bom && mvn install -B -q` (the example imports the BOM, so it must be resolvable before the next step) → `cd examples/basic && mvn clean compile -B -q` (so CodeQL traces both library and consumer code) → `codeql-action/analyze` with category `/language:java`.
- Findings appear under the repository's Security → Code scanning tab.

---

## 3. Dependency Review (`dependency-review.yml`)

Runs on every pull request. Steps: harden runner → checkout → `actions/dependency-review-action`. Compares the PR's dependency manifests against the base branch and surfaces any newly introduced vulnerable versions. If configured as a required check, blocks the merge.

---

## 4. Scorecard (`scorecards.yml`)

OpenSSF Scorecard supply-chain analysis.

- Triggers: push to `main`, `branch_protection_rule`, weekly cron `20 7 * * 2` (Tuesdays 07:20 UTC).
- Permissions: `read-all` workflow default; the analysis job adds `security-events: write`, `id-token: write`, plus read scopes for issues/PRs/checks needed for scorecard's GraphQL probes.
- Steps: harden runner → checkout (`persist-credentials: false` so the token doesn't leak to subsequent steps) → `ossf/scorecard-action` writing `results.sarif` → upload SARIF as a 5-day-retention artifact → `codeql-action/upload-sarif` to publish into GitHub's code-scanning dashboard.
- `publish_results: true` lets the project carry the OpenSSF Scorecard badge.

---

## 5. Publish to Maven Central (`publish.yml`)

Triggered when a GitHub Release is created, and by hand (`workflow_dispatch`) to resume a release
that published some modules and not others. A deploy can fail after its bundle is already
uploaded, because Central's validation is sometimes slow rather than stuck; before the manual
trigger existed, the only way to finish such a release was to cut another tag. The dispatch
takes `ref` (the tag being resumed) and `modules` (`all`, or a comma-separated subset of
`annotations,processor,ksp,bom,cli`).

- Job: `publish-maven-central`, JDK 21, `permissions: contents: write` (to attach release assets).
- **Resolve which modules to deploy** — validates `modules` against `[a-z,]` and writes one
  output per module. The input reaches the script through `env`, never through `${{ }}` inside
  `run:`, because an interpolated expression becomes script text in the one job that holds the GPG
  key and the Central token.
- Sets up Maven with `server-id: central` and exports `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` for the deploy steps.
- **Import GPG key** — pipes `secrets.GPG_PRIVATE_KEY` into `gpg --batch --import`, then prints key fingerprints.
- **Five deploy steps, each gated on its module being selected**, each calling
  `.github/scripts/deploy-to-central.sh <module-dir> <artifact> [maven args]`. The script runs
  `mvn clean deploy -P central-publish,sign-artifacts`, reads Maven's exit status from `PIPESTATUS`
  rather than from the `tee` it pipes through, treats "already exists" on Central as success so a
  resume can re-run modules that landed, and retries a transport failure up to 3 times with
  backoff. `build-maven` step 23 tests the failure reporting. Order:
  1. **annotations** — first; the processor depends on it.
  2. **processor** (`-DskipTests`) — tests ran on every push that led to the tagged commit.
  3. **KSP front end** (`-DskipTests`) — after the processor, which it depends on; before the BOM, which manages it.
  4. **BOM** — before the CLI, deliberately. Every install snippet in the README resolves through
     the BOM, and when it used to go last a CLI deploy that timed out took the BOM down with it,
     leaving every documented way of depending on the published jars broken.
  5. **CLI** — last; it consumes the processor as a library, nobody's build depends on it, and its
     fast filesystem-only tests run here as a final gate.
- **Attach signed artifacts to the GitHub release** — release events only; uploads each module's
  jars and `.asc` signatures (the BOM's `.pom.asc`) so Scorecard's `Signed-Releases` check can
  verify them. A manual resume has no release to attach to.

Required repository secrets: `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD`. CI also references `CODECOV_TOKEN` from `build.yml`.

---

## 6. Fuzz Smoke (`fuzz.yml`)

A 10,000-iteration Jazzer run against the OSS-Fuzz harness in `oss-fuzz/`, on every push and pull request to `main`. Single JDK 21 leg, `contents: read`, 15-minute timeout.

It exists to catch two failure classes:

- **Harness rot.** The harness compiles against the live processor classes (`vibetags/target/classes`), so a processor API change that breaks `oss-fuzz/VibeTagsFuzzer.java` is a red build here instead of a surprise at OSS-Fuzz submission time. Not hypothetical: the harness sat unused from 2026-04 and had to be re-synced to the 0.7.1 API by hand.
- **Real findings.** The run is a genuine coverage-guided fuzz of `AIGuardrailProcessor.writeFileIfChanged` (marker parsing, front-matter detection, content merge). 10,000 iterations is a smoke, not a campaign, but Jazzer's CMP instrumentation discovers the `VIBETAGS-START` marker strings on its own within a few thousand executions, so the interesting parse paths are reached. An uncaught exception or hang fails the job and uploads the triggering input as the `fuzz-findings-<run_id>` artifact.

- Steps: harden runner → checkout → set up JDK 21 (Maven cache) → `cd vibetags-annotations && mvn install -B -q -DskipTests` → `cd vibetags && mvn clean compile -B -q` → download Jazzer v0.30.0 and verify it against a SHA-256 recorded in the workflow (a moved release tag cannot substitute a different binary) → fetch `com.code-intelligence:jazzer-api` from Maven Central for the compile classpath → `javac` the harness → build the runtime classpath with `dependency:build-classpath` (the processor jar is not shaded, so slf4j/logback must be on the fuzzer's `--cp`) → run with `-runs=10000` (measured: 120 s for 10,000 runs on a Windows laptop; Linux runners are faster).
- There is no `continue-on-error`: a failure here is either a real finding or real rot, never noise.

---

## 7. Mutation Testing (`mutation.yml`)

PIT mutation coverage over `se.deversity.vibetags.*`. **On demand only** — the sole trigger is `workflow_dispatch`, so nothing starts it on push or pull request. Run it from Actions → Mutation Testing (PIT) → Run workflow, picking the branch to analyse.

It was a job in `build.yml` until it was split out. A full PIT run costs more wall-clock than the rest of CI put together, its score moves slowly, and `continue-on-error: true` meant no result it produced could ever fail a build — so every push paid for a number nobody read. The split also drops `continue-on-error`: when the run is deliberate, a red run should read as red.

- Single JDK 21 leg, `ubuntu-latest`, `contents: read`.
- Steps: harden runner → checkout → set up JDK 21 (Maven cache) → `cd vibetags-annotations && mvn install -B` → `cd vibetags && mvn -B -Pmutation -Pe2e test-compile org.pitest:pitest-maven:mutationCoverage`.
- **`-Pe2e` is load-bearing, not optional.** `pitest-maven` parses surefire's configuration, and surefire carries `<excludedGroups>${vibetags.test.excludedGroups}</excludedGroups>`, which defaults to `e2e`. Without `-Pe2e`, PIT runs the fast tier and scores the project as if 55 test classes did not exist (the count when this was measured; 111
classes carry the tag on 2026-09-26). Measured on `main` over the seven classes PIT reported as entirely uncovered: 19% line coverage and 16 of 211 mutants killed without it, 88% and 142 of 211 with it. The code was never untested, only unmeasured. Cost is roughly +57% wall-clock on that scope, which is affordable for a `workflow_dispatch`-only job.
- The `mutation` Maven profile (in `vibetags/pom.xml`) pulls in `pitest-maven` and `pitest-junit5-plugin` and is otherwise inactive — it only applies when `-Pmutation` is passed explicitly, so normal `mvn install` / `mvn test` runs are unaffected.
- **The score has a floor: `<mutationThreshold>80</mutationThreshold>` and `<coverageThreshold>90</coverageThreshold>`.** Until #558 there was neither, so the badge could say 86% while a change that dropped it to 70 stayed green. Run [33372147965](https://github.com/PIsberg/vibetags/actions/runs/33372147965) (2026-08-31) measured **86% mutation coverage, 3427/3992 mutants over 138 classes**, with 96% line coverage and 89% test strength; both thresholds sit six points under those. Deliberately a floor rather than a ratchet — PIT varies a little run to run, and a gate that fails on noise is one people learn to rerun rather than read. When the score rises, raise the floor and change the number here in the same commit; `MutationThresholdTest` fails if the thresholds disappear from the pom.
- **Upload PIT mutation report** — `if: always()`. Uploads `vibetags/target/pit-reports/**` as `pitest-report-${{ github.run_id }}`, `if-no-files-found: warn`.
- The PIT badge in `README.md` is a hand-maintained static badge; update it from a dispatched run rather than expecting CI to move it.

---

## 8. Demo GIF (`demo.yml`)

Regenerates `docs/demo.gif` when the processor, the annotations, or the demo script change
(push to `main` with path filters, or manual dispatch). Builds the library, records a scripted
session with asciinema against `tools/demo/` (placeholder files reset to empty first, so the
recording shows real generation), converts the cast to GIF with agg, and opens a PR with the
updated GIF rather than pushing to `main` directly. A demo that shows a previous version's
output is a claim the README makes and nothing checks; regenerating it on the trigger that
invalidates it keeps the claim honest.

---

## 9. Gradle Wrapper Validation (`gradle-wrapper-validation.yml`)

Verifies every checked-in `gradle-wrapper.jar` against known-good Gradle release checksums. The
push trigger deliberately carries no path filter: OpenSSF Scorecard's Binary-Artifacts check
requires a successful run at the head SHA of the default branch, so filtering to wrapper paths
would leave the wrappers permanently reported as unverified. PRs keep the filter.

---

## 10. Inquisitor (`inquisitor.yml`)

An adversarial AI reviewer with the opposite objective function from whoever produced the diff:
it is rewarded for finding violations of committed rules, not for shipping. Three separations
make it adversarial by construction — its own objective, its own context (the raw diff plus the
committed law only, never the conversation that produced the change), its own runtime (a CI
job). The reviewer prompt is versioned at `.github/INQUISITOR.md`; its authority is limited to
committed artifacts (`CLAUDE.md` guardrails, `.claude/rules/`, `docs/LOAD-BEARING.md`, the
stated conventions), and its output contract is a structured gripe — target, violated rule,
file:line evidence, explanation, executable remediation — or a one-line ALL CLEAR. The verdict
gate is deliberately dumb bash over files the reviewer writes: the model never decides its own
exit code. Runs only when the `ANTHROPIC_API_KEY` secret exists. A small preflight job ("Check
for the review key") turns the secret's presence into a job output, because a job-level `if:`
cannot read `secrets`; without the key the `Adversarial Review` job is skipped, so the check reads
Skipped in the PR and the preflight summary says SKIPPED. Until #697 the job gated each step
instead, which skipped every step and still reported the job as a green pass. Branch protection
treats a skipped job as satisfying a required check, so promoting this one to required also means
making a missing key fail rather than skip. The model is pinned by exact ID in the workflow;
changing it is a reviewed commit like any other change to an enforcer. Not yet in the
required-checks set — it blocks nothing until its verdict record earns that promotion.

---

## 11. Instruction Evals (`instruction-evals.yml`)

The merge gate for the instruction layer: when a PR edits the files agents obey (`CLAUDE.md`,
`AGENTS.md`, `GEMINI.md`, `.claude/**`), the task bank under `evals/` runs — headless agent
trials in disposable worktrees, deterministic detectors, per-task pass-rate floors — and the
job fails if a measured rule drops below its floor. The same idea as running the test suite
when a PR edits code, applied to the one load-bearing artifact that otherwise cannot go red.
Method, floors, and honest limits: `evals/README.md`. Requires `ANTHROPIC_API_KEY`; skips
loudly without it. Trial logs upload as the `instruction-eval-results` artifact.

---

## 12. Copilot Review (`copilot-review.yml`)

The always-available adversarial review lane: on every opened, reopened, or
ready-for-review PR, the job requests a GitHub Copilot code review via the reviewer API.
Copilot needs no repository secret and spends the maintainer's Copilot Free quota, so this
lane works when the Inquisitor's does not; together they mean every PR gets at least one
machine reviewer whenever either lane has credit. The job requests and then VERIFIES: the
request API returns success even when GitHub silently drops the reviewer (observed live on
this repo's PR #417 - a 2xx and then no review-request event at all, from the Actions token
and from the maintainer's own token alike, with the equivalent ruleset parameter silently
stripped too), so only a recorded pending request counts as requested. Anything else - quota
exhausted, code review not available on the account or plan, rollout - skips loudly and stays
green: an advisory reviewer must never block a merge, but the summary says SKIPPED, because
skipped is not passed. `synchronize` is deliberately not a trigger, so work-in-progress
pushes do not drain the quota; re-request from the PR page when a revised diff deserves
fresh eyes. The lane's
model is GitHub-managed and unpinnable, which `.github/MODEL-ROSTER.md` records as a known
property, acceptable exactly because the lane is advisory.

---

## 13. Nightly Perf Ring (`nightly-perf.yml`)

The scheduled half of the performance contract (the inner-loop half is
`ProcessorAllocationBudgetTest`, which asserts an allocation budget on every e2e run). Weekly,
it runs the load-tests allocation sweep and compares `ProcessorAlloc(KB)` at N=100 and N=500
against the newest committed baseline under `load-tests/results/`, warning at more than 35%
deviation. It compares allocation and not wall-clock because allocation is the only metric
the harness's own documentation certifies as stable (0.6% between runs; wall-clock has
varied 1.93x). Deviations warn rather than fail: the committed baselines were captured on a
different machine and JDK, so a hard gate would fail on environment rather than regression;
a warning that repeats weekly is the signal to capture a fresh baseline and investigate.
Sweep output uploads as an artifact either way.

---

## How the build verifies the annotation processor

The non-obvious part of `build.yml` is that the example project is the test fixture for the processor:

1. Some of the library's own tests are pure JUnit and never invoke `javac` — they assert classes, methods, and parsing logic in isolation. Most are not: 132 of the 307 test classes drive a compiler round-trip (counted 2026-09-26 as the classes using `ProcessorTestHarness` or a `JavaCompiler`), which is why they dominate the suite's runtime and why the fast/e2e split (below) is drawn on cost.
2. The integration and end-to-end tests compile annotated fixture sources in-memory via `ProcessorTestHarness`; they do not read `examples/basic/` (the `-Drun.integration.tests=true` gate that once tied them to it was dropped in 2026-04). Most of them are tagged `@Tag("e2e")`, so they run under `mvn test -Pe2e` — which CI does on every leg — rather than under a plain local `mvn test`. See `docs/TESTS.md`.
3. `reset-ai-files.sh` is what makes step 14 a meaningful test: without it, the verification steps would pass even if the processor wrote nothing, because the files would still hold content from a previous run.
4. The `@AIAudit` grep step exists because "file is non-empty" is too weak — a partially broken processor can still emit headers and frontmatter.

Maven, Gradle, and the Windows/macOS `cross-platform` job all call the same `.github/actions/verify-generated-files` composite action, so any platform-specific or OS-specific output difference (e.g. a Gradle-only file path bug, or a Windows CRLF regression) is caught.
