---

description: "Task list for routing test-code guardrails to TESTING.md"
---

# Tasks: Route Test-Code Guardrails to TESTING.md

**Input**: Design documents from `/specs/001-test-annotations-testing-md/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The repository's standing rule is that every behaviour change ships with a
test, written first and shown failing. Each test task says what it must see to fail.

**Organization**: Grouped by user story. US1 and US2 are both P1; US3 and US4 are P2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 to US4, from spec.md

## Path Conventions

- `MAIN/` = `vibetags/src/main/java/se/deversity/vibetags/processor/`
- `TEST/` = `vibetags/src/test/java/se/deversity/vibetags/processor/`
- Run Maven from `vibetags/`, from PowerShell on Windows, never piped through `tee`/`tail`/
  `Select-Object` (the pipe eats the exit code).

## Rules that apply to every task

- **Never edit `AIGuardrailProcessor.generateFiles()`**. It is `@AILocked`. If a task cannot be
  done without editing it, stop and escalate to the owner (research R3).
- **Never reorder or remove entries in `GuardrailAnnotations.ALL`** (locked, append-only).
- Before editing a class listed under `<scoped_rules>` in the root `CLAUDE.md`, read its rule file
  in `.claude/rules/` with `ctx_read` (path-scoped rules do not auto-load in this setup).
- Logging follows `docs/LOGGING.md`: `domain.event key=value`, `reason=` on every `.skip`.
- One commit per task or tight group; messages say which failure the change prevents and how it
  was verified.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: a known-green baseline, so a later red is attributable to this change.

- [x] T001 Confirm the branch base: run `git log --oneline origin/main..HEAD` and `git status --short` in the repo root; record in the PR notes that the branch sits on `simplify/whole-codebase` (17 commits) and that `CLAUDE.md` plus the Spec Kit install were dirty before work began. Do not stage those files.
- [ ] T002 Build and install in order: `vibetags-annotations` (`mvn install`), `vibetags` (`mvn clean install`), `vibetags-bom` (`mvn install`); then run `mvn test -Pe2e` from `vibetags/` and record the pass/fail/skip counts as the baseline. A red baseline is reported, not worked around.
- [x] T003 [P] Read the scoped rule files this feature touches and note any constraint that contradicts plan.md: `.claude/rules/se-deversity-vibetags-processor-internal-ModuleSidecar.md`, `...-internal-AnnotationCollector.md`, `...-internal-ServiceRegistry.md`, `...-internal-content.md`, `...-internal-content-PlatformRenderer.md`, `...-model.md`, `...-internal-BuildFingerprint.md`, `...-AIGuardrailProcessor.md`
- [x] T004 [P] Resolve research R1's open check: find what source set the KSP front end reports by reading `vibetags-ksp/src/main` for its use of `ModuleIdentity`/`ModuleSidecar.scopedModuleId`; write the answer into `specs/001-test-annotations-testing-md/research.md` under R1. If it reports a test source set, add a KSP test task to Phase 7.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the `testing` service exists and the builder knows whether a round is a test round.
No output changes for any existing project at the end of this phase.

**CRITICAL**: no user story work starts until this phase is green.

- [x] T005 Add failing unit tests for `ModuleIdentity.isTestSourceSet()` in `TEST/internal/ModuleIdentityTest.java` (create if absent): true for `test`, `integrationTest`, `functionalTests`, `testFixtures`; false for `main`, `jmh`, `generated`, `latest`, `contest`-style names that merely contain "test" in lower case without the suffix rule, and blank. Must fail to compile first.
- [x] T006 Implement `isTestSourceSet()` on `MAIN/internal/ModuleIdentity.java` per research R1 (equals `test`, ends with `Test` or `Tests`, or equals `testFixtures`); T005 goes green.
- [x] T007 Add `TESTING("testing")` to `MAIN/internal/content/Platform.java`
- [x] T008 Register the service in `MAIN/internal/ServiceRegistry.java`: `map.put("testing", root.resolve("TESTING.md"))` in `buildServiceFileMap()` and `"testing"` in `OPT_IN_KEYS`. Use the registry's own opt-in predicate (`isOptedIn`), never a bare `Files.exists`.
- [x] T009 Add `"testing"` to the two hardcoded service-key sets: `expectedKeys` in `TEST/AIGuardrailProcessorProcessTest.java` and the matching `Set.of(...)` in `TEST/AIGuardrailProcessorUnitTest.java`
- [x] T010 Add a `testRound` flag to `MAIN/internal/content/RenderingContext.java` (plain `boolean`, accessor plus an `asTestRound()` copy method in the style of `asSafetyDigest()`); no compiler API import (`TEST/ArchitectureRulesTest.java` must stay green).
- [x] T011 Add a source-set carrier to `MAIN/internal/AnnotationCollector.java` (setter and `isTestRound()` getter, default false), and set it in `AIGuardrailProcessor.process()` immediately after the existing `moduleIdentity = ModuleRootResolver.fromRound(...)` assignment in `MAIN/AIGuardrailProcessor.java`. `process()` must still return false (`TEST/AIGuardrailProcessorProcessTest.java`).
- [x] T012 Create `MAIN/internal/content/platforms/TestingRenderer.java` implementing `PlatformRenderer`: returns `null` when `context.testRound()` is false; otherwise writes a two-line preamble (these guardrails apply to test code; safety guardrails for test code are in the always-loaded files) and delegates the body to the Codex/`AGENTS.md` Markdown renderer, following `FirebaseRenderer`'s delegation pattern. Wire `case TESTING:` in `MAIN/internal/content/PlatformRendererRegistry.java`. If the Codex delegate needs a `case TESTING:` in any annotation formatter, pick a delegate that does not, and record the choice in research R7.
- [x] T013 In `MAIN/internal/GuardrailContentBuilder.java`, make `build()` pass `context.asTestRound()` when `collector.isTestRound()` is true. No routing yet.
- [x] T014 Invariant 4 guard, test first: extend `TEST/AgentsMdSoleFallbackTest.java` with a case where `TESTING.md` and `AGENTS.md` are the only files present and assert `AGENTS.md` is still written as the sole AI config file. Show it failing if it does, then exclude `testing` from whatever "is an AI config file" set the sole-fallback rule uses in `MAIN/internal/ServiceRegistry.java` (or the class that rule lives in).
- [ ] T015 Run `mvn test -Pe2e` from `vibetags/`: the count of failures must equal T002's baseline. Then run `git status --short examples/` after `cd examples/basic && rm -f .vibetags-cache && mvn clean compile`: no generated file may differ. This is the first FR-002 proof.

**Checkpoint**: `testing` is a registered, inert service; every existing output is byte-identical.

---

## Phase 3: User Story 1 - Test-code guardrails land in TESTING.md (Priority: P1) MVP

**Goal**: with `TESTING.md` present, a test round writes its non-safety guardrails there and
keeps them out of the always-loaded files.

**Independent Test**: one annotated main class, one annotated test class, empty `TESTING.md` and
`CLAUDE.md`; after a main round and a test round the test class is only in `TESTING.md`.

### Tests for User Story 1 (write first, show failing)

- [x] T016 [P] [US1] Partition unit test in `TEST/model/GuardrailModelViewsTest.java`: build a model with one reference for each of the 44 annotations (use `TEST/GuardrailModels.java` helpers); assert `safetyOnly()` and `withoutSafety()` are disjoint, their union equals the full model's `elementIds()`/`totalAnnotatedReferences()`, and `safetyOnly()` holds exactly `@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure`. Iterate over `GuardrailAnnotations.ALL` so a 45th annotation fails the test until classified.
- [x] T017 [P] [US1] Contract test `TEST/ServiceRoutingContractTest.java` (fast tier): iterate every key of `ServiceRegistry.buildServiceFileMap(tmp)` and assert `routesTestGuardrails(key)` against a pinned expected set. Derive the first expected set by running the predicate, then review each key by hand against contracts/testing-md-output.md before pinning; resolve research R5's `llms` question here and record it.
- [ ] T018 [US1] End-to-end test `TEST/TestingMdRoutingEndToEndTest.java` (`@Tag("e2e")`), modelled on `TEST/SourceSetIsolationEndToEndTest.java` for running a `src/main/java` round then a `src/test/java` round over one module root. Cases: (a) spec US1 scenario 1 for `CLAUDE.md`, `AGENTS.md`-style and one hash-marker aggregate such as `.cursorrules`; (b) adding a test annotation changes `TESTING.md` and no other routed file's main content; (c) removing the last test annotation empties the generated region and keeps the file; (d) hand-written text above and below the markers in `TESTING.md` is byte-identical afterwards; (e) a tests-only module (no main round, single sidecar) still routes, which is the path `mergeAcrossModules` skips; (f) research R8: a test round with no safety annotations overwrites a previously unrouted `CLAUDE.md` so no stale test entry remains. Assert on content, never on file existence.

### Implementation for User Story 1

- [x] T019 [US1] Add pure views `safetyOnly()` and `withoutSafety()` to `MAIN/model/GuardrailModel.java`. Name the six safety annotations by reusing the definition the safety digest already uses (find it via `RenderingContext.safetyDigest()` and `hasSafetyTierAnnotations()`); do not introduce a second list. Transitive (dependency) guardrails are excluded from `withoutSafety()`. T016 goes green.
- [x] T020 [US1] Add `routesTestGuardrails(String key)` to `MAIN/internal/ServiceRegistry.java` per contracts/testing-md-output.md (single file, `GuardrailFileWriter.getMarkersFor != null`, `PlatformRendererRegistry.mergeShapeFor == null`, not `isIgnoreService`, not `testing`, not a `*_safety` key, not the locks or `llms` keys). T017 goes green.
- [x] T021 [US1] Route in `MAIN/internal/GuardrailContentBuilder.java`: when `collector.isTestRound()` and `activeServices.contains("testing")`, render routed services against `model.safetyOnly()`, render `testing` against `model.withoutSafety()`, and everything else against the full model. Apply the same rule in `putRendered` for the implicit sidecars only if `routesTestGuardrails` says so. The `safetyDigest()` fluent mode and `withTransitiveAppendix` must behave as before when not routed. T018 (a) to (f) go green.
- [x] T022 [US1] Extend `TEST/MarkerInjectionTest.java` with a `TESTING.md` case: annotation text containing `<!-- VIBETAGS-END -->` on a test element must not end the generated region early.
- [x] T023 [US1] Run `mvn test -Pe2e -Dtest='TestingMdRoutingEndToEndTest,GuardrailModelViewsTest,ServiceRoutingContractTest,MarkerInjectionTest'` then the full `mvn test -Pe2e`; failures must equal the T002 baseline.

**Checkpoint**: MVP. Routing works in one build; toggling the file is not yet loss-proof (US2).

---

## Phase 4: User Story 2 - No TESTING.md, no change (Priority: P1)

**Goal**: absent file means today's bytes; creating or deleting the file never loses a guardrail
in any build shape.

**Independent Test**: build a fixture with and without the feature and compare every generated
file byte for byte; cross create/delete with main-only, tests-only and main-then-tests builds.

### Tests for User Story 2 (write first, show failing)

- [ ] T024 [US2] End-to-end test `TEST/TestingMdLifecycleEndToEndTest.java` (`@Tag("e2e")`). Cases: (a) no `TESTING.md`: after main and test rounds it does not exist, and every generated file equals a golden produced by the same sources on the T002 baseline jar (or, if that is impractical in-suite, equals the output of a run with routing force-disabled); (b) present, main round only after a full build: the `TESTING.md` generated region is unchanged; (c) present, test round only: main guardrails still in `CLAUDE.md`; (d) create `TESTING.md` then main round only: nothing lost, test entries still in `CLAUDE.md`; (e) delete `TESTING.md` then main round only: non-safety test guardrails are back in `CLAUDE.md`, no pointer, `TESTING.md` not recreated; this case must fail before T026; (f) toggling the file defeats the fingerprint short-circuit: assert the log has no `round.skip reason=fingerprint-match` on the round after a toggle; (g) the same full build twice changes 0 files on the second run.
- [x] T025 [P] [US2] Sidecar unit tests in `TEST/internal/ModuleSidecarTestingFallbackTest.java`: `~tfull~` round-trips through `save`/`loadFor`; a sidecar file carrying an unknown `~zzz~x` key still loads (older-reader behaviour); `mergeFor` picks the normal body when the transient flag is true and `~tfull~` when false; a sidecar with no `~tfull~` merges exactly as before in both states.

### Implementation for User Story 2

- [x] T026 [US2] In `MAIN/internal/ModuleSidecar.java`: add the `~tfull~<service>` key namespace (persisted, next to `~idx~`), `putUnroutedBody`, a transient never-persisted `testingOptedIn` flag set for every sidecar in `readAll(root)` via an `applyTestingOptInTo(root, sidecars)` helper modelled on `applyRootIndexModeTo` but using `ServiceRegistry.isOptedIn`, and in `mergeFor` substitute the `~tfull~` body when the flag is false. `mergeFor`'s `@AIContract` signature is unchanged. Also apply the substitution in `contributionsFor` only if a routed service can reach it (it should not: routed services all have markers).
- [x] T027 [US2] In `MAIN/AIGuardrailProcessor.java`: change `populateSidecarBodies` from `static` to an instance method with the same name and parameters so both call sites (`generateFiles`, `checkFiles`) stay byte-identical; when the round is routed, render the unrouted body for each routed service with a second `GuardrailContentBuilder` pass that ignores routing, and store it with `putUnroutedBody`. Verify with `git diff -U0` that no line inside `generateFiles()` changed, and run `python action/locked-files/check_locked_diff.py` with `PYTHONIOENCODING=utf-8` against `merge-base..HEAD`.
- [x] T028 [US2] Extend `TEST/CheckModeTest.java`: check mode reports a stale `TESTING.md` as drift, reports none on a tree a real routed build just produced, and agrees with generation after a delete-then-main-only build.
- [x] T029 [US2] Extend `TEST/PartialRoundGuardrailLossTest.java`: a partial test round with `TESTING.md` present writes nothing to `TESTING.md` or to any routed file, and says so in the log.
- [ ] T030 [US2] Run T024 and T025 green, then the full `mvn test -Pe2e` against the T002 baseline.

**Checkpoint**: both P1 stories hold; the feature is safe to opt into and out of.

---

## Phase 5: User Story 3 - Agents can find the test guardrails (Priority: P2)

**Goal**: each routed file tells an agent to read `TESTING.md` before touching test code.

**Independent Test**: build the US1 fixture; each routed file contains the pointer once; it is
absent when `TESTING.md` is absent or the module moved nothing.

- [x] T031 [US3] Add failing cases to `TEST/TestingMdRoutingEndToEndTest.java`: pointer text from contracts/testing-md-output.md appears exactly once in each routed file of a single-module build; zero times when `TESTING.md` is absent; zero times when the test code carries only safety annotations; in a two-module reactor, once inside each contributing module's `VIBETAGS-MODULE` sub-markers and not in a module without test guardrails.
- [x] T032 [US3] In `MAIN/internal/GuardrailContentBuilder.java`, append the pointer constant to a routed service's body when the round is routed and `model.withoutSafety()` is non-empty. Place it inside the generated region in a form each marker family tolerates: a Markdown line for HTML-marker files, a `#` comment line for hash-marker files; never inside an XML-like element such as `<project_guardrails>`. Keep the constant in one place and reference it from the test.
- [x] T033 [US3] Confirm the `~tfull~` body never contains the pointer (extend `TEST/internal/ModuleSidecarTestingFallbackTest.java` or T024 case (e)).

