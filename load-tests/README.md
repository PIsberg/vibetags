# VibeTags Load Tests

Standalone benchmark harness for the `AIGuardrailProcessor`. Five test categories live here:

| Category | Class | What it measures |
|---|---|---|
| Annotation-volume sweep | `AnnotationVolumeStressTest` | Wall-clock cost as N annotated classes grows from 10 → 10 000 |
| Memory-volume sweep | `MemoryVolumeStressTest` | Per-thread allocated bytes and peak heap, same N sweep |
| Hot-path microbenchmarks | `ProcessorHotPathBenchmark` (JMH) | Per-call cost of `writeFileIfChanged`, `buildServiceFileMap`, `resolveActiveServices` |
| Cache-hit microbenchmarks | `WriteCacheHitBenchmark` (JMH, since 0.7.1) | Per-call cost & allocation of `writeFileIfChanged` with the `WriteCache` wired in vs. null. Small (1 KB), medium (12 KB), large (1 MB) bodies × marker (`.md`) and non-marker (`.json`) file types |
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

# JMH microbenchmarks — both classes, JSON output + GC profiler (~3 min)
mvn package -DskipTests
java -jar target/benchmarks.jar -wi 3 -i 5 -f 1 -tu us -bm avgt -prof gc \
     -rf json -rff results/<X.Y.Z>/jmh.json

# Just the cache-hit benchmark (proves the WriteCache value at 1 MB body sizes)
java -jar target/benchmarks.jar WriteCacheHitBenchmark -wi 3 -i 5 -f 1 \
     -tu us -bm avgt -prof gc \
     -rf json -rff results/<X.Y.Z>/jmh-cache-hit.json

# A single benchmark method with custom forks/iterations
java -jar target/benchmarks.jar writeFileIfChanged -f 1 -wi 3 -i 5 -tu us
```

## What to measure for a library like this

VibeTags is a **compile-time** annotation processor with `RetentionPolicy.SOURCE` — there is no runtime footprint to monitor. The numbers that matter are the ones a downstream developer feels when they hit *Build*:

| Dimension | Why it matters | Where it shows up |
|---|---|---|
| **Wall-clock overhead per N classes** | Determines whether the processor scales linearly with project size. A super-linear curve means a real user with 10 000 annotated classes pays a disproportionate compile-time tax. | `processorTime - baselineTime` in `AnnotationVolumeStressTest` |
| **Per-call latency on the hot path** | `writeFileIfChanged` runs once per generated file per round. `resolveActiveServices` runs once per compile. Sub-millisecond regressions here multiply across modules. | JMH `AverageTime` mode in `ProcessorHotPathBenchmark` |
| **Allocation rate / peak heap during processing** | Annotation processors run inside `javac` — they share the compiler's heap. A processor that allocates `O(N²)` strings can OOM a multi-module Maven build long before it errors. | `OverheadAlloc(KB)` and `PeakHeap(MB)` columns in `MemoryVolumeStressTest`; JMH `-prof gc` for finer-grained per-call attribution |
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

`AnnotationVolumeStressTest` and `MemoryVolumeStressTest` write `target/{stress,memory}-results-<timestamp>.txt`. JMH writes whatever `-rf` tells it to. Adopt one folder per release:

```bash
TAG=0.7.2
mkdir -p load-tests/results/$TAG
cd load-tests
mvn test -Dprocessor.version=$TAG \
         -Dtest=AnnotationVolumeStressTest,MemoryVolumeStressTest,ConcurrentBuildTest \
         -Dstress.max.classes=1000

mvn package -DskipTests -Dprocessor.version=$TAG
java -jar target/benchmarks.jar -wi 3 -i 5 -f 1 -tu us -bm avgt -prof gc \
     -rf json -rff results/$TAG/jmh.json
java -jar target/benchmarks.jar WriteCacheHitBenchmark -wi 3 -i 5 -f 1 \
     -tu us -bm avgt -prof gc \
     -rf json -rff results/$TAG/jmh-cache-hit.json
