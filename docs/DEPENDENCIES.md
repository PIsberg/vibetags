# Dependencies

Every third-party artifact VibeTags uses, why it is here, and what happens if it changes.

Versions are not listed here as the source of truth. `vibetags-parent/pom.xml` is, and this
document names the property that holds each one so the two cannot quietly disagree. Where a number
does appear below it is there to make the table readable; if it contradicts the parent, the parent
is right and this file is stale.

## What reaches a consumer

VibeTags is an annotation processor, so "reaches a consumer" means "lands on the annotation
processor path of somebody else's build". Three artifacts do. Nothing else in this repository ever
leaves it.

| Artifact | Property | Why it is here |
|---|---|---|
| `org.jspecify:jspecify` | `jspecify.version` | `@NullMarked`, `@Nullable`, `@NonNull` on the processor's own source, which is what NullAway checks against. The artifact is annotations only, all `SOURCE`/`CLASS` retention, so it adds no runtime behaviour. |
| `org.slf4j:slf4j-api` | `slf4j.version` | The logging facade behind `VibeTagsLogger`. Every diagnostic event in the processor goes through it. |
| `ch.qos.logback:logback-classic` | `logback.version` | The binding that actually writes `vibetags.log`. Without a binding, SLF4J is a no-op and the diagnostic channel described in `CLAUDE.md` produces nothing. |

The two logging artifacts are compile-scope and deliberately **not** `<optional>`. Maven's
`annotationProcessorPaths` resolver drops optional dependencies of a processor, so marking them
optional produces a `NoClassDefFoundError` for `org.slf4j.Logger` at the consumer's compile time.
The same comment sits in `vibetags/pom.xml`; do not undo it in either place alone.

Because they sit on `annotationProcessorPaths` rather than in `<dependencies>`, they are invisible
to the consumer's compile and runtime classpath. A consumer who wires VibeTags in as a plain
compile dependency does get them, which is one more reason `USAGE.md` treats the processor-path
form as the recommended setup and the class-path form as a fallback with caveats.

