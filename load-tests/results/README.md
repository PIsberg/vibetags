# Load-test baselines

One folder per processor release. Each folder is a **frozen baseline** captured by running the harness in `load-tests/` against the matching `vibetags-processor` artifact.

## Layout

```
results/
├── README.md                 ← you are here
├── 0.5.4/
│   ├── env.txt               machine + JDK + commit metadata
│   ├── stress.txt            AnnotationVolumeStressTest table (N=10..1000)
│   ├── concurrent.txt        ConcurrentBuildTest surefire summary
│   ├── concurrent.xml        ConcurrentBuildTest surefire XML
│   └── jmh.json              ProcessorHotPathBenchmark JMH JSON
└── 0.5.5/
    └── (same files)
```

## How these were captured

```bash
# 1. Install the target processor version into the local Maven repo
git worktree add ../vibetags-vX.Y.Z vX.Y.Z
cd ../vibetags-vX.Y.Z/vibetags && mvn install -DskipTests

# 2. Run stress + concurrent capped at N=1000
cd <repo>/load-tests
mvn test -Dprocessor.version=X.Y.Z \
         -Dtest=AnnotationVolumeStressTest,ConcurrentBuildTest \
         -Dstress.max.classes=1000

# 3. Run JMH (writes JSON directly into results/X.Y.Z/)
mvn package -DskipTests -Dprocessor.version=X.Y.Z
java -jar target/benchmarks.jar -rf json -rff ../load-tests/results/X.Y.Z/jmh.json

# 4. Copy artifacts
cp target/stress-results-*.txt              results/X.Y.Z/stress.txt
cp target/surefire-reports/...ConcurrentBuildTest.txt  results/X.Y.Z/concurrent.txt
cp target/surefire-reports/TEST-...ConcurrentBuildTest.xml results/X.Y.Z/concurrent.xml
```

## Comparison: 0.5.4 vs 0.5.5

Same machine, same JDK (Temurin 26), same run window. Cap: `stress.max.classes=1000`.

### Stress sweep — `Overhead(ms)` (processor − baseline)

| N | 0.5.4 | 0.5.5 | Δ |
|---:|---:|---:|---:|
| 10 | 730 | 750 | +20 |
| 100 | -38 | -184 | -146 |
| 500 | 1062 | 1859 | **+797** |
| 1000 | 933 | 1209 | +276 |

`OutputSize(B)` is identical between versions at every N (17 156 / 122 555 / 599 895 / 1 196 918), so the work product hasn't changed — the cost has.

### JMH hot-path (`avgt`, µs/op, lower is better)

| Benchmark | 0.5.4 | 0.5.5 |
|---|---:|---:|
| `buildServiceFileMap` | 8.19 ± 0.13 | **4.80 ± 0.63** |
| `resolveActiveServices_allPresent` | 554.55 ± 57.17 | 537.99 ± 150.02 |
| `resolveActiveServices_nonePresent` | 497.27 ± 86.94 | 633.11 ± 350.46 |
| `writeFileIfChanged_noChange` | 355.57 ± 296.12 | 437.66 ± 252.73 |
| `writeFileIfChanged_smallWrite` | 1 916.92 ± 183.36 | 9 456.07 ± 5 186.26 |
| `writeFileIfChanged_largeWrite` | 3 058.88 ± 293.93 | 5 628.32 ± 4 314.78 |

## Caveats — read before drawing conclusions

1. **JMH error bars on the 0.5.5 run are very wide** (±5 186 µs/op on `writeFileIfChanged_smallWrite`). This indicates background system noise during measurement, not a real 5× regression. Re-run on a quiet machine before treating the `writeFileIfChanged_*` deltas as signal. The narrow-error metrics (`buildServiceFileMap`, `resolveActiveServices_allPresent`) are trustworthy.
2. **Stress times include process-launch jitter on Windows** — a single high-overhead leg (e.g. N=500 here) is not enough to call a regression. CI should accept ±15% variance, and large deltas should be reproduced before fixing.
3. **JUnit `@TempDir` cleanup fails on Windows** because `vibetags.log` is held open by the file logger during processing. The `concurrent.xml` reports `errors=1` for this reason; the test body itself completed and assertions passed. Same applies to the per-N stress test legs in `surefire-reports/` (not stored here). This is a pre-existing harness issue affecting Windows runs only.
4. **Both runs used JDK Temurin 26-ea**. Re-baseline on the JDK that CI uses (currently 21) before comparing CI artifacts against these numbers.

## Diffing two baselines

For the stress sweep, eyeball-diffing `stress.txt` works for N ≤ 6 rows. For JMH, the canonical diff is the official `jmh-compare` (or [JMH Visualizer](https://jmh.morethan.io/)) which understands confidence intervals — paste both `jmh.json` files into the visualizer and look for benchmarks where the bars don't overlap.
