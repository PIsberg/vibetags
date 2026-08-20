---
name: bump-dependencies
description: Bump every third-party dependency, plugin and toolchain pin in VibeTags to its latest stable release, mirror the pins Gradle cannot inherit, verify with the real gates, and open the PR. Use when the user says "bump dependencies", "update dependencies", "dependency update", "upgrade deps", "are we on the latest versions", or before a release.
---

# Bump dependencies

Every third-party version lives once, as a `<name.version>` property in
`vibetags-parent/pom.xml`; the managed poms inherit it and `BuildVersionParityTest` fails
the build if a literal creeps back in or a Gradle coordinate drifts. What that test cannot
see is whether the pinned version is *current*, which is what this skill is for.

## Step 1 - Branch, then report

```bash
git fetch origin && git checkout -b chore/bump-dependencies-$(date +%F) origin/main
scripts/bump-dependencies.sh            # read-only; add --include-prereleases to see betas
```

The script prints one line per property with its Maven Central latest, then the toolchains
pinned outside the parent (Gradle wrapper, Kotlin, Groovy, Scala 2.13). It exits 2 if the
parent gains a `*.version` property the script has no Maven Central path for: add the path
to its `PINS` table in the same change, so the report can never silently go stale.

## Step 2 - Decide

- Apply stable releases only. Never an alpha, beta, milestone (`M1`), RC or `-ea`, even
  when Maven Central marks it `<latest>`; the report already hides them by default.
- Read the release notes of anything with a major bump before applying it. Plugins that
  change how the build runs (compiler, surefire, enforcer, spotbugs, pmd, error-prone,
  nullaway) can turn a green build red for reasons unrelated to the code; that is not a
  reason to skip them, it is a reason to run the full gates below.
- Kotlin: `examples/kotlin` uses kapt; a Kotlin bump is verified by that example's Gradle
  build, nothing less.

## Step 3 - Apply, including the mirrors

Edit the property in `vibetags-parent/pom.xml`. Then the places that cannot inherit it:

| Pin | Also lives in |
|-----|---------------|
| `junit.version`, `junit.platform.version`, `mockito.version`, `archunit.version`, `async-test-lib.version`, `snakeyaml.version`, `logback.version`, `slf4j.version`, `jspecify.version` | `vibetags/build.gradle` (coordinates; `BuildVersionParityTest` enforces) |
| `pmd.version` | `toolVersion` in `vibetags/build.gradle` and `vibetags-annotations/build.gradle` (enforced) |
| `maven-compiler-plugin.version` | literals in `examples/basic/pom.xml`, `examples/multimodule/pom.xml`, `examples/multimodule-indexed/pom.xml`, `examples/all-tiers/pom.xml`, `tools/demo/pom.xml` (consumer poms; keep them in step) |
| Gradle wrapper | `gradle/wrapper/gradle-wrapper.properties` in `vibetags`, `vibetags-annotations`, `example`, `examples/kotlin`, `examples/groovy`, `examples/scala`; the version named in `docs/DEPENDENCIES.md` |
| Kotlin | `examples/kotlin/build.gradle.kts` (`jvm` and `kapt`), the snippets in `README.md` and `examples/kotlin/README.md` |
| Groovy, Scala | `examples/groovy/build.gradle`, `examples/scala/build.gradle` (Scala stays on the 2.13 line: the example is about Java-only support) |
| pre-commit hook revs | `python -m pre_commit autoupdate`; the `checkstyle` hook runs in Docker, so unless Docker is available revert its rev and say so - an unverifiable bump is not a verified one |

Never touch `<revision>`: that is the VibeTags release version, owned by `scripts/set-version.sh`
and the `release` skill.

## Step 4 - Verify with the gates CI runs

From the repo root, in this order (each depends on the previous install):

```bash
(cd vibetags-annotations && mvn -B install -DskipTests)
(cd vibetags && mvn -B clean install -Pe2e)          # the whole suite; BuildVersionParityTest is in it
(cd vibetags-bom && mvn -B install) && (cd vibetags-cli && mvn -B install)
(cd load-tests && mvn -B test-compile)
(cd vibetags && ./gradlew clean compileTestJava --no-daemon)   # the Gradle side resolves the new pins
(cd examples/kotlin && ./gradlew clean build --no-daemon)       # kapt against the new Kotlin
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
