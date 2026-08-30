---
name: release
description: Cut a new VibeTags release — bump the version everywhere, update the CHANGELOG, open the release PR, and create the GitHub release that triggers Maven Central publishing. Use when the user says "release", "/release", "cut a release", "publish a new version", or asks to bump VibeTags to a new version.
---

# Release VibeTags

Drives the release process documented in `docs/RELEASING.md`. That document is the
source of truth; this skill is the executable path through it. If the two ever
disagree, `docs/RELEASING.md` wins — and say so rather than silently improvising.

## Step 1 — Establish the current version and ask for the next one

The single source of truth for "what version are we on" is the `<revision>`
property in `vibetags-parent/pom.xml`:

```bash
sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' vibetags-parent/pom.xml | head -n1
git tag --sort=-v:refname | head -5
```

Then **ask the user which version to release** with `AskUserQuestion`. Compute the
candidates from whatever the current version turns out to be — do not hardcode them:

- If the current version is a release candidate (`X.Y.Z-RCn`), offer promoting it to
  final (`X.Y.Z`) and cutting the next candidate (`X.Y.Z-RC<n+1>`).
- If the current version is final, offer the patch, minor, and major bumps, and a first
  release candidate for the next minor if that fits what is in `[Unreleased]`.

Always include the semantic-versioning meaning in each option's description. The user
can also type any version via "Other". Never guess and proceed — the version is always
the user's call.

Sanity-check the answer before continuing: it must be a release version (no
`-SNAPSHOT` — Maven Central rejects those), and `git tag -l "v<version>"` must come
back empty.

## Step 2 — Preflight

Refuse to continue and tell the user what is wrong if any of these fail:

```bash
git status --porcelain          # must be clean
git rev-parse --abbrev-ref HEAD # note the branch
gh auth status                  # gh must be authenticated
```

Then:

```bash
git checkout main && git pull
git checkout -b release/v<version>
```

## Step 3 — Bump the version

`tools/set-version.sh` does the whole bump in one pass (it reads the current
version fresh from the parent, so it is idempotent):

```bash
tools/set-version.sh <version>
```

That rewrites `<revision>` in `vibetags-parent/pom.xml` — which every managed pom
inherits its version from, so `vibetags-annotations/pom.xml`, `vibetags/pom.xml`,
`vibetags-bom/pom.xml` and `load-tests/pom.xml` need no edit at all — plus the
places that cannot inherit it: both `build.gradle` files, the copy-pasteable
snippets in the `<description>` blocks, and the standalone example/demo poms.

Then confirm nothing was missed, rather than assuming. Install the annotations at the new
version first, or the test cannot run at all — `vibetags` now depends on
`vibetags-annotations:<version>`, which does not exist in the local repository until you
build it, and the failure is a dependency-resolution error rather than a parity one:

```bash
cd vibetags-annotations && mvn -q install -DskipTests && cd ..
cd vibetags && mvn test -Dtest=BuildVersionParityTest
```

That test fails if any Gradle file, example pom or managed pom disagrees with
`<revision>`. It exists because the previous script skipped the examples and
`load-tests/`, and `load-tests/` consequently sat two releases behind while CI
believed it was benchmarking the branch.

It also updates the consumers, which track a *released* BOM version: `examples/basic/pom.xml`,
`examples/basic/build.gradle`, `examples/multimodule/pom.xml`, `examples/multimodule-indexed/pom.xml`,
`examples/all-tiers/pom.xml`, `tools/demo/pom.xml`, the Kotlin/Groovy/Scala example builds,
`vibetags-cli/pom.xml`, `README.md` and `.claude/skills/vibetags-usage/SKILL.md`. It prints
every file it touched, so read that list rather than assuming this one is current.

Do not hand-edit those files first. This section used to say the script left the consumers
alone and listed eight of them to edit by hand; the script had grown to cover them and the
instruction had not, so following it meant editing over work already done and then wondering
why the diff looked strange. `BuildVersionParityTest` is the check that settles it either
way — run it and believe it, rather than either this list or the script's output.

