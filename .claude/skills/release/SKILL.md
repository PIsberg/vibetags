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
cd examples/basic && mvn -q clean compile && cd ../..
cd examples/multimodule && mvn -q clean test-compile && cd ../..
cd examples/multimodule-indexed && mvn -q clean compile && cd ../..
```

`vibetags-cli` is in that list because it is published too, and it depends on the processor
as a library. Both reactor examples are there because they exercise the sidecar merge, which
the single-module `example` cannot reach.

`test-compile`, not `compile`, for `examples/multimodule`, and the difference is not a
detail. Its `tests` module has only `src/test/java`, so a `compile` never runs the processor
over that module, its sidecar goes stale, and the root merge drops the whole
`VIBETAGS-MODULE: tests` region from all 18 generated files. That reads exactly like the
drift this step exists to catch: 18 files, hundreds of deleted lines, on the one step whose
whole job is to notice a rendering change. It has happened, and cost a round
of investigation to reduce to a wrong verb. If the region is missing and nothing else moved,
re-run this with `test-compile` before believing it.

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

## Step 5c — Re-check every platform path against its vendor

Required. The build cannot notice that a vendor retired a product or stopped documenting a path,
so this is the only check that does. Follow docs/RELEASING.md step 0b: list the paths with
`python corpus/check-platforms.py list vibetags/src/main/java/se/deversity/vibetags/processor/internal/ServiceRegistry.java`,
then confirm each against the vendor's own documentation, never a cross-tool round-up.

Report per tool: still documented, renamed, retired, or **could not check**. A retired or
undocumented output is deprecated through `DeprecatedServices` and the PLATFORMS.md table, never
removed in a minor release. If this step finds something, say so in the release notes; if it
could not run, say that instead of calling it clean.

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

Only once the user confirms the PR is merged. `tools/release-notes.sh` extracts the
section from the CHANGELOG and rewrites the relative image paths to absolute raw URLs
pinned to the tag, because GitHub resolves `changelog-assets/…` from the repo root on a
release page and an unrewritten link 404s there:

```bash
TAG=v<version>
tools/release-notes.sh "${TAG#v}" > "$SCRATCHPAD/release-notes-${TAG}.md"
```

Do not inline the extraction here. It was an `awk` range in this file until #619, and an
awk range tests its end pattern against the line that opened it, so `/^## \[/` closed the
range on the heading and the command emitted one line: the correct release title with the
whole body missing. `gh release create --notes-file` accepts that without complaint, and by
the time anyone reads the release page the tag exists and the Central deploy has fired.
`docs/RELEASING.md` had already hit this at 1.2.3 and had already been corrected, with a
warning against the range form; this file kept the broken copy, and this file is the one an
agent follows. One script, called from both, is why that cannot drift a third time. The
script refuses to emit fewer than five lines rather than let a truncated file reach an
irreversible step.

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
