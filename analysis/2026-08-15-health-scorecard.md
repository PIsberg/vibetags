# Vibe Architecture health scorecard: VibeTags self-audit

- **Date:** 2026-08-15
- **Scope:** this repository, audited against the 33-row scorecard in *Vibe Architecture*,
  Appendix A (first edition, 2026-08-01 build), after the alignment changes landed in the same
  PR that adds this file.
- **Status:** point-in-time analysis, not current reference. The enforcing artifacts named in
  each row are the reference; when this file and a gate disagree, the gate wins.
- **Method:** each row scored 0 (absent) / 1 (partial, manual, or team-dependent) / 2
  (automated, consistent, merge-blocking where applicable), with the evidence named. Scores are
  claims about mechanisms that exist and run, not about intentions.

**Total: 56 of 66.** Book banding: 50-66, "complete stack - maintain, don't add process for its
own sake." The ten points not taken are listed with reasons; several are deliberate deviations
rather than debts, and are marked so.

## Part I: Foundation

| # | Row | Score | Evidence / reason |
|---|---|---|---|
| 1 | Tier-1 file <= 20 lines | 1 | `CLAUDE.md` is 219 lines: invariants, enforcement pointers, build commands, logging law, docs router, generated guardrails index. Far over the book's budget, and deliberately so. The instruction evals (row 13) now measure which rules actually bind; a future diet should follow that evidence, not the line count. |
| 2 | Tier-2 scoping | 2 | `.claude/rules/` carries 9 generated per-element rule files, glob-loaded; `.gemini/rules/` mirrors them. |
| 3 | Build/test commands in root file | 2 | `CLAUDE.md` "Build Commands", including the fast/e2e split and the self-annotate loop. |
| 4 | No AI in prohibited categories | 2 | No cryptographic, regulated-output, or safety-critical paths exist in this codebase; the no-AI list binds at usage time and nothing here falls under it. |

## Part II: Engineering the Guardrails

