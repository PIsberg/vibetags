# Examples coverage index

What each example exercises and how CI verifies it, audited against
`.github/workflows/build.yml` on 2026-08-31. [README.md](README.md) is the narrative guide for
picking an example to read; this file is the coverage ledger. When the two disagree, the workflow
file wins: every gate named below is a step that exists there.

## Per-example matrix

Annotation counts are distinct `@AI*` names in the example's sources, counted by grep on the audit
date. "Drift gate" is what CI does to the example's committed generated files after rebuilding it.

| Example | Build | Modules | Distinct `@AI*` | Drift gate in CI | Content assertions in CI |
|---|---|---|---|---|---|
| [`basic/`](basic/) | Maven and Gradle | 1 | 44 | byte for byte, whole directory | log contract on `vibetags.log`; cache-off rebuild byte-identical |
| [`enforcing/`](enforcing/) | Maven | 1 | 3 | byte for byte, whole directory | build red on drifted `@AILocked` signature; violation names the baseline entry |
| [`multimodule/`](multimodule/) | Maven | 5 | 44 | byte for byte, plus check mode | recovery, roles, mirror, locks, transitive origin |
| [`multimodule-indexed/`](multimodule-indexed/) | Maven | 2 | 44 | check mode | root collapses to an index; safety buckets stay inline |
| [`all-tiers/`](all-tiers/) | Maven | 2 | 44 | check mode | tier split: buckets inline, Tier-2 isolation, Tier-3 front matter |
| [`gradle-multimodule/`](gradle-multimodule/) | Gradle | 5 | 44 | byte for byte, plus opt-in check mode (`-PvibetagsCheck`) | module regions, scoped rules, locks, roles, transitive origin |
| [`gradle-shared-buildfile/`](gradle-shared-buildfile/) | Gradle | 2 | 2 | `CLAUDE.md` only | both module identities survive |
| [`gradle-flat/`](gradle-flat/) | Gradle | 2 | 2 | `CLAUDE.md` only | module ids are names, not path hashes |
| [`gradle-composite/`](gradle-composite/) | Gradle, `includeBuild` | 2 builds | 2 | `CLAUDE.md` only | both builds land in one root file |
| [`kotlin/`](kotlin/) | Gradle + kapt | 1 | 4 | byte for byte, whole directory | Kotlin elements appear, stub signatures included |
| [`groovy/`](groovy/) | Gradle | 1 | 3 | byte for byte, whole directory | annotated class and method appear; the `@AIPrivacy` field does NOT (groovyc stubs carry no fields) |
| [`scala/`](scala/) | Gradle | 1 | 2 | byte for byte, whole directory | annotated Java class appears, Scala class does not |

## Feature map

Which example to read for a given processor feature.

| Feature | Where |
|---|---|
| All 44 annotations in use | `basic`, `multimodule`, `multimodule-indexed`, `all-tiers`, `gradle-multimodule` |
| Every supported platform's output committed | `basic` |
| Enforcing mode (`-Avibetags.enforce`, `.vibetags-baseline`, `-Avibetags.baseline.update`) | `enforcing` |
| Sidecar merge into a root aggregate | every reactor example |
| Granular rules grouped by role (`.vibetags-roles`) | `multimodule`, `multimodule-indexed`, `all-tiers`, `gradle-multimodule` |
| Root aggregate as index (`.vibetags-root-index`) | `multimodule-indexed`, `all-tiers` |
| Test-source mirroring (`.vibetags-mirror`) | `multimodule/tests/`, `gradle-multimodule/tests/` |
| Locks report (`.vibetags-locks`) | `multimodule`, `gradle-multimodule` |
| Transitive manifests, publish and consume (`.vibetags-manifest`, `.vibetags-transitive`) | `multimodule`, `gradle-multimodule` |
| Check mode | `multimodule` (Maven property), `gradle-multimodule` (`-PvibetagsCheck`), `all-tiers`, `multimodule-indexed` |
| Write cache disabled (`-Avibetags.cache=false`) | `basic` (CI rebuilds with it and asserts byte-identical output) |
| Codex sidecar | `gradle-multimodule` (`.codex/rules/`) |
| `AGENTS.md` fallback | `basic` |
| kapt over Kotlin sources | `kotlin` |
| Groovy joint-compilation stubs, field drop gated | `groovy` |
| scalac's missing JSR 269 support, gated not claimed | `scala` |
| Processor options exercised by a build file | `root`, `module`, `check`, `enforce`, `baseline.update`, `cache`, `log.level`, `log.path`, `project` |
| Manifest fallback options (`manifest.dir`, `manifest.packages`, `manifest.max`) | documented in the `.vibetags-transitive` comments; no build passes them |

## Not covered by any example

Verified absent by grep over `examples/` on 2026-08-31. Each entry says what a reader cannot
currently see demonstrated.

1. **The companion CLI, run.** `vibetags init` and `vibetags doctor` run in no example build or CI
   step; [README.md](README.md) points at [USAGE.md](../USAGE.md) for both. `doctor` is the tool
   that reports the Groovy field drop the `groovy` example gates.
2. **Manifest tuning options.** `manifest.dir`, `manifest.packages` and `manifest.max` exist for
   builds where classpath discovery cannot work (kapt, ECJ, JPMS). They are documented where a
   reader will meet them, in the `.vibetags-transitive` comments, and exercised by library tests
   only.
