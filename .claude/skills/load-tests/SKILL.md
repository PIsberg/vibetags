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

**"Where did the curve move over the last few releases?"** The release-trend plots cannot answer
it: they overlay per-release captures taken months apart. Sweep every release you care about in
one sitting instead, switching only `-Dprocessor.version`, and plot it with
`tools/plot-release-history.py`. Done over 1.2.5..main on 2026-09-22 this found a 6 % step at
1.3.2 that eight releases of cross-day baselines had not shown. If it finds one, bisect it to a
commit with `git worktree` rather than guessing from the changelog. The guess that session made
from the changelog was wrong, and the commit the measurement named was one whose javadoc asserted
the cost was negligible.

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

**The fixture is blind to anything that depends on where a source file lives.** Every sweep here
hands javac in-memory `JavaFileObject`s with a `string:///` URI. `ModuleRootResolver` resolves a
module root and a source set by walking up from the compilation unit's source **file**, so with
no file it resolves neither: every round is treated as a main round of an unidentified module.
Features keyed on that are therefore not merely unmeasured, they are switched off, and the sweep
still goes green. `TESTING.md` routing is the worked example — opting the volume sweep into
`TESTING.md` and giving it a `pom.xml` still left the file at 0 bytes while `CLAUDE.md` took the
whole model (issue #789). Before measuring anything to do with source sets, module roots, roles,
mirrors or granular paths, assert that the feature engaged; then write sources to disk, as
`TestingMdRoutingStressTest` does, rather than extending a sweep that cannot see it.

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

**The fixture opts into six of 65 output files, and always has.** `OPT_IN_FILES` has held the
same six since 0.5.4, so every committed baseline describes a project that uses six platforms.
Measured at N=500 on 2026-09-22: a fully opted-in project allocates 4.2x what the sweep reports,
and its wall-clock is 17x, because scoped rules write 10 058 files instead of 56. Do not widen the
sweep's fixture to fix this, because that silently invalidates every baseline. `PlatformBreadthStressTest`
measures it beside them, at one fixed N, carrying the six-file level as an anchor column whose
`OutputSize` must stay byte-identical to `stress.txt`'s.

**The fixture emits six of the 44 annotations, and always has.** `SyntheticClassGenerator` rotates
`@AIContext`, `@AILocked`, `@AIAudit`, `@AIIgnore`, `@AIPrivacy` and `@AIDraft`, so 38 formatters
never ran in any sweep here. Same rule as above: do not widen it, measure beside it.
`AnnotationBreadthStressTest` does, and reports the number nothing else here ever has, which is
what one annotation costs on its own. Measured at N=100 on 2026-09-22, marginal over a round with
no annotations: `@AITestDriven` 12.9 MB and `@AILocked` 12.5 MB at the top, `@AIContract` and
`@AIPrivacy` about 5.7 MB at the bottom, a 2.0x spread. Rendered bytes do not predict it, so do
not reason from `OutputSize` about which formatter is expensive: `@AIObservability` renders the
most of any annotation, 121 KB, and costs less than `@AILocked`'s 34 KB. At N=500 the all-44
fixture allocates 10.7x the six-annotation one, for 18x the annotated references, so the cost is
sublinear per annotation on a class.

**Every number here is a cold build.** Each sweep gives its N a fresh `@TempDir`, so
`.vibetags-cache` is empty and the fingerprint has nothing to match: both short-circuits are
structurally unreachable, and the build a developer actually waits for is unmeasured.
`IncrementalRebuildStressTest` measures it. The answer is uncomfortable and worth knowing: the
short-circuit fires and still leaves 96 % of the cost standing at N=1000, because the collector
has walked every annotated element before a fingerprint can be computed.

**Write the engagement assertion before the threshold, and measure before choosing one.** That
last test was first written asserting a 25 % saving, from the reasonable-sounding assumption that
skipping "content build and writes" would be most of the cost. The real figure is 3.6 %. A
threshold picked from a guess fails a healthy build; a threshold picked from one measurement of a
3.6 % effect cannot separate the effect from the floor. Gate on the deterministic thing, here the
processor's own "inputs unchanged since last run" note, asserted present on the warm round and
absent on the cold one, and report the percentage without gating it.

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
- **A JMH run whose every benchmark failed still exits 0**, having written `[]` to the `-rff`
  file. `ProcessorHotPathBenchmark` was dead that way from 1.3.5 to 1.3.7. Its `@Setup(Level.Trial)`
  created every service path as a file, and #684 put `.windsurf/rules/+vibetags-safety.md` inside
  the `.windsurf/rules` directory service, so creating one of the two threw. Nothing anywhere went red, because CI never runs
  JMH. Count the benchmarks in the file after every run; `jmh.json` must hold 6.
- **A caption positioned with `ax.text(..., transform=ax.transAxes)` at a negative y is dropped by
  `savefig` without a word.** Every committed comparison and processor-tax PNG shipped without the
  caption its own script's comment called load-bearing. Use `fig.text(0.5, 0.015, ...)`, and check
  the rendered PNG rather than the code.
- **A test in `load-tests` that calls a processor method directly breaks every older-jar run.**
  The module is deliberately compiled against older `-Dprocessor.version` jars to compare
  releases, so a call to a method those jars lack fails `testCompile` and takes the volume sweeps
  down with it. `PlatformBreadthStressTest` reaches `ServiceRegistry` reflectively and skips when
  the jar is too old, for exactly this reason: it failed a five-point bisect the first time it
  did not.
- **Do not compare a JMH run against a baseline captured on another machine or JDK.** All
  committed baselines used JDK Temurin 26 on one i7-1260P; the CI matrix is 21/25/26.
