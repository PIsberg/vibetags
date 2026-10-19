---
name: bump-dependencies
description: Bump every third-party dependency, plugin and toolchain pin in VibeTags to its latest stable release, mirror the pins Gradle cannot inherit, verify with the real gates, and open the PR. Use when the user says "bump dependencies", "update dependencies", "dependency update", "upgrade deps", "are we on the latest versions", or before a release.
---

# Bump dependencies

Every third-party and toolchain version lives once, as a property in
`vibetags-parent/pom.xml`. The managed poms inherit it, the Gradle builds read it, and the
files that cannot read a pom (Gradle wrappers, standalone examples, workflow `env`, README
snippets) carry copies listed in `vibetags-parent/version-mirrors.txt`.
`BuildVersionParityTest` fails the build if a literal creeps back in or any copy drifts.
What that test cannot see is whether the pinned version is *current*, which is what this
skill is for.

## Step 1 - Branch, then report

```bash
git fetch origin && git checkout -b chore/bump-dependencies-$(date +%F) origin/main
tools/bump-dependencies.sh            # read-only; add --include-prereleases to see betas
```

The script prints one line per property with its latest stable release: Maven Central for
most, plugins.gradle.org for the foojay plugin, services.gradle.org for Gradle, nodejs.org for
the Node LTS, and a release line for Scala (2.13) and the publishing Maven (3.x). It exits 2 if
the parent gains a `*.version` property the script has no source for, and
`BumpDependenciesTableTest` fails first: add a row to its `PINS` table in the same change.

## Step 2 - Decide

- Apply stable releases only. Never an alpha, beta, milestone (`M1`), RC or `-ea`, even
  when Maven Central marks it `<latest>`; the report already hides them by default.
- Read the release notes of anything with a major bump before applying it. Plugins that
  change how the build runs (compiler, surefire, enforcer, spotbugs, pmd, error-prone,
  nullaway) can turn a green build red for reasons unrelated to the code; that is not a
  reason to skip them, it is a reason to run the full gates below.
- Kotlin: `examples/kotlin` uses kapt and `examples/kotlin-ksp` compiles the same sources through
  KSP; a Kotlin or KSP bump is verified by both examples' Gradle builds, nothing less.
- Maven for publishing is `publish-maven.version` (copied into `publish.yml`). Under Maven 3.10, plugin 0.11.0 built bundles
  Central rejected (#945); 0.12.0 fixed that, so the pin moved to 3.10.0. The `Central Bundle Shape`
  check on the PR is what verifies a bump of either the pin or the plugin; a green `build-maven`
  says nothing about publishing.

## Step 3 - Apply, then sync the copies

Edit the property in `vibetags-parent/pom.xml` and nothing else, then:

```bash
python tools/sync-version-mirrors.py           # rewrites every copy, prints each one it changed
python tools/sync-version-mirrors.py --check   # exit 1 if a copy is stale; what the test enforces
```

`vibetags-parent/version-mirrors.txt` is the list of copies. A new file that has to carry a
copy (a new example, a new snippet) gets a row there; a row that stops matching anything fails
`BuildVersionParityTest`, so a moved file cannot quietly drop out. What the sync cannot do:

| Pin | By hand |
|-----|---------|
| `publish-maven.version` | change `publish-maven.sha512` beside it. Take the SHA-512 from `https://archive.apache.org/dist/maven/maven-3/<v>/binaries/apache-maven-<v>-bin.tar.gz.sha512` and compare it with `sha512sum` of the archive downloaded from Maven Central, which is where the workflow fetches it |
| `ksp.version` | `kotlin-stdlib.version` and `kotlinx-coroutines.version` follow what `symbol-processing-api` declares, so re-read its pom |
| `scala.version` | stays on the 2.13 line: the example is about Java-only support |
| pre-commit hook revs | `python -m pre_commit autoupdate`; the `checkstyle` hook runs in Docker, so unless Docker is available revert its rev and say so - an unverifiable bump is not a verified one |
| GitHub Actions, npm CLIs | Dependabot owns them (`docs/DEPENDENCIES.md`, "Pinned outside the parent") |

Never touch `<revision>`: that is the VibeTags release version, owned by `tools/set-version.sh`
and the `release` skill.

## Step 4 - Verify with the gates CI runs

From the repo root, in this order (each depends on the previous install):

```bash
(cd vibetags-annotations && mvn -B install -DskipTests)
(cd vibetags && mvn -B clean install -Pe2e)          # the whole suite; BuildVersionParityTest is in it
(cd vibetags-bom && mvn -B install) && (cd vibetags-cli && mvn -B install)
(cd load-tests && mvn -B test-compile)
(cd vibetags && ./gradlew clean compileTestJava --no-daemon)   # the Gradle side resolves the new pins
(cd vibetags-ksp && mvn -B install)                             # the KSP front end against the new KSP
(cd examples/kotlin && ./gradlew clean build --no-daemon)       # kapt against the new Kotlin
(cd examples/kotlin-ksp && ./gradlew clean build --no-daemon)   # KSP; must match examples/kotlin byte for byte
(cd examples/groovy && ./gradlew clean build --no-daemon)
(cd examples/scala && ./gradlew clean build --no-daemon)
(cd examples/basic && ./gradlew clean build --no-daemon)
git add -A && SKIP=checkstyle python -m pre_commit run --all-files
```

Report each result as passed, failed (with the output) or not run. A Gradle build that
was not run because a toolchain was missing is "not run", never "passed". A tool bump that
turns a static-analysis gate red may be the tool, not the code: rerun on the JDK CI uses
before concluding a defect.

## Step 5 - Record and hand over

- `docs/CHANGELOG.md`, under `[Unreleased]` / `Changed`: one entry listing every pin that
  moved, old and new, and which gates verified it.
- Commit, push, open the PR with the report output and the verification list in the body,
  then follow CI until it is green. Do not merge.