| # | Row | Score | Evidence / reason |
|---|---|---|---|
| 5 | Annotations on frozen APIs | 2 | `@AILocked` on `generateFiles()`, `@AIContract` on `process()` and the sidecar/writer contracts, `@AICore` on the four load-bearing internals, `@AIThreadSafe` (new) on the four concurrency-proven classes, `@AIPerformance` on the cache hot path. |
| 6 | Regeneration enforced, drift fails build | 2 | CI self-check (`-Pself-annotate -Dvibetags.selfcheck=true`), the example's from-empty byte-for-byte regeneration gate, and check mode in all three example reactors. |
| 7 | >= 3 ArchUnit boundary rules | 2 | `ArchitectureRulesTest`: compiler-free rendering layer, formatter placement, stateless-class rules. |
| 8 | Arch tests in default run | 2 | Untagged, so they run under plain `mvn test` and every CI leg. |
| 9 | Living diagram regenerates every build | 2 | New `diagrams` CI job: code-karta SVGs regenerate and drift fails the build. The gate found real drift on introduction (4 stale SVGs), which is the argument for it. |
| 10 | Spec directory | 2 | `docs/` is the constitution (the book allows `.specs/` or `docs/`); `SPEC.md`/`PLAN.md` are the archived historical specs, routed from ARCHITECTURE.md's Design History. |
| 11 | BDD feature files | 1 | No Gherkin. Core flows are pinned by 158 JUnit classes including full end-to-end compiles. Deliberate deviation: a Cucumber layer adds a dependency and a dialect for behaviour the E2E suite already states executably. Revisit only if a non-developer stakeholder needs to read the scenarios. |
| 12 | `@AITestDriven` on critical classes | 1 | Not annotated. The failing-test-first policy binds through CLAUDE.md standing rules and the PR template instead. Per the book's own selectivity rule (ch04: annotate the 2-3 categories matching actual failure modes), the annotation is held in reserve. |
| 13 | Instruction evals | 1 | New `evals/` task bank (4 rules, deterministic detectors, floors) wired to PRs touching instruction files. Scored 1, not 2: the repository has no `ANTHROPIC_API_KEY` secret (verified on this PR's first run, where the workflow skipped loudly), so in CI the bank currently measures nothing. Becomes a 2 the day the secret exists. v1 measures binding power only. |

## Part III: The Practice

| # | Row | Score | Evidence / reason |
|---|---|---|---|
| 14 | Skills forged from the codebase | 2 | 8 project skills citing real paths and measured numbers (`load-tests`, `add-platform`, `release`, `consumer-regression-suite`, new `consultation-loop` and `correctness-hunt`, ...). |
| 15 | Context as runtime invariant | 2 | Lean root file relative to the docs it routes; heavy work delegated to scoped subagents and on-demand skills; lean-ctx on the maintainer's side. |

## Part IV: Handling the Hard Stuff

| # | Row | Score | Evidence / reason |
|---|---|---|---|
| 16 | Concurrency detector on shared state | 2 | async-test-lib `@AsyncTest` on `WriteCache`, `ModuleSidecar`, `GuardrailFileWriter`, `VibeTagsLogger`; each class now carries `@AIThreadSafe` naming the proving test. |
| 17 | Performance contract | 1 | `@AIPerformance` declares the cache budget; `load-tests/` holds committed per-release baselines and CI runs the sweeps. No automated baseline comparison, deliberately: the harness's own documentation shows 1.17x-1.93x wall-clock noise between identical runs on shared hardware, so only same-session allocation comparisons are honest, and those are a release-time skill step, not a per-commit gate. |
| 18 | Expand-contract migrations | 2 | No database; the persisted formats (`.vibetags-cache` header version with discard-on-newer, sidecar v2, locks `format:1`) are versioned, and `docs/RELEASING.md` now carries the data-at-rest rule for behaviour-changing fixes. |
| 19 | Contract tests per external API | 2 | No external APIs consumed. The equivalent risk is downstream: the consumer regression suite builds five real consumer repositories against a candidate version before release. |
| 20 | OpenAPI versioned + registered | 2 | No HTTP surface. The wire contract is the generated-file formats, pinned by the e2e suite and `BuildVersionParityTest`. |
| 21 | Supply-chain gate | 2 | Frozen versions in the parent, dependency-review + dependabot, all actions SHA-pinned, and (new) the maven-enforcer shipping-dependency allowlist. Release-age cooldown not adopted: dependency count is 4 shipping artifacts. |
| 22 | Sandbox isolation for autonomous runs | 1 | Maintainer-side (harness worktrees, eval-runner disposable worktrees with an empty config dir). The repository cannot enforce how its maintainer runs agents; the eval harness at least models the discipline. |
| 23 | Untrusted-context handling | 2 | Product side: `Escape.java` centralises interpolation escaping, `MarkerInjectionTest`/`EscapeTest` pin hostile annotation values. Agent side: `.aiexclude`, `.claudeignore` and the ignore-file family are generated and committed. |
| 24 | Attacker agent | 2 | OSS-Fuzz harness fuzzes the marker parser and content merge (the untrusted-input surface) coverage-guided on every push, findings upload as artifacts, no `continue-on-error`. Contract-fuzzing an HTTP surface does not apply. |
| 25 | Tests audited for wrong-reason green | 2 | PIT on demand with measured kill-rates in `docs/WORKFLOW.md`, log events pinned as contracts, `BuildFingerprintMutationTest`, and derived-rule tests (`MultiModuleWholeFileMergeTest`) that fail any renderer missing its declaration. Property-based testing is not in use; noted, not planned. |
| 26 | Guardrails merge-blocking in CI | 2 | Required status checks set on `main` (2026-08-15): the JDK-21 Maven leg, cross-platform legs, locked-files, diagrams. `enforce_admins` is on, so they bind the maintainer too. |
| 27 | Agent provenance in history | 1 | Adopted 2026-08-15: commits carry `Co-Authored-By` and a session link, and the PR template asks for them. History before that date predates the convention; scoring 2 requires the convention to have history. |
| 28 | `@AILocked` merge-blocking | 2 | New `locked-files` CI job runs the shipped action against every PR diff, with `.vibetags-locks` committed and verified current by check mode. |
| 29 | Inquisitor on PRs | 1 | New workflow with the three separations, a versioned prompt, structured gripes, and a dumb-bash verdict gate. Scored 1, not 2: it requires the `ANTHROPIC_API_KEY` secret and is deliberately not a required check until its verdict record earns promotion. |
| 30 | Consultation loop | 2 | `consultation-loop` skill: five scoped adversarial questions to a fresh reviewer, advisory by contract. |
| 31 | Prompt lineage | 1 | Instruction files and reviewer prompts are version-controlled; the PR template records load-bearing intent per change. Ambient session capture (a SessionEnd hook) is a maintainer-environment decision, not made here. |
| 32 | Model pinning and routing | 1 | The two AI workflows pin exact model IDs, and the eval bank doubles as the golden-prompt replay for a model bump. No versioned model roster or routing policy beyond that. |
| 33 | Micro-commit discipline | 2 | PR-based flow with per-concern commits; reload-don't-repair is standing practice. |

## Closed by the same PR that adds this file

Rows 9, 21 (allowlist), 26, 28, 30 from their prior state to 2; rows 13 and 29 from 0 to 1
(their remaining point is the `ANTHROPIC_API_KEY` secret and, for 29, required-check
promotion); plus the row-27 convention. The diagram drift gate and the orphan-docs test both
went red on introduction against the real repository (4 stale SVGs; 12 orphaned docs), and the
locked-files guard's first run flagged its own dogfooding PR, exposing an over-broad
lock-stripping rule that was then scoped to source files. Three gates, three real findings on
day one: the difference between installing a gate and installing a decoration.

## Deferred decisions (the maintainer's, not an agent's)

1. **CODEOWNERS.** Branch protection currently has `require_code_owner_reviews: true`,
   `required_approving_review_count: 0`, `enforce_admins: true`, and no CODEOWNERS file, so the
   code-owner requirement is vacuous. Adding a CODEOWNERS file could make the sole maintainer's
   own PRs unmergeable (an author cannot approve their own PR) with admin enforcement on.
   Decide the two settings together; do not add the file alone.
2. **Promoting the Inquisitor to a required check**, once its gripe record justifies blocking.
3. **A `CLAUDE.md` diet**, driven by eval evidence (row 1) rather than the line count.
4. **BDD scenarios** (row 11) if a non-developer audience for them ever exists.
5. **SessionEnd lineage capture** (row 31) in the maintainer's own harness config.
6. **The `ANTHROPIC_API_KEY` repository secret.** Without it the Inquisitor and the
   instruction evals skip on every PR (loudly, but skipped is not passed). Adding it spends
   real API money per PR: an eval run is 12 headless sessions, an Inquisitor run is one
   review of the diff. Rows 13 and 29 each gain their remaining point when it exists.

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
