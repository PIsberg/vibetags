# GitHub Actions Workflows

This document describes what happens during CI builds in `.github/workflows/`. Eleven workflows: seven run on push, pull request, schedule, or release; mutation testing runs only when someone asks for it; the demo recording runs when the code it demonstrates changes; and the two AI-backed workflows (Inquisitor, Instruction Evals) run on pull requests but skip themselves, loudly, when the `ANTHROPIC_API_KEY` secret is absent.

## Overview

| Workflow | File | Trigger |
|---|---|---|
| Build and Test | `build.yml` | Push to `main`/`master`/`feature/*`/`fix/*`, PRs to `main`/`master` |
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

All jobs run on `ubuntu-latest` and start with the StepSecurity `harden-runner` action in `audit` mode, which records every outbound network call. The default token permission for every workflow is `contents: read`; jobs that need more (e.g. CodeQL writes `security-events`) escalate explicitly.

All third-party actions are pinned by full commit SHA with the version as a trailing comment — required by the OpenSSF Scorecard `Pinned-Dependencies` check.

---

## 1. Build and Test (`build.yml`)

The main CI workflow. Jobs run in parallel except `load-tests`, which waits on `build-maven`.

### Job: `build-maven`

Matrix over **JDK 21, 25, 26** (Temurin distribution, Maven dependency cache). JDK 21 is the minimum supported version; the rest are forward-compatibility checks. Steps:

1. **Harden runner** — egress audit.
2. **Checkout**.
3. **Set up JDK** — installs Temurin and primes the `~/.m2/repository` cache keyed on `pom.xml`.
4. **Install VibeTags Annotations** — `cd vibetags-annotations && mvn install -B`. Installs the zero-dependency annotations jar into the local Maven repo first, because `vibetags/pom.xml` declares it as a regular `<dependency>`.
5. **Build VibeTags Library** — `cd vibetags && mvn clean install -B`. Compiles the annotation processor, runs unit tests, and installs the artifact into the local Maven repo so the example project can resolve it. PMD, SpotBugs (with the Find Security Bugs detectors attached) and CPD are JDK-independent, so they run only on the JDK 21 leg (`-Dmaven.pmd.skip=true -Dspotbugs.skip=true` is passed on the other JDKs) to avoid repeating identical analysis ~4×. Error Prone still runs on every JDK because it is a compiler plugin and is JDK-sensitive, and it
carries NullAway with it — nullability is checked at `ERROR` on every matrix JDK, so a
`@Nullable` that stops being honoured fails the build rather than producing a warning nobody reads.

6b. **Verify VibeTags' Own Guardrails Are Current** — `mvn clean compile -Pself-annotate
`-Dvibetags.selfcheck=true`, JDK 21 only. The repo dogfoods its own guardrails, and until now
nothing checked that the committed `CLAUDE.md` / `GEMINI.md` / `.claudeignore` / `.claude/rules`
matched what the processor writes. They had drifted. The flag turns the self-annotate profile into
check mode, which fails on any would-be write, so the drift is a red build rather than something
the next person to run the profile by hand discovers. JDK 21 only because it compares file content,
which is JDK-independent.

   Running it **locally** can report `vibetags/CLAUDE.md` as out of date when CI does not. That file
   is gitignored, so a fresh checkout has none and the file-existence opt-in means nothing creates
   one — verified by deleting it and running the full suite, which leaves it absent. On a machine
   that has run `-Pself-annotate` before, it exists, and a test run then rewrites it. Regenerate
   (`mvn compile -Pself-annotate`) before checking, or delete the file.
