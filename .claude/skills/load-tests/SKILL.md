---
name: load-tests
description: Run the VibeTags benchmark harness in load-tests/ and report the numbers honestly — annotation-volume and memory sweeps, JMH hot-path and cache-hit microbenchmarks, the processor-tax control — then record a release baseline under load-tests/results/. Use when the user says "load tests", "benchmarks", "capture a baseline", "did this get slower", "JMH", "performance regression", or before cutting a VibeTags release.
---

# Load tests

The harness lives in `load-tests/`. It is a standalone Maven project that compiles synthetic
annotated sources with and without the processor and subtracts. `load-tests/README.md` documents
the harness; `load-tests/results/README.md` documents the baselines. This skill is the judgement
around both: which run answers which question, and which of the resulting numbers may be quoted.

The single most important fact about this harness: **most of what it prints is noise on a
developer workstation.** Two runs of an identical build have differed by up to 1.93x on the JMH
hot path. Only the allocation numbers are stable enough to make claims from. Everything below
follows from that.

## Step 1 — Decide which question is being asked

**"Does this change make the processor slower?"** Only the allocation sweep can answer that on
an ordinary machine, and only if both versions are measured back-to-back in one sitting. See
Step 4.

**"Capture a baseline for the release."** The full capture in Step 3, recorded under
`load-tests/results/<version>/` with an `env.txt` that says what the machine was doing.

**"Prove this specific optimisation works."** Neither sweep will show it unless it is large.
Write a targeted JMH benchmark or a targeted stress test instead — `WriteCacheHitBenchmark` and
`SignatureCaptureStressTest` are the two worked examples in the repo, and both exist because the
general sweeps could not see the effect they were built to measure.

## Step 2 — Install the version under test first

The harness resolves `vibetags-processor` from the local Maven repository. It does not build it.

```bash
cd vibetags-annotations && mvn install -DskipTests
cd ../vibetags         && mvn install -DskipTests
```

`load-tests/pom.xml` sets `<processor.version>${revision}</processor.version>`, so a plain run
measures whatever version the parent declares. Confirm that rather than assume it:

```bash
cd load-tests && mvn dependency:tree | grep vibetags-processor:jar
```

That line is the only proof that `-Dprocessor.version=...` reached the dependency. Measuring the
wrong jar produces a result that looks entirely reasonable.

## Step 3 — Run it

Each command is separate on purpose; do not chain them through a pipe that eats the exit code.

```bash
cd load-tests
TAG=<version>
mkdir -p results/$TAG

# Sweeps. The cap matters: uncapped, N goes to 10 000 and the run takes hours.
mvn test -Dtest=AnnotationVolumeStressTest,MemoryVolumeStressTest,ConcurrentBuildTest \
         -Dstress.max.classes=1000

# JMH. KEEP THE CLASS FILTER.
mvn package -DskipTests
java -jar target/benchmarks.jar ProcessorHotPathBenchmark -wi 3 -i 5 -f 1 -tu us -bm avgt -prof gc \
     -rf json -rff results/$TAG/jmh.json
java -jar target/benchmarks.jar WriteCacheHitBenchmark -wi 3 -i 5 -f 1 -tu us -bm avgt -prof gc \
     -rf json -rff results/$TAG/jmh-cache-hit.json
```

Without the class filter JMH runs every benchmark it can find and writes them all into
`jmh.json`. That is how `0.9.5` came to have 18 benchmarks in a file every other release has 6
in, and why the release-trend chart compares a different set of bars for that one release.

These runs are long — tens of minutes for the sweeps, a few minutes per JMH class. Start them in
the background and read the log, rather than holding a foreground tool call open: a run that
outlives the tool's idle timeout is killed mid-sweep, and the harness has no resume.

## Step 4 — Read the numbers honestly

**Wall-clock is not evidence.** Measured on this repo's usual box while capturing `1.0.0-RC9`:
two runs of the identical build, minutes apart, differed by 1.17x to 1.93x across the six
hot-path benchmarks, and re-running `0.9.7` reproduced its own recorded baseline only to within
1.4x-3.1x. A difference smaller than that says nothing at all. Say "inside the noise floor",
not "unchanged" — they are different claims.

