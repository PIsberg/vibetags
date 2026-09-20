# Implementation Plan: Route Test-Code Guardrails to TESTING.md

**Branch**: `001-test-annotations-testing-md` | **Date**: 2026-09-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-test-annotations-testing-md/spec.md`

## Summary

When a user-created `TESTING.md` exists at the VibeTags root, a compilation round over a test
source set renders its non-safety guardrails into `TESTING.md`, and renders only the six safety
annotations (plus one pointer line) into the always-loaded instruction files. Main rounds and
projects without `TESTING.md` produce today's bytes.

The routing is done in `GuardrailContentBuilder` by rendering each service against a filtered
view of the model, so no renderer changes and the single-sidecar and merged paths agree
([research R2](research.md)). The locked `generateFiles()` is not edited; every seam needed is
already called from it (R3). A `~tfull~` sidecar key keeps the unrouted rendering so deleting
`TESTING.md` loses nothing before the next test compile (R4).

## Technical Context

**Language/Version**: Java, processor built with JDK 21 in CI; annotations stay
`RetentionPolicy.SOURCE`

**Primary Dependencies**: JSR 269 (`javax.annotation.processing`), `VibeTagsLogger`; no new
dependency

**Storage**: files only: `TESTING.md` (new output), `.vibetags-mod-*` sidecars (one new key
namespace, `~tfull~`), `.vibetags-cache` (format unchanged)

**Testing**: JUnit 5 under `vibetags/src/test`, fast tier `mvn test`, full `mvn test -Pe2e`;
`ProcessorTestHarness` for real compiles; static analysis via `mvn -B verify -Pe2e`

**Target Platform**: any javac-hosted build (Maven, Gradle), Windows/macOS/Linux as in CI

**Project Type**: library (compile-time annotation processor)

**Performance Goals**: no measurable cost when `TESTING.md` is absent. When present, a test round
renders each routed service twice (routed and `~tfull~`); to be measured with the existing
`load-tests/` annotation-volume sweep, not estimated

**Constraints**: byte-identical output when `TESTING.md` is absent (FR-002); zero edits to
`generateFiles()`; rendering layer imports no compiler API; sidecars stay readable by and from
older processor versions

**Scale/Scope**: 1 new service, 1 new delegating renderer, about 10 production classes touched,
3 new test classes plus additions to about 8 existing ones, 6 docs, 2 example fixtures

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template (R11), so the gates are the
Tier-1 invariants in the root `CLAUDE.md`. Only those this feature can touch are listed; 5, 8, 11,
14 and 16 are not in its path.

| # | Invariant | How the design holds it | Enforcing check |
|---|---|---|---|
| 1 | File presence is the only opt-in; never create an output file | `testing` is an ordinary `OPT_IN_KEYS` entry; nothing creates `TESTING.md` | `GuardrailLifecycleEndToEndTest` + new lifecycle test |
| 2 | Hand-authored content outside markers is never lost | `.md` gets HTML markers from `getMarkersFor`; written through `GuardrailFileWriter` only | `GuardrailFileRecoveryEndToEndTest`, `MarkerInjectionTest` extended |
| 3 | `process()` returns false; writing on `processingOver()` | `process()` gains one setter call after `fromRound`; no write moves | `AIGuardrailProcessorProcessTest` |
| 4 | `AGENTS.md` sole-fallback rule | `TESTING.md` must not count as "an AI config file" for that rule, or creating it would stop `AGENTS.md` being written. Explicit task and test | `AgentsMdSoleFallbackTest` extended |
| 6 | Safety buckets stay inline | Safety annotations never move (FR-010); the routed body is `safetyOnly()` | `GranularRulesEndToEndTest` + new routing test |
| 7 | Rendering layer imports no compiler API | The source set reaches rendering as a plain `boolean` on `RenderingContext`; model views are pure | `ArchitectureRulesTest` |
| 9 | New output follows `add-platform` | Followed, minus Step 0a (no vendor exists, R7) | skill checklist, `ExampleOptInCoverageTest` |
| 10 | YAML `mergeShape()` / marker-free `wholeFileMerge()` | Not applicable: Markdown with markers. The contract tests still run over the new service | `YamlMergeShapeContractTest`, `MultiModuleWholeFileMergeTest` |
| 12 | Generated content reaches `BuildFingerprint` | The active-service set and `module:<id>__<sourceSet>` are already inputs; a test toggles `TESTING.md` and asserts regeneration | new case beside `TransitiveFingerprintTest` |
| 13 | Granular files only via `mergeGranular` | Granular files are out of scope (FR-009), untouched | `MultiModuleGranularRoleMergeTest` |
| 15 | Logging is law | Events in R10; every `.skip` has `reason=`; no event when not opted in | log contract test, new cases |
| 17 | A partial round writes nothing | Routing sits behind the partial-round gate and does not weaken it | `PartialRoundGuardrailLossTest` + new case with `TESTING.md` |

Locked and sensitive elements:

- `AIGuardrailProcessor.generateFiles()` **locked**: not edited (R3). Checked with the
  locked-files guard run locally (`PYTHONIOENCODING=utf-8`), which no Maven build runs.
- `GuardrailAnnotations.ALL` **locked**: not touched; `safetyOnly()` selects buckets and does not
  reorder the list.
- `ModuleSidecar` (**core, high**): one new key namespace and one transient flag; the
  `@AIContract` signature of `mergeFor` is unchanged. Its scoped rule file is read first.
- `Escape` (**security**): every interpolated value in `TESTING.md` goes through the delegate
  renderer's existing `Escape` calls; the pointer is a constant. No new interpolation site.

**Gate result, pre-design**: PASS, with invariant 4 flagged as the one most likely to be broken
by accident. **Post-design re-check**: PASS. The design added no violation; the one place it leans
on a technicality is recorded under Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/001-test-annotations-testing-md/
├── plan.md              # This file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── testing-md-output.md    # file format, pointer text, routed-service predicate
│   └── sidecar-and-logging.md  # ~tfull~ key, merge fallback, log events
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks), not created here
```