6. **Install VibeTags BOM** — `cd vibetags-bom && mvn install -B`. Installs `se.deversity.vibetags:vibetags-bom` (pom-only) into the local Maven repo. Required because `example/pom.xml` imports the BOM via `<dependencyManagement>` to resolve `vibetags-annotations` and `vibetags-processor` versions, and the BOM has to be resolvable before step 8 runs.
6b. **Build VibeTags CLI** — `cd vibetags-cli && mvn clean install -B`. Runs after the processor install because the CLI consumes `vibetags-processor` as a library (`ServiceRegistry`, marker constants); its `InitCommandTest`/`DoctorCommandTest` are the gate on `init`/`doctor` behaviour. The `cross-platform` job repeats this build, which is the only place the CLI's filesystem behaviour runs on Windows and macOS path separators.
7. **Reset AI Config Files** — `cd example && bash reset-ai-files.sh`. Truncates every generated AI config file in `example/` to zero bytes and removes all granular rule files under `.cursor/rules/`, `.trae/rules/`, `.roo/rules/`. The files themselves are kept (their existence is the opt-in signal for the processor), but their content is cleared so the next compile must regenerate everything from scratch.
8. **Build Example Project** — `cd example && mvn clean compile -B -Dvibetags.log.path=../vibetags.log`. This is the only step that triggers `AIGuardrailProcessor` — it runs during `javac` of the example, sees the existing (now-empty) AI config files, and writes generated content back into them. The processor log is redirected to the repo root.
9. **Run Full Test Suite (VibeTags Library, incl. e2e)** — `cd vibetags && mvn test -B -Pe2e`. This is no longer the redundant second pass it used to be. Step 5's `mvn install` runs the fast tier only, because plain `mvn test` skips the 52 classes tagged `@Tag("e2e")`; `-Pe2e` adds them back, so this step is the only place the whole suite runs and the only one that can call the branch green. See `docs/TESTS.md` for what is tagged and why.
10. **Verify Generated AI Config Files** — delegates to the local composite action `.github/actions/verify-generated-files` (`working-directory: example`), which checks that every expected file under `example/` exists and is non-empty. Failure means the processor either skipped a platform or wrote nothing. Covered files include `.cursorrules`, `CLAUDE.md`, `.aiexclude`, `AGENTS.md`, `QWEN.md`, `gemini_instructions.md`, `.github/copilot-instructions.md`, `llms.txt`, `llms-full.txt`, `.codex/config.toml`, `.codex/rules/vibetags.rules`, `CONVENTIONS.md`, `.aiderignore`, granular rule files for `PaymentProcessor` / `DatabaseConnector` under `.cursor/rules/`, `.trae/rules/`, `.roo/rules/`, the AI PR-reviewer configs `.coderabbit.yaml` / `.pr_agent.toml` / `ellipsis.yaml`, the context-packer ignore files `.repomixignore` / `.gitingestignore` / `.gptignore` / `.ghostcoderignore` / `.piecesignore`, and the `.void/rules.md` and `.roomodes` editor/mode files. The same composite action step also runs the `@AIAudit` content check described next — it is one step in the workflow YAML, not two.
11. **Verify @AIAudit Content** — the second step inside the composite action greps each generated file for the platform-specific phrasing of the audit section (e.g. `MANDATORY SECURITY AUDITS` in `.cursorrules`, `audit_requirements` in `CLAUDE.md`, `CONTINUOUS AUDIT REQUIREMENTS` in `gemini_instructions.md`). This catches a class of regression where the file is non-empty but the `@AIAudit` rendering has silently broken for one platform.
12. **Upload coverage to Codecov** — only on the JDK 21 matrix leg, to avoid duplicate uploads. Reads `vibetags/target/site/jacoco/jacoco.xml`. Passes `fail_ci_if_error: false`, so a Codecov outage doesn't fail the build.
13. **Upload failure logs** — `if: failure()` only. Keeps `vibetags.log` (the processor's diagnostic channel) and the surefire reports as artifacts, because a CI-only failure whose log died with the runner has to be reproduced instead of read.

The generated-file and `@AIAudit` verification logic used to be duplicated inline in both `build-maven` and `build-gradle`; it now lives once in `.github/actions/verify-generated-files/action.yml` (a local composite action, `working-directory` input defaulting to `example`) and is invoked by both jobs — plus `cross-platform` (see below) — so the three call sites can never drift out of sync.

### Job: `cross-platform`

Matrix over **`windows-latest`, `macos-latest`** (`fail-fast: false`), JDK 21, `shell: bash`. The main matrix only runs on Linux, but the processor's file handling is OS-sensitive — path separators, CRLF line endings, the marker-aware `GuardrailFileWriter`, and `root.relativize()`. This job installs `async-test-lib` and the annotations jar, builds the library, installs the BOM, resets and rebuilds the example project the same way `build-maven` does, and runs the library's self-contained unit tests (`cd vibetags && mvn test -B`) on Windows and macOS. It then calls the same `.github/actions/verify-generated-files` composite action used by `build-maven`/`build-gradle`, so a Windows- or macOS-only regression in the generated output is caught here too — previously this job only ran unit tests and did not build or verify the example project. It uses the default `JAVA_HOME` (the Linux-only `JAVA_HOME_21_X64` does not exist on Windows or arm64 macOS) and omits the `harden-runner` step, which only supports Linux runners. On failure it uploads `vibetags.log` and the surefire reports, same rationale as `build-maven` step 13 — the OS-specific file-handling paths are exactly why this job exists, so their evidence must survive it.

### Job: `load-tests`

Single JDK 21 leg, `needs: build-maven`. Steps:

1. **Harden runner**, **checkout**, **set up JDK 21** (Maven cache).
2. **Install VibeTags annotations** — `cd vibetags-annotations && mvn install -B`.
3. **Install VibeTags processor** — `cd vibetags && mvn install -DskipTests -B`.
3. **Run stress tests** — `cd load-tests && mvn test -B -Dtest="AnnotationVolumeStressTest,ConcurrentBuildTest" -Dstress.max.classes=500`. Two scenarios: scaling annotation volume up to 500 classes, and concurrent builds.
4. **Upload stress-test results** — `if: always()`, so artifacts upload even on failure. Glob `load-tests/target/stress-results-*.txt`, retained as `stress-results-${{ github.run_id }}`.

### Job: `build-gradle`

Mirror of `build-maven` but with Gradle. Matrix over **JDK 21, 25, 26**. Differences:

- Uses Gradle dependency cache.
- Gradle wrappers (`gradlew`/`gradlew.bat`, Gradle 8.8) are committed in `vibetags-annotations/`, `vibetags/`, and `example/` — copied from `vibetags/`'s pre-existing wrapper. CI invokes `./gradlew` in every subproject; there is no on-the-fly `gradle wrapper` generation step.
- Annotations build: `cd vibetags-annotations && ./gradlew clean build publishToMavenLocal --no-daemon`. Runs first because the processor depends on it.
- Library build: `cd vibetags && ./gradlew clean build publishToMavenLocal --no-daemon`.
- BOM install: `cd vibetags-bom && mvn install -B`. The BOM is Maven-only; Gradle reads it from `mavenLocal()` when resolving `platform('se.deversity.vibetags:vibetags-bom:...')` in `example/build.gradle`. This step runs after the library build and before the example build.
- Example build: `cd example && ./gradlew clean build -PcompilerArgs="-Avibetags.log.path=../vibetags.log" --no-daemon`.
- Kotlin example build (JDK 21 leg only): `cd example-kotlin && ./gradlew clean build --no-daemon`, then greps the regenerated `CLAUDE.md` / `.cursorrules` for the annotated Kotlin elements — the proof that kapt actually ran the processor. Gated to 21 because the Kotlin Gradle plugin trails new JDK releases and the example pins a 21 toolchain anyway.
- Groovy example build (JDK 21 leg only, same shape): joint-compilation stubs with `javaAnnotationProcessing = true`; greps prove the processor saw the Groovy class.
- Scala example build (JDK 21 leg only): asserts the feature *and* the limitation — the annotated Java class must appear in the generated files, and the annotated Scala class must **not** (`! grep`), because scalac has no JSR 269 support. If the negative assertion ever fails, the docs' support matrix is wrong, not the build.
- Tests use `cd vibetags && ./gradlew test --no-daemon`.
- Codecov reads `vibetags/build/reports/jacoco/test/jacocoTestReport.xml`, uploads under flag `unittests-gradle`, and passes `fail_ci_if_error: false`.

The same `.github/actions/verify-generated-files` composite action runs after the Gradle build, so any divergence between Maven and Gradle output paths is caught.

Mutation testing used to be a job here. It now lives in its own manually triggered workflow — see section 7.

### Job: `locked-files`

Pull requests only. Dogfood of the shipped `action/locked-files`: installs the annotations and
processor, then invokes the action with `build-command: mvn -B -q -f vibetags/pom.xml clean
compile -Pself-annotate`, which regenerates `.vibetags-locks` at the PR head (the action touches
the file first; file presence is the opt-in). The action then intersects the PR diff with the
locked line ranges and fails when a diff touches an `@AILocked` element, naming the element and
its recorded reason. The same guard consumers get, applied to the repository that ships it.

### Job: `diagrams`

Regenerates the code-karta architecture diagrams (`sh tools/generate-architecture-diagrams.sh`)
and fails on `git diff` against the committed SVGs under `docs/diagrams/codekarta/`. A generated
diagram that only regenerates when someone remembers is documentation, not telemetry; this job
makes "the picture matches the code" a property of every commit. Regeneration is byte-idempotent
(verified 2026-08-15, two consecutive runs with zero diff), which is what makes a byte-level
drift check a stable gate. On failure the fresh SVGs upload as the `regenerated-diagrams`
artifact so the committer can take them without reproducing the toolchain.

---

## 2. CodeQL (`codeql.yml`)

Static-analysis security scanning over Java sources.

- Triggers: push/PR to `main`, weekly cron `0 0 * * 1` (Mondays midnight UTC).
- Job permissions: `actions: read`, `contents: read`, `security-events: write`.
- Steps: harden runner → checkout → `codeql-action/init` (language `java`) → set up JDK 21 → `cd vibetags && mvn clean install -B -q` → `cd vibetags-bom && mvn install -B -q` (the example imports the BOM, so it must be resolvable before the next step) → `cd example && mvn clean compile -B -q` (so CodeQL traces both library and consumer code) → `codeql-action/analyze` with category `/language:java`.
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

Triggered when a GitHub Release is created.

- Job: `publish-maven-central`, JDK 21.
- Sets up Maven with `server-id: central` and exports `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` for the deploy step.
- **Import GPG key** — pipes `secrets.GPG_PRIVATE_KEY` into `gpg --batch --import`, then prints key fingerprints.
- **Sign and deploy annotations** — `cd vibetags-annotations && mvn clean deploy -P central-publish,sign-artifacts -B -Dgpg.passphrase="${{ secrets.GPG_PASSPHRASE }}"`. Runs first; the processor depends on `vibetags-annotations` so it must be in Central before the processor jar that references it is published.
- **Build, sign, and deploy processor** — `cd vibetags && mvn clean deploy -P central-publish,sign-artifacts -B -DskipTests -Dgpg.passphrase="${{ secrets.GPG_PASSPHRASE }}"`. Tests are skipped here because they ran on every push that led to the tagged commit; this step only signs and uploads.
- **Build, sign, and deploy CLI** — `cd vibetags-cli && mvn clean deploy -P central-publish,sign-artifacts -B -Dgpg.passphrase="${{ secrets.GPG_PASSPHRASE }}"`. Runs after the processor because it consumes it as a library; its 15 tests run here as a last gate (fast, filesystem-only).
- **Sign and deploy BOM** — `cd vibetags-bom && mvn clean deploy -P central-publish,sign-artifacts -B -Dgpg.passphrase="${{ secrets.GPG_PASSPHRASE }}"`. Same Sonatype Central + GPG profiles as the processor pom; publishes `se.deversity.vibetags:vibetags-bom:<version>` (pom-only) so consumers can import it. Runs after annotations and processor have been deployed.

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
- Steps: harden runner → checkout → set up JDK 21 (Maven cache) → install `async-test-lib` → `cd vibetags-annotations && mvn install -B` → `cd vibetags && mvn -B -Pmutation -Pe2e test-compile org.pitest:pitest-maven:mutationCoverage`.
- **`-Pe2e` is load-bearing, not optional.** `pitest-maven` parses surefire's configuration, and surefire carries `<excludedGroups>${vibetags.test.excludedGroups}</excludedGroups>`, which defaults to `e2e`. Without `-Pe2e`, PIT runs the fast tier and scores the project as if 55 of its 132 test classes did not exist. Measured on `main` over the seven classes PIT reported as entirely uncovered: 19% line coverage and 16 of 211 mutants killed without it, 88% and 142 of 211 with it. The code was never untested, only unmeasured. Cost is roughly +57% wall-clock on that scope, which is affordable for a `workflow_dispatch`-only job.
- The `mutation` Maven profile (in `vibetags/pom.xml`) pulls in `pitest-maven` and `pitest-junit5-plugin` and is otherwise inactive — it only applies when `-Pmutation` is passed explicitly, so normal `mvn install` / `mvn test` runs are unaffected. It sets no `mutationThreshold`, so the job goes red only on a real failure, not on a low score.
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
exit code. Runs only when the `ANTHROPIC_API_KEY` secret exists; otherwise every step skips and
the summary says SKIPPED, which is not a pass. The model is pinned by exact ID in the workflow;
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

## How the build verifies the annotation processor

The non-obvious part of `build.yml` is that the example project is the test fixture for the processor:

1. Some of the library's own tests are pure JUnit and never invoke `javac` — they assert classes, methods, and parsing logic in isolation. Most are not: 63 of the 131 test classes drive a compiler round-trip, which is why they dominate the suite's runtime and why the fast/e2e split (below) is drawn on cost.
2. The integration and end-to-end tests compile annotated fixture sources in-memory via `ProcessorTestHarness`; they do not read `example/` (the `-Drun.integration.tests=true` gate that once tied them to it was dropped in 2026-04). Most of them are tagged `@Tag("e2e")`, so they run under `mvn test -Pe2e` — which CI does on every leg — rather than under a plain local `mvn test`. See `docs/TESTS.md`.
3. `reset-ai-files.sh` is what makes step 8 a meaningful test: without it, the verification steps would pass even if the processor wrote nothing, because the files would still hold content from a previous run.
4. The `@AIAudit` grep step exists because "file is non-empty" is too weak — a partially broken processor can still emit headers and frontmatter.

Maven, Gradle, and the Windows/macOS `cross-platform` job all call the same `.github/actions/verify-generated-files` composite action, so any platform-specific or OS-specific output difference (e.g. a Gradle-only file path bug, or a Windows CRLF regression) is caught.
