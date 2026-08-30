# tools/

Every hand-run or CI-run script in the repository, except the CI-internal publish helper
(`.github/scripts/deploy-to-central.sh`, which only `publish.yml` calls). `tools/demo/` is not a
tool: it is the Maven fixture the animated README demo builds against (excluded from
`ExampleCoverageTest`, version pinned by `BuildVersionParityTest`).

The "Enforced by" column names the check that notices when the script and the repository drift
apart. "manual" means nothing runs it automatically; it exists for a human (or the named skill).

## Release and build gates

| Tool | What it does | Run by | Enforced by |
|---|---|---|---|
| `set-version.sh` | Rewrites every file that states the release version, in one pass | `release` skill | `ReleaseScriptCoverageTest`, `BuildVersionParityTest` |
| `consumer-sweep.sh` | Builds every downstream consumer against a given VibeTags version | `release` and `consumer-regression-suite` skills | `ReleaseConsumerSweepGateTest` |
| `bump-dependencies.sh` | Reports third-party pins that have a newer stable release (read-only) | `bump-dependencies` skill | manual |
| `ecj-degradation-check.sh` | Compiles `examples/basic` under javac and ECJ, compares the locks output | `ecj` job in `build.yml` | that CI job |

## Architecture diagrams

| Tool | What it does | Run by | Enforced by |
|---|---|---|---|
| `generate-architecture-diagrams.sh` | Regenerates the code-karta SVGs under `docs/diagrams/codekarta/` | `diagrams` job in `build.yml` | that job fails on structural drift |
| `diagram-structure.sh` | Structural fingerprint of the SVGs (sorted `<title>` multiset); the comparison the drift gate uses | `diagrams` job in `build.yml` | that CI job |

## Load-test plots (see `load-tests/README.md`)

| Tool | What it does | Run by |
|---|---|---|
| `plot-results.py` | Release-trend plots from every committed `load-tests/results/<version>/` folder | `load-tests` skill, per release |
| `plot-cache-hit.py` | Wall-clock and allocation plots from `WriteCacheHitBenchmark` JMH output | `load-tests` skill, per release |
| `plot-processor-tax.py` | Splits "processor overhead" into javac's share and VibeTags' | `load-tests` skill, per release |
| `plot-release-comparison.py` | Same-session allocation comparison against prior releases, for the changelog | `load-tests` skill, per release |
| `plot-alloc-before-after.py` | One-off figure for the 1.0.0-RC1 allocation optimizations | manual, historical |

## README demo

| Tool | What it does | Run by |
|---|---|---|
| `demo-commands.sh` | The command sequence asciinema replays inside `tools/demo/` to produce `docs/demo.gif` | `demo.yml` |
| `make-demo-cast.py` | Generates `docs/vibetags-demo.cast` from real VibeTags output | manual |

The four gate scripts previously lived in a separate `scripts/` directory; older
`docs/CHANGELOG.md` entries refer to them there. No release version appears in this file on
purpose: `ReleaseScriptCoverageTest` fails any tracked file that states one unless
`set-version.sh` rewrites it.
