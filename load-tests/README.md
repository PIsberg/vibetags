# VibeTags Load Tests

Standalone benchmark harness for the `AIGuardrailProcessor`. Three test categories live here:

| Category | Class | What it measures |
|---|---|---|
| Annotation-volume sweep | `AnnotationVolumeStressTest` | Wall-clock cost as N annotated classes grows from 10 → 10 000 |
| Hot-path microbenchmarks | `ProcessorHotPathBenchmark` (JMH) | Per-call cost of `writeFileIfChanged`, `buildServiceFileMap`, `resolveActiveServices` |
| Concurrent-build safety | `ConcurrentBuildTest` | Behaviour under N threads writing to a shared project root |

## Prerequisite

Install the processor into your local Maven repo first:

```bash
cd ../vibetags && mvn install -DskipTests
```

## Running

```bash
cd load-tests

# Stress sweep + concurrent test
mvn test

# Cap the sweep so CI stays fast (skips N > 500)
mvn test -Dstress.max.classes=500

# JMH microbenchmarks (~2 min)
mvn package exec:java -Dexec.mainClass=org.openjdk.jmh.Main

# A single JMH benchmark with custom forks/iterations
mvn package exec:java -Dexec.mainClass=org.openjdk.jmh.Main \
    -Dexec.args="writeFileIfChanged -f 1 -wi 3 -i 5 -tu ms"
```

## What to measure for a library like this

VibeTags is a **compile-time** annotation processor with `RetentionPolicy.SOURCE` — there is no runtime footprint to monitor. The numbers that matter are the ones a downstream developer feels when they hit *Build*:

| Dimension | Why it matters | Where it shows up |
|---|---|---|
| **Wall-clock overhead per N classes** | Determines whether the processor scales linearly with project size. A super-linear curve means a real user with 10 000 annotated classes pays a disproportionate compile-time tax. | `processorTime - baselineTime` in `AnnotationVolumeStressTest` |
| **Per-call latency on the hot path** | `writeFileIfChanged` runs once per generated file per round. `resolveActiveServices` runs once per compile. Sub-millisecond regressions here multiply across modules. | JMH `AverageTime` mode in `ProcessorHotPathBenchmark` |
| **Allocation rate / peak heap during processing** | Annotation processors run inside `javac` — they share the compiler's heap. A processor that allocates `O(N²)` strings can OOM a multi-module Maven build long before it errors. | JMH `-prof gc` (allocations); `-Xlog:gc*` or `jcmd <pid> GC.heap_info` from a stress run |
| **Generated output size vs. annotation count** | Linear is expected; anything else means duplicate emission or the marker block is being appended instead of replaced. | `OutputSize(B)` column in the stress report |
| **No-change fast-path latency** | Incremental builds dominate developer wait time. If `writeFileIfChanged_noChange` is not the cheapest benchmark, the read-compare path has regressed. | `writeFileIfChanged_noChange` in JMH |
| **CPU time vs. wall-clock under contention** | `ConcurrentBuildTest` exposes the absence of file-level locking. CPU staying flat while wall-clock balloons indicates lock contention or I/O serialization. | `ConcurrentBuildTest` + OS-level CPU sampling |

What we deliberately do **not** track:
- Steady-state throughput (this is not a server)
- p99 latency (a single compile is one synchronous call, not a distribution)
- Any runtime metric of the consumer app — `RetentionPolicy.SOURCE` guarantees zero runtime cost

## Comparing results between versions / builds