**Checkpoint**: moved guardrails are discoverable from every always-loaded file.

---

## Phase 6: User Story 4 - Safety guardrails on test code stay visible (Priority: P2)

**Goal**: the six safety annotations on test code never move and are never duplicated.

**Independent Test**: one test class with a safety annotation, one with a non-safety annotation,
one with both; check where each lands.

- [x] T034 [US4] Add cases to `TEST/TestingMdRoutingEndToEndTest.java` for spec US4 scenarios 1 to 3, once per safety annotation (parameterized over the six): the safety guardrail's rendering in `CLAUDE.md` is byte-identical to the rendering without `TESTING.md`, and the element path does not appear in `TESTING.md` for a safety-only class. For a class carrying both kinds, its path appears in both files, each with only its own kind.
- [x] T035 [P] [US4] FR-009 cases in `TEST/TestingMdRoutingEndToEndTest.java`: with `TESTING.md` present, an `@AIIgnore` on a test file still yields its glob in `.cursorignore` and one other ignore file; a JSON or YAML review config (for example `.coderabbit.yaml`) and a granular rule file under `.claude/rules/` for a test class are byte-identical with and without `TESTING.md`.
- [x] T036 [US4] Extend `TEST/GranularRulesEndToEndTest.java` (invariant 6): with a granular directory opted in and `TESTING.md` present, the aggregate's inline safety buckets still include test-code safety guardrails. Fix `MAIN/internal/GuardrailContentBuilder.java` if the indexed renderer variant and the `safetyOnly()` view interact badly.

