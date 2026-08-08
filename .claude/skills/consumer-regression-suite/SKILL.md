---
name: consumer-regression-suite
description: Build every downstream consumer of VibeTags against a chosen VibeTags version and report which ones actually pass. Use when the user says "regression suite", "consumer sweep", "test the consumers", "check the downstream repos", "does the new version break anything", or before cutting a VibeTags release.
---

# Consumer regression suite

VibeTags' own 1537 tests say the processor works. They say nothing about whether a real
consumer still builds. This skill answers that second question, for every Java repo under
`../` that depends on VibeTags.

The executable core is `scripts/consumer-sweep.sh` in this repo. This document is the
judgement around it: what to run, what a result means, and what not to believe.

## Step 1 — Decide which VibeTags the sweep is testing

Two different questions, and they need different setups. Ask which one is wanted if it is
not obvious from the request.

**"Does the released version still work?"** Use the published version directly. Nothing to
install; consumers resolve it from Maven Central.

**"Does what we are about to release still work?"** `main` is usually ahead of the newest
tag, so the published artifact is not the code under test. Check first:

```bash
git -C <vibetags> log "$(git -C <vibetags> tag --sort=-v:refname | head -1)"..main --oneline
```

If that is non-empty, build and install `main` locally:

```bash
cd vibetags-annotations && mvn install -DskipTests
cd ../vibetags         && mvn install -DskipTests
cd ../vibetags-bom     && mvn install
```

Installing `main` under a version number that is also on Central makes the local copy differ
from the published one for every later build on this machine. Say so, and offer the cleanup
in Step 5. Installing under a fresh `-SNAPSHOT` avoids it but means no consumer PR can pin
the result.

## Step 2 — Run the sweep

```bash
bash scripts/consumer-sweep.sh <version>            # every consumer
bash scripts/consumer-sweep.sh <version> blindbean  # one
```

Per repo it fetches, branches off `origin/main`, rewrites every place that repo declares the
VibeTags version, builds with the repo's own wrapper, and prints the build's real exit code.
It commits nothing, pushes nothing and opens nothing.

Consumers, and how each declares the version:

| repo | build | declares the version in |
|---|---|---|
| `blindbean` | Maven (`mvnw`) | `pom.xml` |
| `codekarta` | Maven + Gradle | `pom.xml` **and** `build.gradle.kts` — both, kept in sync |
| `common-license-lib` | Maven + Gradle | `pom.xml` **and** `build.gradle.kts` |
| `skill3` | Gradle | `build.gradle` |
| `async-test-lib` | Maven + Gradle | `pom.xml` only; Gradle reads it from the POM |

Add a repo by adding a row to `CONSUMERS` in the script, not by running it by hand.

## Step 3 — Read the results honestly

A red result is a claim about VibeTags, and most red results are not. Before reporting any
failure as a regression:

**Rerun it on the base.** Check out `origin/main` with its existing pinned version and run
the same command. A test that fails both ways is the consumer's problem, and saying otherwise
sends someone hunting a bug that is not there. `blindbean`'s
`FheAsyncConcurrencyTest.concurrentBfvOperationsAreThreadSafe` fails roughly one run in three
on both the old and new version — it is a 60-second timeout in a thread-safety test and it
flakes on a loaded machine.

**Compare like with like.** A repo that builds with both Maven and Gradle must be compared
tool-for-tool. Running Maven on the new version and Gradle on the base produced a convincing
three-file "guardrail drift" here that was entirely an artifact of the comparison.

**Distinguish content drift from line-ending churn.** On Windows, `git status` lists a file
whose line endings moved even when its text did not. `git diff --numstat` compares after
git's normalisation, so an empty numstat with a dirty status is EOL churn, not drift. Only a
non-empty numstat means the new VibeTags renders something different — which is a real
finding, because the consumer's committed guardrail files are then stale and its check-mode
gate will fail.

**A first build in a fresh worktree is not evidence.** It can touch files that every
subsequent build leaves alone. Reproduce anything surprising before reporting it.

## Step 4 — Fix in VibeTags, not in the consumer

If the sweep finds a genuine regression, the fix belongs in VibeTags with a regression test in
the VibeTags suite. Working around it in the consumer hides it from the next consumer. Adding
a validation check is a line in `ValidationRules.PAIRS`; the other invariants are in
`CLAUDE.md` and `docs/LOAD-BEARING.md`.

## Step 5 — Hand it over, do not finish it

Report a table: repo, pass or fail, and for every failure whether it reproduces on the base.
Report skipped and not-run separately from passed.

Then stop. Opening the consumer PRs is a separate, deliberate act:

- A consumer cannot pin a version that is not published. If the sweep tested an unreleased
  `main`, the consumer bumps have to wait for the release, and saying this out loud is part
  of the report.
- Each consumer PR is one bump in one repo. Do not fold in whatever else that repo's `main`
  is missing.
- `async-test-lib` is swept in a `git worktree` rather than a checkout, because another agent
  works in that tree and switching its branch underneath them is destructive. Keep it that
  way. Clean up with `git -C ../async-test-lib worktree prune`.

If `main` was installed locally over a published version, offer to purge it so a later build
resolves the real artifact:

```bash
rm -rf ~/.m2/repository/se/deversity/vibetags/*/<version>
```

## Traps this suite has already paid for

- The Maven on PATH here is 3.8.6 and `blindbean`'s enforcer requires 3.9.0+. Always prefer
  the repo's `./mvnw`; only `blindbean` ships one.
- `verify` is a Maven phase, not a Gradle task. A repo built both ways needs two commands.
- `cmd | tail` reports `tail`'s exit code. Every build in the script writes to a log and reads
  `$?` directly.
- `sed -i` rewrites a file even when the pattern matches nothing, converting CRLF to LF on
  Windows and inventing drift in files the bump never needed to touch.
- Sweeping a repo with uncommitted work either fails or drags that work into the branch. The
  script skips dirty repos on purpose; do not force past it.
- **Run this repo's gates after `git add`, not before.** `ReleaseScriptCoverageTest` reads
  `git ls-files`, so a brand-new file is invisible to it while untracked. A local
  `mvn verify -Pe2e` went green on these very files and CI then failed on all 17 jobs, because
  both of them quoted a real release version in an example. Never put a literal release version
  in a new file: say `<version>`.
- Six PRs opening at once made Maven Central answer 429 and failed an unrelated consumer job.
  A resolution failure naming artifacts you did not touch (`junit-bom` here) is infrastructure;
  rerun it rather than investigating it.
