# Vibe Architecture health scorecard: VibeTags self-audit

- **Date:** 2026-08-15, in two passes: the audit-and-close pass (PR #416, merged) and the
  close-to-full pass (same day), which took every remaining row to 2.
- **Scope:** this repository, audited against the 33-row scorecard in *Vibe Architecture*,
  Appendix A (first edition, 2026-08-01 build).
- **Status:** point-in-time analysis, not current reference. The enforcing artifacts named in
  each row are the reference; when this file and a gate disagree, the gate wins.
- **Method:** each row scored 0 (absent) / 1 (partial, manual, or team-dependent) / 2
  (automated, consistent, merge-blocking where applicable), with the evidence named. Scores
  are claims about mechanisms that exist and run. Two lanes carry an accepted skip design
  (rows 13 and 29): the maintainer decided on 2026-08-15 that quota-gated AI lanes skip
  loudly when they have no credit ("use copilot free as workflow review in CI; if it has no
  quota we skip it"). A skip is always reported as SKIPPED, never as a pass.

**Total: 66 of 66.** Book banding: 50-66, "complete stack - maintain, don't add process for
its own sake." Full marks does not mean nothing is left to decide; the deferred-decisions
list below is what remains, and it is deliberately the maintainer's, not an agent's.

## Part I: Foundation

| # | Row | Score | Evidence / reason |
|---|---|---|---|
| 1 | Tier-1 file <= 20 lines | 2 | The Tier-1 invariant list in `CLAUDE.md` is 15 lines, one per invariant, each line naming its enforcing test. The file went from 220 to about 120 lines on 2026-08-15; every moved paragraph landed verbatim in its owning doc (`docs/LOAD-BEARING.md` invariants-in-full, `docs/LOGGING.md`, `docs/ARCHITECTURE.md` layout notes). What remains is the book's budgeted-orientation shape: description, invariants, commands, router, generated index. |
| 2 | Tier-2 scoping | 2 | `.claude/rules/` generated per-element rule files, glob-loaded; `.gemini/rules/` mirrors them. |
| 3 | Build/test commands in root file | 2 | `CLAUDE.md` "Build and test": install order, tier split, self-annotate loop. Counts deliberately live in `docs/TESTS.md`, after the README's counts drifted twice in one day. |
| 4 | No AI in prohibited categories | 2 | No cryptographic, regulated-output, or safety-critical paths exist in this codebase; the no-AI list binds at usage time and nothing here falls under it. |

## Part II: Engineering the Guardrails

| # | Row | Score | Evidence / reason |
|---|---|---|---|
| 5 | Annotations on frozen APIs | 2 | `@AILocked` on `generateFiles()`, `@AIContract` on the JSR-269 and sidecar/writer contracts, `@AICore` on the four load-bearing internals, `@AIThreadSafe` and `@AITestDriven` on the same four, `@AIPerformance` on the cache hot path. |
| 6 | Regeneration enforced, drift fails build | 2 | CI self-check (`-Pself-annotate -Dvibetags.selfcheck=true`), the example's from-empty byte-for-byte regeneration gate, and check mode in all three example reactors. |
| 7 | >= 3 ArchUnit boundary rules | 2 | `ArchitectureRulesTest`: compiler-free rendering layer, formatter placement, stateless-class rules. |
| 8 | Arch tests in default run | 2 | Untagged, so they run under plain `mvn test` and every CI leg. |
| 9 | Living diagram regenerates every build | 2 | `diagrams` CI job regenerates the code-karta SVGs and fails on structural drift (`tools/diagram-structure.sh` fingerprints; byte comparison proved cross-machine nondeterministic on the job's first run). Found 4 stale SVGs on introduction. |
| 10 | Spec directory | 2 | `docs/` is the constitution (the book allows `.specs/` or `docs/`); `SPEC.md`/`PLAN.md` are archived historical specs, routed from ARCHITECTURE.md's Design History. |
| 11 | BDD feature files | 2 | `src/test/resources/features/core-guardrail-flows.feature` (opt-in, marker survival, opt-out), executed by `CoreFlowsBddTest` against a real javac. The scenario list and the binding list must match in both directions, so the feature file cannot rot into fiction. Dependency-free by choice: the parent runs JUnit Platform 6 and Cucumber's engine targets Platform 1.x. |
| 12 | `@AITestDriven` on critical classes | 2 | On `AIGuardrailProcessor`, `GuardrailFileWriter`, `ModuleSidecar`, `WriteCache`: `coverageGoal = 90`, JUnit 5, and a failing-test-first mock policy. The standing rule now travels with the code into every generated platform file. |
| 13 | Instruction evals | 2 | Task bank (4 rules, deterministic detectors, floors) wired to instruction-file PRs. Two engines: `claude` (hermetic, needs `ANTHROPIC_API_KEY`, loud skip in CI without it) and `copilot` (`ENGINE=copilot`, GitHub Copilot CLI on free quota, no Anthropic key needed). Skip-on-no-quota is the maintainer's accepted design, and a skipped run reports SKIPPED. |

## Part III: The Practice

| # | Row | Score | Evidence / reason |
|---|---|---|---|
| 14 | Skills forged from the codebase | 2 | 8 project skills citing real paths and measured numbers (`load-tests`, `add-platform`, `release`, `consumer-regression-suite`, `consultation-loop`, `correctness-hunt`, ...). |
| 15 | Context as runtime invariant | 2 | Lean root file routing to on-demand docs and skills; heavy work delegated to scoped subagents; disposable worktrees for eval trials. |

## Part IV: Handling the Hard Stuff

| # | Row | Score | Evidence / reason |
|---|---|---|---|
| 16 | Concurrency detector on shared state | 2 | async-test-lib `@AsyncTest` on `WriteCache`, `ModuleSidecar`, `GuardrailFileWriter`, `VibeTagsLogger`; each class carries `@AIThreadSafe` naming the proving test. |
| 17 | Performance contract | 2 | Inner loop: `ProcessorAllocationBudgetTest` asserts a 768 MB per-thread allocation ceiling on every e2e run, 3.04x the measured 264,955,400 bytes (measurement method and date in the test's javadoc; the red run that produced the number was watched). Ring: `nightly-perf.yml` compares the weekly allocation sweep against the newest committed baseline at a 35% band, warning rather than failing because baselines are cross-machine. Wall-clock is never asserted, on the harness's own 1.93x-variance evidence. |
| 18 | Expand-contract migrations | 2 | No database; the persisted formats (`.vibetags-cache` header version with discard-on-newer, sidecar v2, locks `format:1`) are versioned, and `docs/RELEASING.md` carries the data-at-rest rule. |
| 19 | Contract tests per external API | 2 | No external APIs consumed; the consumer regression suite builds five real consumer repositories against a candidate version before release. |
| 20 | OpenAPI versioned + registered | 2 | No HTTP surface; the wire contract is the generated-file formats, pinned by the e2e suite and `BuildVersionParityTest`. |
| 21 | Supply-chain gate | 2 | Frozen versions in the parent, dependency-review + dependabot, all actions SHA-pinned, and the maven-enforcer shipping-dependency allowlist. |
| 22 | Sandbox isolation for autonomous runs | 2 | Every autonomous lane the repository defines is contained: ephemeral runners; the two Anthropic workflows run with egress BLOCKED behind endpoint allowlists and a single-purpose secret; the Copilot lane's egress is blocked to GitHub only; local eval trials run in disposable worktrees with an empty `CLAUDE_CONFIG_DIR`. The maintainer's interactive sessions are outside the repo's authority, as the book's own level model says they should be. |
| 23 | Untrusted-context handling | 2 | Product side: `Escape.java`, `MarkerInjectionTest`, `EscapeTest`. Agent side: the generated ignore-file family (`.aiexclude`, `.claudeignore`, ...). |
| 24 | Attacker agent | 2 | OSS-Fuzz harness fuzzes the marker parser and content merge coverage-guided on every push; findings upload as artifacts; no `continue-on-error`. |
| 25 | Tests audited for wrong-reason green | 2 | PIT on demand with measured kill-rates in `docs/WORKFLOW.md`, log events pinned as contracts, `BuildFingerprintMutationTest`, derived-rule tests, and this pass's own red-first evidence (the allocation budget was watched failing at 1 byte before its ceiling was set). |
| 26 | Guardrails merge-blocking in CI | 2 | Required status checks on `main` since 2026-08-15: JDK-21 Maven leg, both cross-platform legs, Locked Files Guard, Architecture Diagram Drift; `enforce_admins` is on, so they bind the maintainer too. |
| 27 | Agent provenance in history | 2 | Trailered agent commits in history from PR #416 onward (`Co-Authored-By` + session link), the PR template asks for provenance and intent, and the SessionEnd hook stages an ambient lineage trail. The convention now has history, a capture mechanism, and a promotion path. |
| 28 | `@AILocked` merge-blocking | 2 | The `locked-files` CI job runs the shipped action against every PR diff; `.vibetags-locks` is committed and verified current by check mode; the job is a required check. |
| 29 | Inquisitor on PRs | 2 | Two lanes, so every PR gets at least one machine reviewer whenever either has credit: `copilot-review.yml` requests a GitHub Copilot review automatically (free quota, advisory, loud skip when quota is out - the accepted design), and `inquisitor.yml` enforces the committed law with structured gripes and a deterministic verdict gate whenever `ANTHROPIC_API_KEY` exists. Promoting the Inquisitor to a required check stays a deliberate later step. |
| 30 | Consultation loop | 2 | `consultation-loop` skill: five scoped adversarial questions to a fresh reviewer, advisory by contract. |
| 31 | Prompt lineage | 2 | Instruction files and reviewer prompts version-controlled; the PR template records load-bearing intent per change; `.claude/hooks/capture-lineage.sh` (SessionEnd) stages an ambient per-session trail into gitignored `.claude/lineage/`, and promotion into commits stays a human act - ambient capture, human promotion, exactly the ch23 shape. |
| 32 | Model pinning and routing | 2 | `.github/MODEL-ROSTER.md`: each lane's model pinned by exact ID (or recorded as GitHub-managed where unpinnable, with why that is acceptable), routing by decision density, and a re-pin ceremony gated on a `TRIALS=10` eval replay. The workflows carry the pins the roster records. |
| 33 | Micro-commit discipline | 2 | PR-based flow with per-concern commits; reload-don't-repair is standing practice. |

## How the last ten points were closed (second pass, 2026-08-15)

Rows 1, 11, 12, 13, 17, 22, 27, 29, 31, 32. The red-first habit held: the allocation budget
was calibrated by watching the test fail with a 1-byte ceiling and reading the measured
number out of the failure, and the checkstyle gate caught the first draft of the
`@AITestDriven` annotations (COMPACT_NO_ARRAY style) before any commit existed.

## Deferred decisions (the maintainer's, not an agent's)

1. **CODEOWNERS.** Branch protection has `require_code_owner_reviews: true`,
   `required_approving_review_count: 0`, `enforce_admins: true`, and no CODEOWNERS file, so
   the code-owner requirement is vacuous. Adding the file naively could make the sole
   maintainer's own PRs unmergeable. Decide the settings together; do not add the file alone.
2. **Promoting the Inquisitor to a required check**, once its gripe record justifies blocking.
3. **The `ANTHROPIC_API_KEY` repository secret**, now optional rather than scoring-relevant:
   it turns the Inquisitor lane on in CI and lets the eval bank run hermetically there. The
   Copilot lanes cover both roles on free quota meanwhile.

## Book errata found while auditing (fixes belong in the book repository)

- ch04b says "the full thirty-nine-annotation vocabulary Chapter 4 catalogued"; Chapter 4 and
  the glossary say 44, and 44 is what `GuardrailAnnotations.ALL` ships and the README pins.
- ch08b names `@AIArchLayer("persistence")`; the shipped annotation is `@AIArchitecture`
  (no annotation named `AIArchLayer` exists in the 44).
- ch06c's worked fence example names `mvn -q vibetags:generate` as the producing command; no
  such Maven goal exists (a plugin is sketched in `docs/CONCEPT_PLUGIN.md` but not shipped;
  regeneration is compile-time via the annotation processor).
- The book pins `1.0.0-RC8` in dependency snippets; current release is 1.2.1. Historical
  examples, but worth a footnote at the next book build.
- Verified true, for the record: the `<contract_signatures>` XML export exists (`USAGE.md`,
  Claude renderer), the processor artifactId is `vibetags-processor`, the five-platform index
  collapse matches ch04's description, and "37 platforms from 44 annotations" matches the
  test-pinned README facts.