**Checkpoint**: all four stories independently verified.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T037 Logging, test first: add contract cases in `TEST/internal/GuardrailFileWriterLogContractTest.java` (or a sibling `TestingRoutingLogContractTest.java` in the same package) for `testing.route sourceSet= routed= moved= kept=`, `testing.skip reason=not-test-round`, `testing.skip reason=no-test-guardrails`, `merge.testing.fallback service= module=`, and for the absence of any `testing.` event when `TESTING.md` is absent. Then emit them from `MAIN/internal/GuardrailContentBuilder.java` or `MAIN/AIGuardrailProcessor.java` (outside `generateFiles()`) and `MAIN/internal/ModuleSidecar.java`; document them in `docs/LOGGING.md`.
- [x] T038 [P] Multi-module test in `TEST/TestingMdRoutingEndToEndTest.java` or a new `TEST/MultiModuleTestingMdTest.java`: two modules with test guardrails produce one `TESTING.md` with two `VIBETAGS-MODULE` regions; building one module alone does not erase the other's region (FR-011). Add the research R9 case: a nested `module-a/TESTING.md` behaves like the root case and does not throw.
- [ ] T038a KSP routing test, added by T004 (research R1): in `vibetags-ksp/src/test`, run the KSP front end over a Kotlin source under `src/test/kotlin` with `TESTING.md` and `CLAUDE.md` present and assert a non-safety guardrail lands in `TESTING.md` and not in `CLAUDE.md`. T004 established by reading, not by running, that `KspElements.getFileObjectOf` hands `ModuleRootResolver` the real path; this task is what executes it. If the KSP test harness cannot place sources under a `src/test` path, say so and open an issue.
- [ ] T039 Fixture `examples/basic`: add `examples/basic/src/test/java/` with one class carrying a non-safety annotation and one carrying `@AILocked`, add the JUnit-free test-compile wiring the pom needs, `touch examples/basic/TESTING.md`, add `TESTING.md` to `AI_FILES` in `examples/basic/reset-ai-files.sh`, then `rm -f .vibetags-cache && mvn clean test-compile` and commit the regenerated files. `TEST/ExampleOptInCoverageTest.java` must pass.
- [ ] T040 Fixture `examples/multimodule`: `touch examples/multimodule/TESTING.md`, `rm -f .vibetags-cache`, regenerate with `mvn clean verify` (not `compile`), commit. Read the new active-service count from `examples/multimodule/vibetags.log` and update both `expected=` values in `.github/workflows/build.yml` if they moved. Do the same for `examples/gradle-multimodule` only if `examples/INDEX.md` says that fixture should carry it.
- [ ] T041 CI-only gate: add `TESTING.md` with a content assertion (a known test-class path inside the markers) to `.github/actions/verify-generated-files/action.yml`, then extract every `run: |` block to a script in the scratchpad and execute it with `bash -e -o pipefail` from `examples/basic`. Report the actual exit code.
- [x] T042 [P] Docs, all in this change: a `TESTING.md` row in `docs/PLATFORMS.md`; opt-in, routing rule, mixed-round limit and the toggle behaviour in `docs/PROCESSOR.md`; the `~tfull~` key and test-sidecar states in `docs/MULTI-MODULE.md`; the new test classes in `docs/TESTS.md`; an entry in `docs/CHANGELOG.md`; the `touch TESTING.md` line in `USAGE.md`; Quick Setup and `references/output-files.md` in `.claude/skills/vibetags-usage/`; a "decide `routesTestGuardrails` for the new key" step in `.claude/skills/add-platform/SKILL.md`. No em-dashes, no curly quotes. Do not change the README AI-platform count (research R7); run `ProjectFactsConsistencyTest`.
- [x] T043 [P] Evaluate the one VibeTags self-annotation candidate from plan.md (`ServiceRegistry.routesTestGuardrails`, the include/exclude asymmetry). If added, run `mvn compile -Pself-annotate` from `vibetags/` and commit the regenerated guardrail files; if not, say why in the PR body. Do not add others without a fact the code does not already state.
- [ ] T044 Full gates, in order, reporting each as passed, failed or not run: `mvn -B verify -Pe2e` from `vibetags/` (adds PMD, CPD, SpotBugs, Error Prone; check the JDK matches CI before trusting a red PMD); `python action/locked-files/check_locked_diff.py` with `PYTHONIOENCODING=utf-8`; `git add` then `pre-commit run --all-files`; the quickstart.md walk in `examples/basic` and `examples/multimodule`.
- [ ] T045 Measure the cost instead of estimating it: run the `load-tests/` annotation-volume sweep with and without `TESTING.md` per the `load-tests` skill and put the two numbers in the PR body. If the harness cannot express a test source set, say so and open an issue instead of guessing.
- [ ] T046 Run the consumer regression sweep per the `consumer-regression-suite` skill (native Bash in the background, not `ctx_shell`), and report which of the five consumers were built and which were not.
- [ ] T047 Open the PR against `main` (do not merge). Body: the failure it prevents, how it was verified, "stacked on the simplify/whole-codebase PR, review the last commits", the FR-008 amendment, and links to a GitHub issue for each item left behind: mixed main-and-test rounds are never routed; per-module nested `TESTING.md` is undocumented; `.specify/memory/constitution.md` is unfilled; KSP source-set behaviour if T004 found a gap. Push, then follow CI to green: download the `regenerated-diagrams` artifact for the new renderer class and commit the five SVGs under `docs/diagrams/codekarta/`.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none. T003 and T004 run in parallel with T002.
- **Foundational (Phase 2)**: after Setup. T005 then T006; T007 then T008, T009, T012; T010 then
  T011 then T013; T014 after T008; T015 last.
