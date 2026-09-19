# Architecture: Repository Layout Notes

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## Repository Layout Notes

The developer-facing facts about each subproject that the directory tree alone does not say
(moved here from `CLAUDE.md` when that file went on its context diet; the build order and the
one-line map stay there):

- `vibetags-annotations/` — the 44 `@interface` classes, zero dependencies. On the consumer's
  compile classpath. Build first.
- `vibetags/` — the processor (`AIGuardrailProcessor` + `VibeTagsLogger`). On the consumer's
  annotation-processor path only.
- `vibetags-bom/` — pom-only BOM managing the published versions. Maven only; Gradle reads it
  via `mavenLocal()` / `platform(...)`.
- `vibetags-cli/` — companion CLI (`init` creates opt-in files, `doctor` reports project
  health). Depends on `vibetags` as a library for `ServiceRegistry.optInKeys()` and the marker
  constants — it must never carry its own platform list. Build after `vibetags`.
- `vibetags-ksp/` — the KSP front end (#496): a `SymbolProcessorProvider` that presents Kotlin
  declarations as kapt-shaped `javax.lang.model` elements and drives the unchanged
  `AIGuardrailProcessor`. Depends on `vibetags` as a library; the KSP API and the Kotlin standard
  library are `provided`. Build after `vibetags`. Its tests run real KSP2 in-process.
- `examples/basic/`, `examples/multimodule/`, `examples/multimodule-indexed/` — demo consumers (the last
  two are reactors, asserted in CI).
- `examples/kotlin-ksp/` — `examples/kotlin`'s sources through KSP; CI requires identical output.
- `examples/kotlin/`, `examples/groovy/`, `examples/scala/` — JVM-language consumers, all built on
  the JDK 21 Gradle CI leg. Kotlin (kapt) and Groovy (joint-compilation stubs +
  `javaAnnotationProcessing`) get full support with the same stub caveats (no body-scoped
  annotations, stub positions); Scala is Java-sources-only (scalac has no JSR 269) and its CI
  step asserts the annotated Scala class does NOT appear. Clojure is documented as impossible
  (no javac; SOURCE retention inexpressible) — support matrix in USAGE.md.
- `load-tests/` — standalone benchmark harness; pins `<processor.version>` directly
  (intentional — cross-version comparison is the wrong workload for a BOM).
- `action/locked-files/` — GitHub Action consuming `.vibetags-locks`.
- `vibetags-parent/` — pom-only. Every version in the repository is declared here and nowhere
  else: third-party dependencies, plugins, and `<revision>`, the VibeTags release version. Not
  published: `flatten-maven-plugin` resolves it away before deploy, so the POMs on Maven
  Central are self-contained and consumers gain nothing new to resolve. Subprojects reference
  it by `<relativePath>`, so each builds straight from a checkout with no install step.