### Source Code (repository root)

```text
vibetags/src/main/java/se/deversity/vibetags/processor/
├── AIGuardrailProcessor.java             # process(): hand the source set to the collector;
│                                         # populateSidecarBodies(): store ~tfull~ bodies.
│                                         # generateFiles(): NOT edited
├── internal/
│   ├── ModuleIdentity.java               # + isTestSourceSet()
│   ├── AnnotationCollector.java          # + source-set carrier (read scoped rule first)
│   ├── ServiceRegistry.java              # + "testing" path, OPT_IN_KEYS, routesTestGuardrails()
│   ├── GuardrailContentBuilder.java      # routing: filtered model view per service, pointer
│   ├── ModuleSidecar.java                # + ~tfull~ bodies, transient flag, mergeFor fallback
│   └── content/
│       ├── Platform.java                 # + TESTING("testing")
│       ├── PlatformRendererRegistry.java # + case TESTING
│       ├── RenderingContext.java         # + testRound flag
│       └── platforms/TestingRenderer.java    # NEW, delegating
└── model/GuardrailModel.java             # + safetyOnly(), withoutSafety() (read scoped rule first)

vibetags/src/test/java/se/deversity/vibetags/processor/
├── TestingMdRoutingEndToEndTest.java     # NEW (e2e): US1, US3, US4, single-sidecar path
├── TestingMdLifecycleEndToEndTest.java   # NEW (e2e): US2, toggles, the three build shapes
├── ServiceRoutingContractTest.java       # NEW (fast): pinned routed-service set
└── extended: AgentsMdSoleFallbackTest, MarkerInjectionTest, CheckModeTest,
    PartialRoundGuardrailLossTest, SourceSetIsolationEndToEndTest, the log contract test,
    AIGuardrailProcessorProcessTest and AIGuardrailProcessorUnitTest (service-key sets)

examples/basic/          # + TESTING.md fixture, an annotated test source, reset-ai-files.sh
examples/multimodule/    # + TESTING.md; its `tests` module is the single-sidecar case
docs/PLATFORMS.md, docs/PROCESSOR.md, docs/MULTI-MODULE.md, docs/TESTS.md, docs/CHANGELOG.md,
USAGE.md, .claude/skills/vibetags-usage/, .claude/skills/add-platform/ (routing-decision step)
.github/actions/verify-generated-files, .github/workflows/build.yml (active-service counts)
```