- **US1 (Phase 3)**: after Foundational. T016 and T017 in parallel; T018 after them. T019 then
  T020 then T021; T022 and T023 last.
- **US2 (Phase 4)**: after US1 (its toggle cases need routing to exist). T024 and T025 first;
  T026 then T027; T028 and T029 after T027.
- **US3 (Phase 5)**: after US1. Independent of US2 except T033, which needs T026.
- **US4 (Phase 6)**: after US1. Independent of US2 and US3.
- **Polish (Phase 7)**: T037 and T038 after US2; T039 to T041 after US1 to US4; T042 and T043 any
  time after US4; T044 to T047 strictly last and in order.

### User Story Dependencies

```text
Setup ─▶ Foundational ─▶ US1 ─┬─▶ US2 ─┐
                              ├─▶ US3 ─┼─▶ Polish
                              └─▶ US4 ─┘
```

US1 is not independent of Foundational, and US2's toggle half is not independent of US1: a
"no change" guarantee cannot be tested against a feature that does not exist yet. US2's
byte-identity half is already proven at T015.

### Same-file contention (not parallel even across stories)

- `MAIN/internal/GuardrailContentBuilder.java`: T013, T021, T032, T036, T037
- `TEST/TestingMdRoutingEndToEndTest.java`: T018, T031, T034, T035, T038
- `MAIN/internal/ModuleSidecar.java`: T026, T037
- `MAIN/internal/ServiceRegistry.java`: T008, T014, T020

