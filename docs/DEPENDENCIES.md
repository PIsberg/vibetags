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
source of truth for the platform list). Whoever launches it (jbang, `java -cp`) resolves the
processor's transitive slf4j/logback, which is the full closure.

`vibetags-annotations` has **no** third-party dependencies at all, by design. It is on the
consumer's compile classpath, and anything added there is something a consumer's build has to
resolve, shade or exclude.

## What the tests use

All test-scope, all in `vibetags/`.

| Artifact | Property | Why it is here |
|---|---|---|
| `org.junit.jupiter:junit-jupiter` | `junit.version` | The test framework. |
| `org.junit.platform:junit-platform-launcher` | `junit.platform.version` | Runtime-only. Surefire needs it on the JUnit 6 platform. |
| `org.mockito:mockito-core`, `mockito-junit-jupiter` | `mockito.version` | Faking the javac side (`ProcessingEnvironment`, `Messager`, `Filer`) so processor logic can be tested without a running compiler. |
| `com.tngtech.archunit:archunit-junit5` | `archunit.version` | Enforces the layering invariant: `processor/internal/content/` must not import `javax.lang.model`, `javax.annotation.processing` or `com.sun.source`. See `ArchitectureRulesTest`. This is a rule the compiler cannot express, so a library holds it instead. |
| `se.deversity.async-test-lib` | `async-test-lib.version` | Concurrency tests for the parallel write phase and `WriteCache`. Sibling project, published to Maven Central like any other dependency. |
| `org.yaml:snakeyaml` | `snakeyaml.version` | Parses the six YAML documents the renderers emit. The processor itself must never gain a YAML dependency: it runs on the consumer's annotation processor path, where every extra jar is one more collision. The tests need a real parser because "the file looks right" is not the property that matters; "a strict parser sees every module's guardrails" is. `YamlMergeShapeContractTest` depends on this distinction. |

`load-tests/` additionally uses `org.openjdk.jmh:jmh-core` and `jmh-generator-annprocess`
(`jmh.version`) for the benchmark harness. That module pins `<processor.version>` directly rather
than inheriting it, on purpose: comparing one VibeTags release against another is the whole point of
the module, and a BOM that forces both sides to the same version defeats it.

## What the build uses

Nothing in this section ships. It runs.

**Compile and package.** `maven-compiler-plugin`, `maven-surefire-plugin`, `maven-jar-plugin`,
`maven-source-plugin`, `maven-javadoc-plugin`. Two more serve `load-tests/` alone:
`maven-shade-plugin` builds the benchmark fat jar, and `exec-maven-plugin` gives it an `exec:java`
entry point into `org.openjdk.jmh.Main`.

**Static analysis.** Four tools, each catching something the others do not:

- `maven-pmd-plugin` with `pmd-core` and `pmd-java` (`pmd.version`) for source-level rules and
  copy-paste detection. A red PMD gate is worth checking against the JDK before believing it: PMD
  run on a newer JDK than the build targets reports confident nonsense.
- `maven-checkstyle-plugin` for style, also wired into `pre-commit` so it fails before the commit
  rather than in CI.
- `spotbugs-maven-plugin` with `findsecbugs-plugin` (`findsecbugs-plugin.version`) for bytecode
  analysis, the security rules included.
- Error Prone (`error-prone.version`) with NullAway (`nullaway.version`), on
  `annotationProcessorPaths` of the compiler plugin, main sources only. NullAway is what makes the
  JSpecify annotations load-bearing instead of decorative.

**Coverage and mutation.** `jacoco-maven-plugin` measures line coverage; `pitest-maven` with
`pitest-junit5-plugin` measures whether the tests would notice if the code were wrong. Mutation
testing runs on demand only, via `.github/workflows/mutation.yml`, because it costs minutes rather
than seconds.

**Supply chain and publishing.** `cyclonedx-maven-plugin` emits the SBOM.
`central-publishing-maven-plugin` and `maven-gpg-plugin` sign and publish to Maven Central.
`flatten-maven-plugin` resolves `${revision}` and strips `vibetags-parent` out of the deployed POMs,
so what consumers download is self-contained.

## Outside Maven

- **GitHub Actions** are pinned to commit SHAs, not tags, with the human-readable version in a
  trailing comment. Dependabot proposes the bumps. Pinning is what the OSSF Scorecard
  Pinned-Dependencies check wants, and it is also the only form that cannot be moved under you.
- **Gradle wrapper**, `gradle-9.6.1-bin.zip` in three subprojects. The checked-in
  `gradle-wrapper.jar` files are validated against Gradle's published checksums by
  `.github/workflows/gradle-wrapper-validation.yml`, which must run on every push to main for
  Scorecard's Binary-Artifacts check to accept them.
- **pre-commit hooks**: `gherynos/pre-commit-java` (Checkstyle), `gitleaks/gitleaks` (secret
  scanning), and `pre-commit/pre-commit-hooks` (end-of-file and trailing-whitespace fixers).

## Bumping

`vibetags-parent/pom.xml` holds every version and nothing else holds any, with two exceptions the
build itself enforces:

- The Gradle files cannot inherit from a Maven parent, so `vibetags/build.gradle`,
  `vibetags-annotations/build.gradle` and `example/build.gradle` repeat the coordinates as literals.
- The example poms are standalone on purpose, so a reader can lift one into their own project.

`BuildVersionParityTest` fails the build when either copy drifts from the parent, when a managed pom
grows a version literal, or when a Gradle module's PMD `toolVersion` disagrees. That last check
exists because it had already happened: `vibetags-annotations` sat on PMD 7.24.0 while everything
else was on 7.26.0, so the two modules were analysed by different rule sets.

To bump the VibeTags release version itself, use `scripts/set-version.sh <version>` and then run
that test.

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