`vibetags-cli` adds **no third-party dependency**: it is plain JDK code plus
`vibetags-processor` as a library (for `ServiceRegistry` and the marker constants — the one
source of truth for the platform list). `doctor --classpath` reads `@kotlin.jvm.JvmInline` out of
class files with its own constant-pool reader (`JvmInlineClasses`) rather than `kotlin-metadata-jvm`
or ASM: the annotation is all it needs, and either library would be one more artifact for every
launcher of the CLI to resolve (#691). Whoever launches it (jbang, `java -cp`) resolves the
processor's transitive slf4j/logback, which is the full closure.

`vibetags-ksp` adds **no shipping dependency** either. It needs the KSP API
(`com.google.devtools.ksp:symbol-processing-api`, `ksp.version`) and the Kotlin standard library
(`kotlin-stdlib.version`) to compile, both `provided`: KSP loads a processor into a classloader that
already holds both, so shipping them would put a second copy beside KSP's own. Its runtime closure is
`vibetags-processor`'s.

`vibetags-annotations` has **no** third-party dependencies at all, by design. It is on the
consumer's compile classpath, and anything added there is something a consumer's build has to
resolve, shade or exclude.

## What the tests use

All test-scope. Everything in the table is in `vibetags/`; `vibetags-ksp/` adds the three
below it.

| Artifact | Property | Why it is here |
|---|---|---|
| `org.junit.jupiter:junit-jupiter` | `junit.version` | The test framework. |
| `org.junit.platform:junit-platform-launcher` | `junit.platform.version` | Runtime-only. Surefire needs it on the JUnit 6 platform. |
| `org.mockito:mockito-core`, `mockito-junit-jupiter` | `mockito.version` | Faking the javac side (`ProcessingEnvironment`, `Messager`, `Filer`) so processor logic can be tested without a running compiler. |
| `com.tngtech.archunit:archunit-junit5` | `archunit.version` | Enforces the layering invariant: `processor/internal/content/` must not import `javax.lang.model`, `javax.annotation.processing` or `com.sun.source`. See `ArchitectureRulesTest`. This is a rule the compiler cannot express, so a library holds it instead. |
| `se.deversity.async-test-lib` | `async-test-lib.version` | Concurrency tests for the parallel write phase and `WriteCache`. Sibling project, published to Maven Central like any other dependency. |
| `org.yaml:snakeyaml` | `snakeyaml.version` | Parses the six YAML documents the renderers emit. The processor itself must never gain a YAML dependency: it runs on the consumer's annotation processor path, where every extra jar is one more collision. The tests need a real parser because "the file looks right" is not the property that matters; "a strict parser sees every module's guardrails" is. `YamlMergeShapeContractTest` depends on this distinction. |

`vibetags-ksp/` tests run KSP2's standalone engine in-process over real Kotlin sources:
`com.google.devtools.ksp:symbol-processing-aa-embeddable` and `symbol-processing-common-deps`
(`ksp.version`), and `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm`
(`kotlinx-coroutines.version`), which the engine needs at runtime and does not bring itself.

`load-tests/` additionally uses `org.openjdk.jmh:jmh-core` and `jmh-generator-annprocess`
(`jmh.version`) for the benchmark harness. That module pins `<processor.version>` directly rather
than inheriting it, on purpose: comparing one VibeTags release against another is the whole point of
the module, and a BOM that forces both sides to the same version defeats it.

## What the build uses

Nothing in this section ships. It runs.

### Compile, test and package

| Artifact | Property | Why it is here |
|---|---|---|
| `maven-compiler-plugin` | `maven-compiler-plugin.version` | Compiles every module against `maven.compiler.release` 21, and carries Error Prone and NullAway on its `annotationProcessorPaths`. The consumer-shaped poms (`examples/*/pom.xml`, `tools/demo/pom.xml`) carry a copy, see [Toolchains](#toolchains-and-copies). |
| `maven-surefire-plugin` | `maven-surefire-plugin.version` | Runs the tests: the fast tier by default, everything under `-Pe2e` (docs/TESTS.md). |
| `maven-jar-plugin` | `maven-jar-plugin.version` | Builds the jars. In `vibetags` it writes `Implementation-Version` into the manifest, which `ProcessorVersion` reads into `BuildFingerprint`, so a new processor release regenerates every file. |
| `maven-source-plugin`, `maven-javadoc-plugin` | `maven-source-plugin.version`, `maven-javadoc-plugin.version` | The `-sources` and `-javadoc` jars Maven Central requires beside every published jar. |
| `maven-shade-plugin` | `maven-shade-plugin.version` | `load-tests/` only: builds the benchmark fat jar. |
| `exec-maven-plugin` | `exec-maven-plugin.version` | `load-tests/` only: an `exec:java` entry point into `org.openjdk.jmh.Main`. |
| `build-helper-maven-plugin` | `build-helper-maven-plugin.version` | Runs only when `-Dtest` is set: its `regex-properties` goal works out which surefire execution a named test belongs to, so the test does not also run in the other one (#686, docs/TESTS.md). |
| `flatten-maven-plugin` | `flatten-maven-plugin.version` | Resolves `${revision}` and strips `vibetags-parent` out of the deployed POMs, so what consumers download is self-contained. |

### Static analysis

Four tools, each catching something the others do not. All four run in all four modules that
compile Java (`vibetags`, `vibetags-annotations`, `vibetags-cli` and `vibetags-ksp`), and
`BuildToolchainParityTest` fails if one of them drops a tool, because a check that was never
configured cannot fail and so reports nothing when it is missing.

| Artifact | Property | Why it is here |
|---|---|---|
| `maven-pmd-plugin` with `pmd-core`, `pmd-java` | `maven-pmd-plugin.version`, `pmd.version` | Source-level rules and copy-paste detection. All eight java rule categories are imported by `pmd-ruleset.xml`; each exclusion in that file names the rule, its finding count and the reason. The Gradle builds derive their PMD `toolVersion` from `pmd.version`. A red PMD gate is worth checking against the JDK before believing it: PMD run on a newer JDK than the build targets reports confident nonsense. |
| `maven-checkstyle-plugin` | `maven-checkstyle-plugin.version` | Style. Also the `checkstyle` pre-commit hook (`tools/checkstyle-hook.sh` runs this plugin in each module a staged `.java` file belongs to), so it fails before the commit rather than in CI. |
| `spotbugs-maven-plugin` with `findsecbugs-plugin` | `spotbugs-maven-plugin.version`, `findsecbugs-plugin.version` | Bytecode analysis, the security rules included. `vibetags`, `vibetags-cli` and `vibetags-ksp` carry a `spotbugs-exclude.xml`; `vibetags-annotations` deliberately has none, so the absence of that file stays evidence that nothing there has been excused. |
| Error Prone with NullAway | `error-prone.version`, `nullaway.version` | On the compiler's `annotationProcessorPaths`, main sources only. NullAway is what makes the JSpecify annotations load-bearing instead of decorative. It needs the `--add-exports` flags in each module's `.mvn/jvm.config` to reach javac's internals: without them Error Prone does not fail, it silently does not run, which is why those four files are pinned byte-identical. |
| `org.eclipse.jdt:ecj` | `ecj.version` | Never on a classpath. `tools/ecj-degradation-check.sh` (the `ecj` job in `build.yml`) compiles `examples/basic` with it to check the documented claim that the processor degrades rather than fails under a compiler with no Tree API. |

### Coverage, supply chain and publishing

| Artifact | Property | Why it is here |
|---|---|---|
| `jacoco-maven-plugin` | `jacoco-maven-plugin.version` | Line and branch coverage, uploaded to Codecov from CI. |
| `pitest-maven` with `pitest-junit5-plugin` | `pitest-maven.version`, `pitest-junit5-plugin.version` | Mutation testing: whether the tests would notice if the code were wrong. On demand only, via `.github/workflows/mutation.yml`, because it costs minutes rather than seconds. |
| `cyclonedx-maven-plugin` | `cyclonedx-maven-plugin.version` | Emits the SBOM, as JSON only: each published file costs Central's monthly file allowance 4 files with its signature and checksums (#863). |
| `maven-enforcer-plugin` | `maven-enforcer-plugin.version` | Bans any compile- or runtime-scoped dependency outside the allowlist declared in `vibetags-parent`. This document is the ledger of what ships, and the enforcer rule is that ledger's executable form. Test scope is exempt: it never reaches a consumer, and gating it would tax every test-library bump for no risk reduction. |
| `central-publishing-maven-plugin` | `central-publishing-maven-plugin.version` | Uploads the signed bundle to the Central Portal and publishes it (`autoPublish=true`). 0.12.0 is the first that bundles cleanly under Maven 3.10 (#945). |
| `maven-gpg-plugin` | `maven-gpg-plugin.version` | Signs every published file; Central rejects an unsigned bundle. |

## Toolchains and copies

The parent also defines the toolchains, although no Maven build reads them, so that every version
in the repository has one definition. The files that use them cannot read a pom: a Gradle wrapper
reads only its own properties file, the examples are standalone so a reader can lift one into their
own project, a workflow's `env` is fixed before any step runs, and a README snippet is text. So
each of those files carries a **copy**. `vibetags-parent/version-mirrors.txt` lists every place a
copy lives, `tools/sync-version-mirrors.py` writes them all from the parent, and
`BuildVersionParityTest` fails the build when one disagrees or when a row of that list stops
finding its copy.

| Property | What it is for | Copies live in |
|---|---|---|
| `gradle.version` | The Gradle every Gradle build in the repository runs on: the Gradle publishing path of `vibetags` and `vibetags-annotations`, and the nine Gradle examples. | Every `gradle/wrapper/gradle-wrapper.properties`, eleven today (`git ls-files '*gradle-wrapper.properties'`). The checked-in `gradle-wrapper.jar` files are validated against Gradle's published checksums by `.github/workflows/gradle-wrapper-validation.yml`, which must run on every push to main for Scorecard's Binary-Artifacts check to accept them. |
| `kotlin.version` | The Kotlin compiler and Gradle plugin `examples/kotlin` (kapt) and `examples/kotlin-ksp` (KSP) build with. Separate from `kotlin-stdlib.version`, which follows what `symbol-processing-api` declares; this one is a consumer's choice. | Both examples' `build.gradle.kts`, and the snippets in `README.md`, `USAGE.md` and the examples' READMEs. |
| `ksp.version` | The KSP API `vibetags-ksp` compiles against (see above), and the KSP Gradle plugin `examples/kotlin-ksp` applies. | `examples/kotlin-ksp/build.gradle.kts` and the KSP snippets. |
| `groovy.version` | `examples/groovy`, which shows Groovy support through `javaAnnotationProcessing` stubs (docs/JVM-LANGUAGES.md). | `examples/groovy/build.gradle`. |
| `scala.version` | `examples/scala`, which shows the Java-only support scalac allows. Stays on the 2.13 line on purpose. | `examples/scala/build.gradle`. |
| `lombok.version` | `examples/processor-compat`, which builds VibeTags beside Lombok on one processor path. | `examples/processor-compat/pom.xml`. |
| `mapstruct.version` | The same example's MapStruct, API and processor alike. Stays on stable releases; MapStruct marks pre-releases with a dot (`1.7.0.Beta2`). | `examples/processor-compat/pom.xml`. |
| `lombok-mapstruct-binding.version` | Lets MapStruct read the accessors Lombok generates in that example. | `examples/processor-compat/pom.xml`. |
| `foojay-resolver.version` | The `foojay-resolver-convention` settings plugin, which lets a Gradle example download the JDK toolchain its build asks for when the machine has none. | Every `settings.gradle` / `settings.gradle.kts` under `examples/`. |
| `maven-compiler-plugin.version` | See [Compile, test and package](#compile-test-and-package). | `examples/*/pom.xml`, `tools/demo/pom.xml`. |
| `publish-maven.version`, `publish-maven.sha512` | The Maven `publish.yml` deploys on, and the SHA-512 Apache publishes for its `-bin.tar.gz`, so a runner image update cannot change the Maven a release is built with. It moved from 3.9.16 to 3.10.0 once `central-publishing-maven-plugin` 0.12.0 stopped zipping Maven 3.10's local-repository files into the bundle (#945). The `Central Bundle Shape` job in `build.yml` builds every published module's bundle with it on each pull request, so a bump that breaks bundling is a red pull request rather than a rejected release. Not named `maven.version`: Maven defines that itself, and a property would shadow it for every plugin. | `MAVEN_VERSION` and `MAVEN_SHA512` in the job `env` of `.github/workflows/publish.yml`; `build.yml` reads them from there. |
| `node.version` | The Node that installs the pinned CLIs below. `EvalsNodePinTest` checks it against the claude CLI's `engines.node`. | `node-version` in `build.yml` (codex-rules job) and `instruction-evals.yml`. |

## Pinned outside the parent, deliberately

These stay where their own update tooling edits them. Moving them into the pom would stop that
tooling from working and buy nothing, because none of them can be read from a pom either.

- **GitHub Actions** are pinned to commit SHAs, not tags, with the human-readable version in a
  trailing comment. Dependabot proposes the bumps. Pinning is what the OSSF Scorecard
  Pinned-Dependencies check wants, and it is also the only form that cannot be moved under you.
- **npm CLIs**, each in its own `package.json` with a committed lockfile of integrity hashes and
  installed with `npm ci`; Dependabot bumps both. `evals/package.json` pins
  `@anthropic-ai/claude-code`, the CLI the instruction evals run (`evals/README.md`).
  `tools/codex-cli/package.json` pins `@openai/codex`, which the `codex-rules` job in `build.yml`
  loads the generated `.codex/rules/vibetags.rules` with (#970).
- **pre-commit hooks**, `rev:` in `.pre-commit-config.yaml`, bumped by
  `python -m pre_commit autoupdate`: `gitleaks/gitleaks` (secret scanning) and
  `pre-commit/pre-commit-hooks` (end-of-file and trailing-whitespace fixers). The `checkstyle` and
  `vibetags-self-check` hooks are local and have no rev: they run this repository's own scripts
  with the Maven plugins pinned above.
- **The JDKs CI builds on** (`java-version` in the workflows) are a test matrix, not a
  dependency: the build targets `maven.compiler.release` 21 whichever JDK runs it.

## Bumping

`tools/bump-dependencies.sh` reports every property in the parent whose source has a newer stable
release, toolchains included, and `BumpDependenciesTableTest` fails if the parent gains a
`*.version` property the script has no source for. To apply one, edit the property in
`vibetags-parent/pom.xml`, then run `python tools/sync-version-mirrors.py` to rewrite its copies.
The procedure, with the gates that verify a bump, is the `bump-dependencies` skill.

The parent holds every version. Inside the Maven build nothing else holds any:

- The managed poms inherit; `BuildVersionParityTest` fails if one grows a version literal.
- The Gradle files cannot inherit from a Maven parent, so `vibetags/build.gradle` and
  `vibetags-annotations/build.gradle` **read** the parent's properties rather than repeating them:
  `pomVersion('junit.version')` and the rest parse `vibetags-parent/pom.xml` at configuration time.
- Everything else is a listed copy, as [Toolchains and copies](#toolchains-and-copies) describes.

`BuildVersionParityTest` also fails when a Gradle module applying PMD does not derive its
`toolVersion` from `pmd.version`. That check used to compare the literal against the parent
instead, which reads the same and is weaker. It exists because the drift had already happened -
`vibetags-annotations` sat on PMD 7.24.0 while everything else was on 7.26.0, so two modules were
analysed by different rule sets - but comparing the literal made every automated bump fail.
Dependabot edits `vibetags-parent/pom.xml` and nothing else, because that is where the property
lives, so each PMD bump arrived as a red PR needing a hand-fix before it could merge. Deriving the
value removes the copy the check was policing; asserting the *mechanism* is what stops the check
passing vacuously once there is no literal left to compare.

The copies list exists for the same reason. Before it, the copies were a table in the
`bump-dependencies` skill that nothing checked, and they drifted: the six consumer poms sat on
`maven-compiler-plugin` 3.15.0 while the parent had moved to 3.16.0, and `examples/enforcing` was
not in the table at all.

To bump the VibeTags release version itself, use `tools/set-version.sh <version>` and then run
`BuildVersionParityTest`.

### Versions deliberately not taken

Checked 2026-08-04 against `maven-metadata.xml` on repo1.maven.org, not against a changelog:

| Artifact | Newest published | Why we are not on it |
|---|---|---|
| `org.slf4j:slf4j-api` | 2.1.0-alpha1 | Alpha. The 2.0.x line is the stable one. |
| `maven-compiler-plugin` | 4.0.0-beta-4 | Beta. |
| `maven-surefire-plugin` | 3.6.0-M1 | Milestone. |
| `maven-jar-plugin`, `maven-source-plugin` | 4.0.0-beta-1 | Beta. |

Re-run the check with, from `vibetags/`:

```bash
mvn versions:display-dependency-updates -DprocessDependencyManagement=true
mvn versions:display-plugin-updates
```

`display-plugin-updates` only reports plugins the scanned project actually uses, so a plugin
declared in the parent's `pluginManagement` but used from `load-tests/` or a profile will not appear.
Those need the metadata URL directly.