**Structure Decision**: No new module. The change lives in the existing `vibetags` processor
module plus fixtures and docs. `vibetags-annotations` is untouched (no new annotation).
`vibetags-ksp` is touched only if the R1 open check finds it reports a test source set.

## Delivery order (input to /speckit-tasks)

Each step is failing-test-first, and each behaviour change ships with its test.

1. **Registry and renderer, no routing** (US2 first): `testing` service, `Platform.TESTING`, a
   delegating renderer that returns `null` outside a test round. Proves FR-001 and FR-002: with
   and without `TESTING.md`, every other file byte-identical. The invariant 4 test lands here.
2. **Source set to the builder**: `ModuleIdentity.isTestSourceSet()`, the collector carrier, the
   `RenderingContext` flag. No output change yet.
3. **Routing** (US1, US4) and the pointer (US3): model views, builder routing. The routing e2e
   test includes a tests-only module (single-sidecar path) and a class carrying both kinds.
4. **Toggle safety** (FR-007, FR-012, FR-013): `~tfull~`, transient flag, `mergeFor` fallback.
   Lifecycle e2e: create and delete `TESTING.md`, crossed with main-only, tests-only and
   main-then-tests builds.
5. **Check mode, partial round, multi-module** cases in the existing suites.
6. **Logging** events and their contract tests.
7. **Fixtures, CI-only gates, docs**: examples (clear `.vibetags-cache`; `mvn clean verify` for
   the reactor), `verify-generated-files` (run its body, do not read it), active-service counts
   in `build.yml` (read from `vibetags.log`), the diagram-drift artifact for the new renderer
   class, and every doc in the same change.
8. **Gates**: `mvn -B verify -Pe2e` from `vibetags/`, the locked-files guard, `pre-commit run
   --all-files` after `git add`, then the consumer regression sweep, because this changes what a
   test round writes in any consumer that creates the file.

VibeTags self-annotation, evaluated per the standing rule with a default of no: one candidate,
`ServiceRegistry.routesTestGuardrails`, where the non-obvious fact is the asymmetry (a service
wrongly included loses non-safety test guardrails from a file a tool does load; one wrongly
excluded only costs context). Decide when the code exists. Nothing else in this change states a
fact the code does not.

## Known risks

- **Empty safety-only view (R8)**: a platform may render a non-blank scaffold for an empty model.
  Asserted explicitly in the routing e2e test.
- **Mixed rounds**: a build that compiles main and test sources in one javac invocation is
  reported as `main` and is never routed. Lossless; documented in `docs/PROCESSOR.md`.
- **Reactor pointer count**: one pointer per module region, not per file (R6). FR-008 in the spec
  was amended to say so.
- **Base branch**: this branch carries 17 unmerged commits from `simplify/whole-codebase` that
  restructure the renderers this plan delegates to. If that branch changes before it merges,
  restack with `git rebase --update-refs`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `populateSidecarBodies` turns from `static` to an instance method so the locked call site stays byte-identical | it must read the round's source set and active services to store `~tfull~` | passing them as arguments means editing `generateFiles()`, which is locked. If review judges the same-name change to be against the lock's intent, the fallback is to escalate the lock to the owner, not to work around it further |
| A second rendering pass per routed service in test rounds | `~tfull~` needs the unrouted body | dropping it loses non-safety test guardrails between deleting `TESTING.md` and the next test compile (R4) |
