---
name: correctness-hunt
description: Run a bug hunt against this repository under a fixed work order - baseline the suite, hunt with named bug-shape lenses, failing-test-first, one commit per bug, suspicions reported separately, locks escalated never overridden. Use when the user says "correctness hunt", "bug hunt", "find bugs", "hunt for bugs", or asks for a systematic sweep for defects rather than a review of a specific diff.
---

# Correctness hunt

A hunt is not "look for bugs". It is a fixed, repeatable work order. Findings that skip a
step do not count.

## The work order

1. **Pull latest and branch.** Fresh branch per hunt (`fix/hunt-<date>` or similar), never
   the default branch, based on up-to-date `origin/main`.
2. **Baseline before touching anything.** From `vibetags/`: `mvn test -Pe2e`, in the
   background with output to a log (the run takes minutes and must not be truncated by a
   tool timeout). Record the counts. A hunt against a red suite cannot separate findings
   from pre-existing breakage; if the baseline is red, the hunt's first bug is the baseline.
3. **Hunt with named lenses**, not vibes. Each lens is a yes/no question asked of specific
   code. The standing lens library, from the shapes that have actually bitten:
   - **Drained evidence**: does any check read state that legitimately drains between the
     moment of recording and the moment of judging? (The fingerprint/sidecar mtime coupling
     is this repo's precedent.)
   - **Re-registration wipes**: does re-running a registration/collection path clear state
     a sibling still needs? (Issue #365, granular files with multiple authors.)
   - **Identity from prose**: is anything keyed on a human-readable string that is free to
     reword, rather than on a structural field?
   - **One-throwing-participant-discards-all**: does a loop over rules/renderers/modules
     let one throwing member abort or silently drop the rest?
   - **Green for the wrong reason**: would this test still pass if the mechanism it names
     were deleted? Break it deliberately and watch; one commit and a revert.
   - **Synthetic half-lifecycles**: does a test drive only acquire/open/start and never
     release/close/stop? Cold-start and departed-module scenarios are the precedents here
     (issue #383).
4. **Failing test first, or it does not count.** No red test, no bug. Watch it fail, and
   read the failure: red for the intended reason, not an unrelated exception. New tests go
   in the existing suite and style so CI runs them; tag `@Tag("e2e")` if they exceed the
   fast-tier budget (rule in `docs/TESTS.md`).
5. **One commit per bug**: the failing test and its fix together, independently reviewable
   and independently revertible.
6. **Full gates at the end of the branch**: `git add` everything first (some gates read
   `git ls-files` and pass vacuously on untracked files), then `mvn test -Pe2e` and
   `pre-commit run --all-files`.
7. **Propose, never merge.** Push the branch, open the PR, stop.

## Hard rules

- **Suspicions are not bugs.** A finding without a red test goes in the PR body under a
  separate "Suspicions, unresolved" heading with a one-line reason it was held back. It is
  never committed and never counted.
- **Data-meaning changes escalate.** A fix that reinterprets already-persisted values (the
  `.vibetags-cache` format, the `.vibetags-mod-*` sidecar format, `.vibetags-locks`) is a
  compatibility decision, not a code fix. Ask first: does correct data exist under the old
  behavior? If yes, write it up and hand it over unresolved.
- **Locks bind the hunter too.** A correct fix behind `@AILocked` (see `<locked_files>` in
  `CLAUDE.md`) is escalated, not applied, regardless of confidence.
- **The PR is a review document.** Per bug: file, defect (behavioral fact), concrete
  failure scenario, fix rationale, commit SHA. The PR template's verification section is
  filled with what actually ran.
