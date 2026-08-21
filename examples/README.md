# Examples

Eleven runnable consumer projects. Each is standalone: it depends on a published VibeTags release
rather than on this repository, so any of them can be copied out and built on its own. Every one of
them is built by CI, and the generated files each has committed are compared byte for byte against
what the build produces, so an example that has drifted from the processor's behaviour fails the
pipeline rather than misleading a reader.

Pick by what you want to see.

## Start here

| Example | Build | What it shows |
|---|---|---|
| [`basic/`](basic/) | Maven and Gradle | The full tour. All 44 annotations on a realistic e-commerce codebase, every supported platform's output committed, and a README that maps each annotation to the file that uses it. |

## Reactors and merged output

| Example | Build | What it shows |
|---|---|---|
| [`multimodule/`](multimodule/) | Maven, 5 modules | Sidecar merge into one root file, plus per-module output. Carries a `.vibetags-roles` config, so granular rules group by topic instead of one file per class. |
| [`multimodule-indexed/`](multimodule-indexed/) | Maven, 2 modules | The lean root: `.vibetags-root-index` turns the root aggregate into pointers rather than a second verbatim copy of every module's rules. |
| [`all-tiers/`](all-tiers/) | Maven, 2 modules | All three output tiers at once, including a class annotated at two levels, so the tier a guardrail lands in is visible rather than asserted. |

## Gradle layouts

Gradle resolves a module's root differently from Maven, and four layouts need a decision the
processor cannot make on its own. Each example states the failure it prevents and the option that
prevents it. The same table appears in
[docs/MULTI-MODULE.md](../docs/MULTI-MODULE.md#build-layouts-what-resolves-and-what-needs-telling), which is where the supported
matrix is maintained.

| Example | Layout | The decision |
|---|---|---|
| [`gradle-multimodule/`](gradle-multimodule/) | Subprojects below the root, each with its own `build.gradle` | `-Avibetags.root` at the reactor root. Also the Gradle side's depth fixture: four modules, transitive manifests, mirroring, roles, nested output, check mode and all 51 services. |
| [`gradle-shared-buildfile/`](gradle-shared-buildfile/) | Subprojects configured from the root build file | `-Avibetags.module`, without which every module shares one identity and overwrites the others |
| [`gradle-flat/`](gradle-flat/) | Module beside the root, not below it | `-Avibetags.root` at the directory containing both, or module ids become path hashes that differ per checkout |
| [`gradle-composite/`](gradle-composite/) | Two separate builds joined by `includeBuild` | `-Avibetags.root` in both, or each build writes its own set of files |

## Other JVM languages

| Example | Build | What it shows |
|---|---|---|
| [`kotlin/`](kotlin/) | Gradle, Kotlin DSL | kapt runs the processor over Kotlin sources. CI asserts the generated files name the Kotlin elements, stub signatures included. |
| [`groovy/`](groovy/) | Gradle | Joint compilation with `javaAnnotationProcessing = true`, the Groovy analogue of kapt |
| [`scala/`](scala/) | Gradle | The limitation, gated. scalac has no JSR 269 support, so CI asserts the annotated Java class appears and the annotated Scala class does not. If that ever changes, the build goes red and the docs are wrong, not the code. |

## Building one

The Maven examples need the library installed locally first:

```bash
cd vibetags-annotations && mvn install
cd ../vibetags         && mvn clean install
cd ../vibetags-bom     && mvn install
```

Then, from the example's own directory:

```bash
mvn clean compile        # Maven examples
./gradlew clean build    # Gradle examples
```

The processor writes at the JVM working directory unless `vibetags.root` says otherwise, which is
why every example is built from its own directory and why the Gradle ones pass `-Avibetags.root`
explicitly.

## Changing one

The generated files are committed, and CI compares them against a fresh build. So an edit to an
annotation is not finished until the example is rebuilt and the regenerated files are committed
with it. Clear `.vibetags-cache` first: the fingerprint short-circuit skips the write when inputs
look unchanged, which makes "no diff" ambiguous.
