# Research: Route Test-Code Guardrails to TESTING.md

Every finding below was read from the working tree on 2026-09-19 (branch
`001-test-annotations-testing-md`, based on `origin/simplify/whole-codebase`). Nothing here was
executed; line numbers are from that tree and will drift.

## R1. How to tell test code from main code

**Decision**: Classify per compilation round, using the source set `ModuleRootResolver` already
resolves into `ModuleIdentity.sourceSet()`. A round is a test round when its source set is `test`,
ends in `Test` or `Tests` (`integrationTest`, `functionalTest`), or is `testFixtures`. Expose it
as one predicate on `ModuleIdentity`.

**Rationale**: The processor already keys sidecars by module and source set
(`.vibetags-mod-core` vs `.vibetags-mod-core__test`), so a round-level answer lines up with the
unit the merge works in. `sourceSetOf()` reads the segment after `src/`, which is language
independent, so Kotlin and Groovy test sources classify the same way as Java.

**Known limit, by design**: `pickSourceSet()` collapses a round that compiles main and test
sources together to `main`. Such a round is not routed and its test guardrails stay in the
always-loaded files. That is lossless. By the time the builder runs, a mixed round is
indistinguishable from a main round, so it logs `testing.skip reason=not-test-round`; the limit is
documented, not detected.

**Alternatives considered**:
- Per-element classification from each element's source path. More precise for mixed rounds, but
  it cuts across the per-round sidecar model and needs compiler APIs at a point the rendering
  layer may not import them (invariant 7).
- "Anything that is not `main`". Rejected: `jmh`, `generated` and similar source sets are not
  tests, and the existing `__<sourceSet>` sidecar naming does not claim they are.

**Open check for tasks**: what source set the KSP front end (`vibetags-ksp`) reports. Until
verified, assume `main`, which means "not routed" and is lossless.

**Resolved 2026-09-20 (T004), read not executed**: the KSP front end reports the real source set.
`vibetags-ksp` never names `ModuleIdentity` or a source set itself. `Trees.instance` rejects
`KspProcessingEnvironment`, so `ModuleRootResolver.fromRound` falls through to
`KspElements.getFileObjectOf`, which returns the Kotlin file's own `file:` URI from
`StubModel.sourceOf`. `sourceSetOf` then reads the segment after `src/`, so
`src/test/kotlin/...` classifies as `test` exactly as a Java test source does. A KSP test round
will therefore be routed, and no test in `vibetags-ksp/src/test` names a source set today. That
is a gap, so Phase 7 gains a KSP routing test task.

## R2. Where the routing happens

**Decision**: In `GuardrailContentBuilder.build()`, by rendering each service against a filtered
view of the model. In a test round with `testing` active:

- routed instruction aggregates render `model.safetyOnly()` and get one pointer line appended;
- the `testing` service renders `model.withoutSafety()`;
- every other service renders the full model, as today.

In a main round the `testing` renderer returns `null` (no entry), and everything else is
untouched.

**Rationale**: `mergeAcrossModules()` returns `contentByService` unmerged when there is a single
sidecar (`isMultiModule()` false). A tests-only module, such as the `tests` module of
`examples/multimodule`, is exactly that case. Routing done only at merge time would therefore
never run for it. Routing at build time makes the single-sidecar path and the merged path agree
with no change to the merge, and keeps all 40-odd renderers untouched because they only ever see
a model.

**Alternatives considered**:
- Merge-time substitution modelled on the lean root index (`indexPointers` / `~idx~`). Rejected
  as the primary mechanism for the reason above; it is still used for the toggle fallback in R4.
- Teaching each renderer about test routing. Rejected: 40-odd edits and as many ways to drift.

## R3. `generateFiles()` is locked

**Decision**: The plan makes zero edits to `AIGuardrailProcessor.generateFiles()`.

**Rationale**: It is `@AILocked` (step order). Every seam this feature needs is already called
from it and is not locked:

| Need | Existing seam | Change |
|---|---|---|
| Builder knows the round's source set | `new GuardrailContentBuilder(collector, ...)` | collector carries the source set, set in `process()` right after `ModuleRootResolver.fromRound` |
| Extra sidecar bodies | `populateSidecarBodies(mySidecar, contentByService)` | becomes an instance method with the same name and arguments, so the call site is byte-identical |
| Merge-time fallback | `ModuleSidecar.readAll(root)` | sets transient state, the `applyRootIndexModeTo` precedent |
| New service | `ServiceRegistry.buildServiceFileMap` / `resolveActiveServices` | ordinary registry entry |

`checkFiles()` calls the same seams, so check mode follows without a second implementation. If a
task finds it cannot proceed without editing `generateFiles()`, it stops and escalates; it does
not edit.

## R4. Toggling TESTING.md must not lose guardrails

**Decision**: When a test round is routed, its sidecar keeps the unrouted rendering of each routed
service under a new `~tfull~<service>` key. `ModuleSidecar.readAll()` records, as transient state,
whether `testing` is opted in at the root. `mergeFor()` uses `~tfull~<service>` in place of the
routed body when `testing` is not opted in.

**Rationale**: Deleting `TESTING.md` and running `mvn compile` (no `test-compile`) would otherwise
merge the test sidecar's safety-only bodies into `CLAUDE.md` while `TESTING.md` no longer exists:
the non-safety test guardrails would be in no file until the next test compile. The opposite
toggle (creating `TESTING.md`, then main-only build) is already lossless: the test sidecar still
holds full bodies and no `testing` body, so nothing moves until the test round re-renders.