Leave `load-tests/pom.xml` alone. Its `<processor.version>` is pinned independently
so benchmarks can compare across versions — force-bumping it is a bug, not a chore.

Do not hand-edit `AIGuardrailProcessor.VERSION`. It is `ProcessorVersion.get()`, which
resolves from the jar manifest at class load — bumping `vibetags/pom.xml` and
`vibetags/build.gradle` is what moves it.

Verify the sweep caught everything:

```bash
grep -rn "<old-version>" --include="*.xml" --include="*.gradle" --include="*.md" \
  --include="*.java" --exclude-dir=.git --exclude-dir=target --exclude-dir=build \
  --exclude-dir=results --exclude-dir=node_modules --exclude-dir=changelog-assets .
```

Expected leftovers, which are correct and must stay: the old version's `docs/CHANGELOG.md`
entry, frozen `load-tests/results/<old>/` baselines, `load-tests/pom.xml`, and
`load-tests/dependency-reduced-pom.xml` (regenerated by maven-shade on the next build).
Anything else is a miss — fix it.

`--exclude-dir=.git` matters: `.git/PR_BODY.md` and `.git/RELEASE_NOTES.md` are scratch
files left by earlier releases and full of stale versions. They are not part of the repo
and must never be edited.

## Step 4 — Update the CHANGELOG

Edit `docs/CHANGELOG.md`:

1. Rename `## [Unreleased]` to `## [<version>] - <today's date, YYYY-MM-DD>`.
2. Add a fresh empty `## [Unreleased]` section above it.
3. Update the comparison links at the bottom of the file.

If the `[Unreleased]` section is empty, stop and ask the user — a release with no
changelog entries usually means something went wrong upstream.

## Step 5 — Build in order and verify

Build order is load-bearing (`vibetags-annotations` → `vibetags` → `vibetags-bom`),
because there is no parent POM tying the modules together:

```bash
cd vibetags-annotations && mvn -q install && cd ..
cd vibetags && mvn -q clean install && cd ..
cd vibetags-bom && mvn -q install && cd ..
cd vibetags-cli && mvn -q clean install && cd ..
cd examples/basic && mvn -q clean compile && cd ..
cd examples/multimodule && mvn -q clean compile && cd ..
cd examples/multimodule-indexed && mvn -q clean compile && cd ..
```

`vibetags-cli` is in that list because it is published too, and it depends on the processor
as a library. Both reactor examples are there because they exercise the sidecar merge, which
the single-module `example` cannot reach.

Then check that the examples produced **no** guardrail-file drift:

```bash
git status --porcelain    # only version bumps and the CHANGELOG should appear
```

This is the check worth not skipping. The processor version feeds `BuildFingerprint`, so a
release invalidates every consumer's fingerprint and reruns the whole generate phase: if any
rendering changed, the examples' committed output moves and you find out here rather than in
a consumer's diff. A clean result is what justifies claiming the examples regenerate
byte-for-byte, which the changelog entry usually does.

The `example` compile is the real end-to-end check: it consumes the freshly installed
BOM and triggers annotation processing. If it fails, the release is not ready — report
the failure and stop.

Also run the pre-commit hooks, since the repo enforces them:

```bash
pre-commit run --all-files
```

The `checkstyle` hook runs in a Docker image, so on a machine without a running Docker
daemon that command aborts before any hook runs — including the two that matter most here,
gitleaks and the whitespace fixers. Run the rest rather than skipping the step entirely:

```bash
SKIP=checkstyle pre-commit run --all-files
```

Checkstyle itself is not lost by that: it is bound to the `validate` phase, so the Maven
build above already ran it over the whole project. Report which hooks actually ran — a
skipped gate is not a passed gate, and "pre-commit passed" is the wrong thing to say when
only three of four did.

## Step 5b — Sweep the consumers, before the release exists

Required. Do not skip it because the build is green: the build is green against this
repository's fixtures and the third-party corpus, and neither of those is a project that
already has committed VibeTags output.