**Allocation is the metric to quote.** `MemoryVolumeStressTest` counts allocated bytes through
`ThreadMXBean` rather than timing anything, so a busy machine does not move it: two RC9 runs
agreed to within 0.6 % at N=100/500/1000. To compare two versions, measure them back-to-back in
one session, switching only `-Dprocessor.version`, and record the table in
`results/<new>/memory-same-session.txt`.

**Exclude N=10 from any allocation comparison.** Two runs of one build gave 2995 KB and
12187 KB there. Cache initialisation amortises poorly on ten classes.

**The overhead column is about 4x the processor's real cost.** Every `stress.txt` and
`memory.txt` reports `processor − baseline` where the baseline is `-proc:none`, which switches
off javac's entire annotation-processing subsystem — so that whole subsystem lands on VibeTags'
side of the subtraction. `ProcessorTaxStressTest` measures the split with a no-op-processor
control: at N=1000 the javac tax is ~171 MB against VibeTags' own ~57 MB. Release-to-release
*differences* still mean something because the tax cancels; the absolute figure is not VibeTags'
and must not be quoted as if it were.

**The fixture is blind to per-member cost.** `SyntheticClassGenerator` emits one method per
class, which isolates per-*element* cost and hides anything that scales with a type's member
count. The signature-capture change is the worked example: invisible on the 1000-class sweep,
36 MB on 400 classes of 40 members each. If a change touches per-member work, measure it on wide
types — `SignatureCaptureStressTest` is the template.

**`OutputSize(B)` is the correctness check hiding in the perf report.** It is byte-identical
between releases that render the same thing, and an unexplained change means the work product
moved — a functional finding, not a performance one. It is *not* constant across the whole
history, whatever `results/README.md` used to say: it held at
17 156 / 122 555 / 599 895 / 1 196 918 through 0.9.7, moved at 1.0.0-RC1, moved again at
1.0.0-RC9, and has been 14 179 / 101 296 / 495 656 / 988 897 since. Compare against the
previous baseline's own file, not against a number quoted in prose — including this one.

## Step 5 — Record the baseline

```bash
cp $(ls -t target/stress-results-*.txt | head -1) results/$TAG/stress.txt
cp $(ls -t target/memory-results-*.txt | head -1) results/$TAG/memory.txt
cp target/surefire-reports/se.deversity.vibetags.loadtest.ConcurrentBuildTest.txt      results/$TAG/concurrent.txt
cp target/surefire-reports/TEST-se.deversity.vibetags.loadtest.ConcurrentBuildTest.xml results/$TAG/concurrent.xml
```

Then hand-write `results/$TAG/env.txt`. It is not boilerplate — it is what makes the folder
readable in a year. It must carry `java -version`, OS and CPU, the commit sha, **whether the
machine was quiet**, and a "Skipped, not passed" section naming what the cap left out. A capped
run reports `Tests run: 13, Skipped: 4`; those four are N=5000 and N=10000 and the baseline says
nothing whatsoever about them. Write that down rather than letting a future reader assume
coverage.

Regenerate the plots after adding a folder:

```bash
python tools/plot-results.py            # release-trend, auto-discovers results/<x.y.z>/
python tools/plot-cache-hit.py          # cache-hit proof
python tools/plot-release-comparison.py --version $TAG   # same-session allocation comparison
```

## Traps this harness has already paid for

- **`@TempDir` cleanup used to fail on Windows, and no longer does.** `vibetags.log` was held
  open by the file logger, so `concurrent.xml` reported `errors=1` while the test body completed
  and every assertion passed. That holds for the `0.5.4`-`0.8.0` baselines; every baseline from
  `0.9.7` onwards reports `errors="0"`. Do not wave away a fresh `errors=1` on the strength of
  the old caveat without checking which it is — grep the committed `concurrent.xml` files if in
  doubt.
- **A capped sweep is not a full sweep.** `-Dstress.max.classes=500` (what CI runs) skips
  everything above 500 silently. Report skipped separately from passed.
- **CI runs the sweeps but never the JMH benchmarks**, and it compares nothing against a
  baseline. A green `load-tests` job means the harness ran, not that performance held.
- **`cmd | tail` reports `tail`'s exit code.** Every build here writes to a log and the status
  is read from `$?` directly.
- **The JMH class filter is load-bearing.** See Step 3.
- **Do not compare a JMH run against a baseline captured on another machine or JDK.** All
  committed baselines used JDK Temurin 26 on one i7-1260P; the CI matrix is 21/25/26.