Both toggles change the active-service set, which is already folded into `BuildFingerprint`, so
the fingerprint short-circuit cannot skip the regeneration (FR-013, invariant 12). The source set
is already in the fingerprint through `module:<moduleId>`.

**Sidecar compatibility**: `~tfull~` is a new key namespace, like `~idx~`, `~gran~` and
`~granname~` before it. An older processor leaves an unknown key unstored; it then merges the
routed body, which is what the file on disk holds while `TESTING.md` exists. A sidecar written
before this feature has no `~tfull~` and no `testing` body and merges exactly as today.

**Alternatives considered**: keep the full body under the normal key and the safety-only body
under a new one. Rejected because the single-sidecar path writes `contentByService` directly, so
the normal key has to hold the routed body.

## R5. Which services are "always-loaded instruction files"

**Decision**: One predicate, `ServiceRegistry.routesTestGuardrails(key)`. A service is routed when
it writes a single file, gets markers (`getMarkersFor != null`, so not JSON or TOML), declares no
`mergeShape()` (so not a YAML review config), is not an ignore service, and is not on a short
explicit exclusion list: `testing` itself, the `*_safety` files (already safety-only),
`.vibetags-locks`, and the `llms` files. A test enumerates every registered service key against a
pinned expected set, so adding a platform forces a decision.

**Rationale**: FR-009 keeps files that configure tool behaviour out of scope. A derived predicate
plus a pinned set is the pattern `YamlMergeShapeContractTest` already uses: the rule is code, the
list is a test.

**Alternatives considered**: a hand-written list like `INDEXABLE_AGGREGATES`. Rejected as the sole
source: it silently excludes every platform added later.

**Open check for tasks**: whether `llms.txt` / `llms-full.txt` should be routed. Excluded for now
because they are published project descriptions, not session instructions.

## R6. The pointer

**Decision**: The pointer is part of the test round's routed body, appended after the safety-only
rendering, and only when `model.withoutSafety()` is non-empty. It is not added by the merge.

**Consequence for the spec**: in a reactor each module's region that has test guardrails carries
its own pointer, the same shape the lean root index uses ("one pointer per module"). FR-008 was
amended from "exactly one pointer per file" to "exactly one per module region; exactly one per
file in a single-module project".

**Rationale**: No change to `mergeFor()` for the pointer, and the single-sidecar path gets it for
free. The pointer text is a constant, so it needs no escaping; it names `TESTING.md` relative to
the root.

## R7. What TESTING.md looks like

**Decision**: `TESTING.md` at the VibeTags root, service key `testing`, `Platform.TESTING`,
HTML-comment markers (derived from the `.md` extension by `getMarkersFor`). `TestingRenderer`
delegates to an existing Markdown bucket-walk renderer with its own short preamble, per the
`FirebaseRenderer` delegation pattern; the delegate is chosen in tasks after comparing rendered
output, with the Codex/`AGENTS.md` style as the default candidate because it is tool-neutral.

**Rationale**: The spec assumes an existing layout, not a new format. Delegation means zero new
`case TESTING:` arms in the 44 annotation formatters.

**Not a new AI platform**: `TESTING.md` is a VibeTags convention, not a file any vendor reads, so
Step 0a of the `add-platform` skill (verify at the vendor) has nothing to verify and the README's
AI-platform count does not move. The rest of that skill's checklist applies.

## R8. An empty safety-only view

**Decision**: When a routed test round has no safety annotations, the routed services are still
rendered from the empty `safetyOnly()` view, not skipped.

**Rationale**: In the single-sidecar path a service missing from `contentByService` is simply not
written, which would leave the previous build's test entries in `CLAUDE.md` forever. Rendering
the empty view overwrites them. What an empty view renders per platform is pinned by a test, and
`putBody` already drops blank bodies so the merged path gains no empty shell.

**Risk**: a renderer may emit a non-blank scaffold for an empty model. That is existing behaviour
for any module whose annotations are all in buckets a platform ignores, so it is not new, but the
end-to-end test asserts on it explicitly.

## R9. Per-module nested TESTING.md

**Decision**: Out of scope as a feature, but must not break. A `module-a/TESTING.md` would be
picked up by `ModuleOutputWriter` because it reuses the same registry and builder, so routing
applies there as a by-product. One test pins that it behaves like the root case; no docs promise
it in this change.

## R10. Logging (invariant 15)

| Event | When | Keys |
|---|---|---|
| `testing.route` | test round, `testing` active | `sourceSet`, `routed` (service count), `moved` (non-safety refs), `kept` (safety refs) |
| `testing.skip` | `testing` active, round not routed | `reason=not-test-round` \| `no-test-guardrails`, `sourceSet` |
| `merge.testing.fallback` | merge used `~tfull~` | `service`, `module` |

No event when `testing` is not opted in: the absent-file path must stay byte-identical, log
included. Tested events become contracts in `GuardrailFileWriterLogContractTest` style.

## R11. Constitution

`.specify/memory/constitution.md` is the unfilled template. The plan gates on the 17 Tier-1
invariants in the root `CLAUDE.md` instead, which are this repository's actual constitution and
each name an enforcing test. Running `/speckit-constitution` to import them is a follow-up, not a
blocker.