```bash
tools/consumer-sweep.sh <version>
```

**What you are looking for is not "did it build".** It is whether the version being cut
changes the *content* of files those repositories have already committed. The sweep reports
drift in generated guardrail files, and drift is the finding.

Why this step exists. #480 changed the element identity written into `.vibetags-locks`, into
every `path=` attribute and into granular rule *filenames*: type-use annotations are no longer
part of it. For a project using jspecify or the Checker Framework that moves committed files.
Nothing noticed before release, for two independent reasons, and both are the normal case
rather than bad luck:

- **This repository could not notice.** It uses jspecify in 47 files, but never on a parameter
  of a method carrying an `@AI*` annotation, which is the only place a parameter type reaches
  an element path. Self-annotate had nothing to show.
- **The consumers could not notice.** They are pinned to the previous release, so they had
  never run the change, and this sweep is the only thing that would have run it for them.

So the release is the moment the consequence becomes real, which is why the check belongs
here rather than in a nightly job nobody reads.

Report the result honestly, per repository:

- **No drift** — say so, and carry on.
- **Drift** — do not silently proceed. Say which repositories are affected and what changed,
  and put it in the CHANGELOG and the release notes in the consumer's language: "if you use
  jspecify or the Checker Framework, your generated files will move on this upgrade." A
  maintainer who finds a diff they did not cause, with no note explaining it, has to work out
  from scratch whether their build is broken.
- **Could not run** — a repository missing, a build failing for its own reasons — is neither
  of the above. Report it as not run. A skipped consumer is not a passing consumer.

The script leaves each repository on a sweep branch with the bump uncommitted, and commits,
pushes and opens nothing. Leave it that way.

## Step 6 — Commit and open the PR

```bash
git add -A
git commit -m "chore: prepare release v<version>"
git push -u origin release/v<version>
gh pr create --base main --title "chore: prepare release v<version>" --body "…"
```

Write the PR body from the new CHANGELOG section. Then **stop and hand back to the
user**: they merge the PR once CI is green. Do not merge it yourself, and do not
create the release before the PR is merged — the release tags `main`.

## Step 7 — Create the GitHub release (after the PR is merged)

Only once the user confirms the PR is merged. Extract the release notes from the
CHANGELOG and rewrite the relative image paths to absolute raw URLs pinned to the tag
— GitHub resolves `changelog-assets/…` from the repo root on a release page, so
without the `sed` every embedded image 404s:

```bash
TAG=v<version>
awk "/^## \[${TAG#v}\]/,/^## \[/{if (\$0 ~ /^## \[/ && \$0 !~ /\[${TAG#v}\]/) exit; print}" docs/CHANGELOG.md \
  | sed "s|](changelog-assets/${TAG#v}/|](https://github.com/PIsberg/vibetags/raw/${TAG}/docs/changelog-assets/${TAG#v}/|g" \
  > "$SCRATCHPAD/release-notes-${TAG}.md"
```

Show the user the generated notes and get an explicit go-ahead before publishing —
this step is irreversible and pushes artifacts to Maven Central:

```bash
gh release create $TAG --target main --title "VibeTags $TAG" \
  --notes-file "$SCRATCHPAD/release-notes-${TAG}.md" --latest
```

For a release candidate, use `--prerelease` instead of `--latest`.

## Step 8 — Watch the publish and report

Creating the release triggers `.github/workflows/publish.yml`, which signs and deploys
`vibetags-annotations`, `vibetags-processor`, and `vibetags-bom` to Maven Central:

```bash
gh run watch "$(gh run list --workflow=publish.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Report the outcome plainly. On success, tell the user that Maven Central search and
the README badge lag by roughly 15-30 minutes, and point them at
https://central.sonatype.com/publishing/deployments to watch the deployment. On
failure, surface the actual job log — `docs/RELEASING.md` has a troubleshooting
section covering GPG failures, 401s from the Portal, and stuck validations.