The harness already writes machine-readable output and CI already uploads it (see [§5](#5-what-ci-already-does--and-what-it-doesnt)); the missing piece is a stable workflow for storing baselines and diffing against them.

### 1. Capture results with version metadata

`AnnotationVolumeStressTest` writes `target/stress-results-<timestamp>.txt`. JMH writes whatever `-rf` tells it to. Adopt one folder per release:

```bash
mkdir -p load-tests/results/0.6.0
mvn test -Dtest=AnnotationVolumeStressTest -Dstress.results.dir=load-tests/results/0.6.0
mvn package exec:java -Dexec.mainClass=org.openjdk.jmh.Main \
    -Dexec.args="-rf json -rff load-tests/results/0.6.0/jmh.json"
```

JMH's JSON output is the canonical comparison format — it includes mean, stdev, confidence intervals, and the raw samples per fork. Keep it. The text reports are for humans.

### 2. Establish a baseline per release tag

Run the full sweep on a known-clean machine for every released version (`0.5.x`, `0.6.x`, …) and commit the result files under `load-tests/results/<version>/`. Treat them like golden files.

The numbers are machine-dependent, so a baseline only compares like-with-like:
- Same JDK major + vendor (record `java -version` next to each result)
- Same OS / CPU class (record `uname -a` and CPU model)
- Quiescent box (no Docker, no Slack, no Chrome)

A single line at the top of each result file with `JDK / OS / CPU / commit-sha` makes diffs trivial later.

### 3. Diffing

For the stress sweep, two CSVs side-by-side give a regression table:

```bash
# Convert text to CSV with awk, then diff with csvkit or pandas
csvjoin -c Classes results/0.6.0/stress.csv results/0.7.0/stress.csv \
  | csvcut -c Classes,Overhead_0.6.0,Overhead_0.7.0
```

For JMH, the official `jmh-compare` (or `jmh-visualizer`) diffs two `jmh.json` files and flags benchmarks where the new score is outside the old confidence interval — that's the canonical "is this a real regression?" check.

### 4. Graphs that pay for themselves

Three plots cover the questions that actually come up:

1. **Overhead vs. N (line plot, log-x)** — one line per release. The shape tells you whether scaling is still linear; the height tells you the constant factor. *Source: `Overhead(ms)` column from the stress sweep.*
2. **Hot-path latency per release (grouped bar chart)** — six bars (one per JMH benchmark) × one group per release. Catches regressions in any single hot path even when overall compile time looks fine. *Source: `Score` from `jmh.json`.*
3. **Heap / allocation profile during the N=10 000 run (line plot, time-series)** — peak-RSS or allocated-bytes against time. Useful only when investigating a memory regression; not part of the per-release dashboard. *Source: `-prof gc` (JMH) or `jcmd VM.native_memory` snapshots during the stress test.*

A 30-line `tools/plot-results.py` reading the JSON/CSV from `results/*/` and emitting PNGs into `results/_plots/` is enough — no Grafana, no time-series DB. The repo is the database.

### 5. What CI already does — and what it doesn't

The `load-tests` job in `.github/workflows/build.yml` (documented in [`docs/WORKFLOW.md`](../docs/WORKFLOW.md#job-load-tests)) already runs on every push/PR:

- JDK 21, `needs: build-maven`
- `cd load-tests && mvn test -B -Dtest="AnnotationVolumeStressTest,ConcurrentBuildTest" -Dstress.max.classes=500` (the cap keeps the GitHub Actions leg fast)
- Uploads `load-tests/target/stress-results-*.txt` as artifact `stress-results-${{ github.run_id }}` with `if: always()`, so the file is captured even on failure

What CI does **not** do today:

- Run the JMH microbenchmarks (warm-up + measurement is too slow for per-PR CI; run those locally before tagging a release)
- Compare results against a stored baseline — every run produces a fresh artifact, but nothing fails the build if `Overhead(ms)` doubles between PRs

### 6. Adding a regression gate (next step)

Once `results/<release>/` baselines are committed, the missing piece is a comparison step in the existing `load-tests` job:

1. After `Run annotation-volume stress test`, parse the new `stress-results-*.txt`
2. Diff against `load-tests/results/<latest-release>/stress.csv` with a tolerance (e.g. ±15% on `Overhead(ms)`, JMH confidence-interval overlap for hot paths)
3. Fail the job on regression; the existing artifact upload already preserves the evidence

Tolerances need to be loose enough that GitHub Actions runner variance does not flip the gate red — that's the practical reason to keep a release-tagged baseline rather than comparing PR-to-PR.

## File layout

```
load-tests/
├── pom.xml
├── README.md                                  ← you are here
├── src/
│   ├── main/java/.../SyntheticClassGenerator.java
│   ├── main/java/.../ProcessorHotPathBenchmark.java   (JMH)
│   └── test/java/.../AnnotationVolumeStressTest.java
│       test/java/.../ConcurrentBuildTest.java
└── results/                                   ← suggested; gitignored except for tagged baselines
    ├── 0.6.0/
    │   ├── env.txt          (jdk + os + cpu + sha)
    │   ├── stress.csv
    │   └── jmh.json
    └── _plots/
        ├── overhead-vs-n.png
        └── hotpath-by-release.png
```