---

## Parallel Example: User Story 1

```text
# Together, different files, no shared state:
Task T016: partition unit test in TEST/model/GuardrailModelViewsTest.java
Task T017: routed-service contract test in TEST/ServiceRoutingContractTest.java

# Then, sequentially (each depends on the previous):
Task T019 -> T020 -> T021
```

---

## Implementation Strategy

### MVP First

1. Phases 1 and 2, ending at T015 (byte-identity proven before any routing exists).
2. Phase 3 (US1). Stop and validate with quickstart.md "Single module".
3. Do not ship the MVP alone: without US2 a user who deletes `TESTING.md` and runs `mvn compile`
   loses non-safety test guardrails until the next test compile. US1 plus US2 is the smallest
   releasable unit.

### Incremental Delivery

US1 + US2 (releasable) -> US3 (discoverability) -> US4 (mostly proof; the behaviour arrives with
US1's views) -> Polish. One PR for the whole feature: every phase touches
`GuardrailContentBuilder` and the same end-to-end test, so sibling PRs would conflict on each
merge.

---

## Notes

- A test that passes before its implementation task is a finding: either the behaviour already
  existed or the test does not detect it. Break the fix once and confirm the red where
  failing-first was not possible.
- Assert content, never existence: an opted-in file that exists and holds only a header is the
  defect shape this repository has shipped before.
- Report skipped and not-run gates separately from passed ones.

---

## Progress log (2026-09-20)

Unticked tasks that are partly done, and what is left of each:

- **T002**: baseline `mvn test -Pe2e` run and recorded (3127 run, 0 failures, 0 errors, 3 skipped).
  The three `mvn install` steps were not run; the suite does not need them, the example rebuilds do.
- **T015**: full suite green after Phase 2 (3155 run, 0 failures, 3 skipped). The
  `examples/basic` rebuild and byte comparison was **not run** (needs the processor installed).
- **T018**: cases (a), (b), (d), (e), (f) written and green. Case (c), removing the last test
  annotation, is `@Disabled` and **escalated**: a round that saw no annotations never saves its
  sidecar, so the previous one keeps contributing. The guard is inside the locked
  `generateFiles()`, and the same scenario with no `TESTING.md` leaves the stale guardrail in
  `CLAUDE.md` (measured). Only (a) was failing-first; with routing switched off, (a), (b), (e) and
  (f) fail, which is the evidence the rest detect it.
- **T024**: cases (b), (d), (e), (f), (g) written and green; (e) was red before T026/T027. (f) is
  asserted on the files after a bare toggle with identical sources, both directions, rather than
  on the absence of a `round.skip` log line; it passed first time because the fingerprint already
  folds in the active-service set. Case (a), byte-identity against a golden from the baseline jar,
  is **not written**. One added case is `@Disabled` as a **known limit needing an owner
  decision**: with unannotated main sources, deleting `TESTING.md` and building main only leaves
  the test guardrails out until the next test compile (single sidecar, merge not reached).
  Admitting a lone sidecar to the merge, as `isRootIndexMode` does, was tried and did not change
  the result; reverted.
- **T028**: done as a new class, `TestingMdCheckModeTest` (`CheckModeTest`'s helpers are
  hardwired to `src/main`). Passed first time.
- **T031**: done. Single-module cases in `TestingMdRoutingEndToEndTest` (three failing-first),
  the reactor case in `MultiModuleTestingMdTest` (passed first time).
- **T035**: done. The `@AIIgnore` case and the granular rule file byte-identity case were
  already green (`TestingMdSafetyEndToEndTest`); the review-config case
  (`aReviewConfigIsByteIdenticalWithAndWithoutTestingMd`, `.coderabbit.yaml` and `greptile.json`)
  passed first time and was checked for detection: widening `routesTestGuardrails` to accept a
  file with markers **or** a merge shape turns exactly that one case of the 15 red, so nothing
  else in the suite covered it.
- **T034** lives in a new class, `TestingMdSafetyEndToEndTest`, not in the routing class. Passed
  first time; with `@AISecure` removed from `GuardrailAnnotations.SAFETY` exactly its two
  `AISecure` cases fail.
- **T036**: done as a case in `TestingMdSafetyEndToEndTest`, not in `GranularRulesEndToEndTest`;
  passed first time, no production change needed.
- **T037**: done (`TestingRoutingLogContractTest`, `docs/LOGGING.md`). The cases were **not run
  red first**: a `verify` run held the build directory while they were written.
- **T038**: done. The research R9 case is
  `MultiModuleTestingMdTest.aTestingMdInsideAModuleTakesThatModulesTestGuardrailsOnly`: a
  `module-a/TESTING.md` takes module-a's test guardrails and nothing else, its `CLAUDE.md` gives
  them up and carries the pointer once, and the root file is unaffected. It passed first time, as
  R9 predicted, and detects: with `routesTestGuardrails` forced to false it is one of the three
  cases in the class that go red.
- **T042**: done. PLATFORMS, PROCESSOR, MULTI-MODULE, LOGGING, TESTS, CHANGELOG, USAGE and the
  vibetags-usage Quick Setup were already there; `references/output-files.md` in the
  vibetags-usage skill is now too. The add-platform step was already written (commit 4631fbdb,
  inside checklist item 9), so that half of this entry was stale.
- **T043**: evaluated, answer **no**, and the reasoning is the PR's. The candidate fact is the
  asymmetry in `routesTestGuardrails`: a service wrongly routed loses guardrails from a file a
  tool loads, one wrongly left alone only costs context. Three things already carry it, one of
  them enforced: the method's own Javadoc states it in full, `ServiceRoutingContractTest` fails
  for every new key until someone classifies it by hand, and the add-platform skill asks the
  question at the point a new key is added. A build-enforced gate beats prose an agent may or may
  not have loaded, and `ServiceRegistry` already carries a class-level `@AIContext`, so a
  method-level annotation would be a second entry saying what the first three say.
- `TestingRenderer` is now `RoutedTestingRenderer`: PMD's `TestClassWithoutTestCases` fires on
  any class named `Test*`, and the repo has no PMD suppressions. `plan.md` and `research.md`
  still use the old name.
- **T029**: done, and a finding: invariant 17 already covered the routed file, so no production
  change was needed. Two cases in `PartialRoundGuardrailLossTest`, over three annotated
  `src/test/java` classes each carrying one routed `@AIContext` and one safety `@AILocked`, so one
  partial round is checked against both destinations. Both passed first time; with the
  `!unread.isEmpty()` guard in `process()` disabled they go red on the assertions that matter (the
  `TESTING.md` one, not the precondition, and the missing warning). The fixture needs a `pom.xml`
  at the root: without a build file the source set is never classified and the round is not routed,
  which is what the first red run showed.
- Also corrected: `plan.md` and `research.md` called the renderer `TestingRenderer`; it is
  `RoutedTestingRenderer`.
- **Not started**: T038a (KSP), T039 to T041 (example
  fixtures and the verify-generated-files action; they need the processor installed), T043
  (self-annotation candidate), T045 (load tests), T046 (consumer sweep), T047 (PR, issues, CI).
  T044 is partly done: `mvn -B verify -Pe2e` and the locked-files guard pass; `pre-commit` has
  only been run per commit with checkstyle skipped there (checkstyle runs in the Maven build);
  the quickstart walk has **not been run**.
- Pulled forward from Phase 7 because the new service key turned existing gates red: the
  `docs/PLATFORMS.md` row and README config-file count (part of T042), and the empty
  `examples/basic/TESTING.md` plus its `reset-ai-files.sh` entry (part of T039).

Deviations from the task text, each deliberate:

- **T012**: `TestingRenderer` delegates to the Codex renderer as `Platform.CODEX`, not as
  `Platform.TESTING`, so no annotation formatter needs a `TESTING` arm (research R7).
- **T019**: the six safety annotations now have one definition, `GuardrailAnnotations.SAFETY`
  (beside the locked `ALL`, not in it); `TransitiveManifest.SAFETY_ANNOTATIONS` points at it.
- **T020**: the contract's predicate called `root_index` routed (extensionless name reads as hash
  markers, no merge shape). The rule now also requires a platform. Found by the hand-pinned set.
- **T026**: the transient flag is `testingWithdrawn` (set when `TESTING.md` is absent), not
  `testingOptedIn`, so an in-memory sidecar that never went through `readAll` is never substituted.
- **T014**: `root_index` counts as "another AI config file" for the `AGENTS.md` sole-file rule the
  same way `testing` did, going by the code (`active.size() > 1` with `root_index` in
  `OPT_IN_KEYS`); read, not run. Left alone; it predates this feature. Worth an issue if it holds.