```

JMH's JSON output is the canonical comparison format — it includes mean, stdev, confidence intervals, and the raw samples per fork. Keep it. The text reports are for humans. Currently committed baselines: `0.5.4`, `0.5.5`, `0.5.6`, `0.7.0`, `0.7.1`.

### 2. Establish a baseline per release tag

Run the full sweep on a known-clean machine for every released version and commit the result files under `load-tests/results/<version>/`. Treat them like golden files.

The numbers are machine-dependent, so a baseline only compares like-with-like:
- Same JDK major + vendor (record `java -version` next to each result)
- Same OS / CPU class (record `uname -a` and CPU model)
- Quiescent box (no Docker, no Slack, no Chrome)

A single line at the top of each result file with `JDK / OS / CPU / commit-sha` makes diffs trivial later.

### 3. Diffing

For the stress sweep, eyeballing two `stress.txt` files side-by-side works for N ≤ 6 rows. The plotting script (see §4) reads every release folder and overlays them — that's the de-facto diff tool now.

For JMH, the official `jmh-compare` (or [JMH Visualizer](https://jmh.morethan.io/)) diffs two `jmh.json` files and flags benchmarks where the new score is outside the old confidence interval — that's the canonical "is this a real regression?" check.

### 4. Graphs that pay for themselves

The repo currently produces five release-trend plots and two cache-hit plots, all under `results/_plots/`:

1. **Overhead vs. N** (`overhead-vs-n.png`, line plot, log-x) — one line per release. Shape tells scaling; height tells constant factor. Source: `Overhead(ms)` column from `stress.txt`.
2. **Hot-path latency by release** (`hotpath-by-release.png`, grouped bar chart, log-y) — six bars × one group per release. Catches per-hot-path regressions. Source: `Score` from `jmh.json`.
3. **`writeFileIfChanged` variants** (`writeFileIfChanged-detail.png`, linear scale) — the three `writeFileIfChanged_*` benchmarks isolated from the rest, error bars visible.
4. **Allocation overhead vs. N** (`memory-overhead-vs-n.png`) — `OverheadAlloc(KB)` from `MemoryVolumeStressTest`. Linear scaling expected; flat across releases means no allocation regression.
5. **Peak heap vs. N** (`memory-peak-heap-vs-n.png`) — noisier than allocation overhead (GC-timing dependent). Useful for OOM-risk sanity, not regression decisions.
6. **Cache-hit wall-clock** (`cache-hit-time.png`, log-y) — `WriteCacheHitBenchmark` proof, cache hit vs. no-cache, with speedup ratios annotated. Source: `jmh-cache-hit.json`.
7. **Cache-hit allocation** (`cache-hit-alloc.png`, log-y) — same benchmark, `gc.alloc.rate.norm` axis. Shows the 10,000× allocation reduction at 1 MB body size.

`tools/plot-results.py` regenerates 1–5 from any committed release folders. `tools/plot-cache-hit.py` regenerates 6–7 from a single `jmh-cache-hit.json`. No Grafana, no time-series DB — the repo is the database.

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
│   ├── main/java/.../WriteCacheHitBenchmark.java      (JMH, since 0.7.1)
│   └── test/java/.../AnnotationVolumeStressTest.java
│       test/java/.../MemoryVolumeStressTest.java
│       test/java/.../ConcurrentBuildTest.java
└── results/                                   ← committed baselines per release tag
    ├── README.md
    ├── 0.5.4/  0.5.5/  0.5.6/  0.7.0/  0.7.1/
    │   ├── env.txt                       jdk + os + cpu + sha + release notes
    │   ├── stress.txt                    AnnotationVolumeStressTest table
    │   ├── memory.txt                    MemoryVolumeStressTest table
    │   ├── concurrent.txt / .xml         ConcurrentBuildTest surefire
    │   ├── jmh.json                      ProcessorHotPathBenchmark JMH JSON
    │   └── jmh-cache-hit.json            WriteCacheHitBenchmark (0.7.1+ only)
    │   └── jmh-cache-hit-summary.md      Human-readable cache-benchmark writeup (0.7.1+)
    └── _plots/                           regenerated by tools/plot-{results,cache-hit}.py
        ├── overhead-vs-n.png
        ├── hotpath-by-release.png
        ├── writeFileIfChanged-detail.png
        ├── memory-overhead-vs-n.png
        ├── memory-peak-heap-vs-n.png
        ├── cache-hit-time.png            (since 0.7.1)
        └── cache-hit-alloc.png           (since 0.7.1)
```
