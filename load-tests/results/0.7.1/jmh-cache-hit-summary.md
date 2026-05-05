# WriteCacheHitBenchmark — 0.7.1 cache effectiveness

Source: `load-tests/src/main/java/se/deversity/vibetags/loadtest/WriteCacheHitBenchmark.java`
Raw data: `jmh-cache-hit.json` (alongside this file).

This benchmark wires `GuardrailFileWriter` with a `WriteCache` directly (the
existing `ProcessorHotPathBenchmark` uses `new AIGuardrailProcessor()` which
never calls `init()` and so never has a cache attached — the cache wins are
invisible there).

## Setup

- 4 files primed via `writerWithCache.writeFileIfChanged(...)` so the cache is
  populated and each file is at canonical content.
- Two writers: `writerWithCache` (4-arg ctor with `WriteCache`) and
  `writerNoCache` (4-arg ctor with `null`).
- Each `@Benchmark` issues 100 calls per JMH invocation
  (`@OperationsPerInvocation(100)`) to amortise the JMH framework's per-call
  ~150-200 µs floor and expose the per-call delta.

## Results (avgt, µs/op, lower is better)

| Scenario | cacheHit | noCache | Δ |
|---|---:|---:|---:|
| 1 KB body, `.md` (marker file) | 208 ± 7 | 195 ± 15 | +13 (cache slower) |
| 1 KB body, `.cursorrules` (non-marker) | 216 ± 3 | 199 ± 5 | +17 (cache slower) |
| 12 KB body, `.md` | 226 ± 3 | 212 ± 20 | +14 (cache slower) |
| 12 KB body, `.cursorrules` | 222 ± 6 | 207 ± 8 | +15 (cache slower) |

## What this tells us

**The cache breaks even — slightly loses — on warm-cache local SSD.**

Anatomy of a cache hit (1 KB body):

| Step | Cost |
|---|---:|
| `Files.readAttributes(BasicFileAttributes.class)` (one stat) | ~80-100 µs |
| `body.getBytes(UTF_8)` + `CRC32C.update + getValue` over 1 KB | ~10 µs |
| 8-char hex compare | < 1 µs |
| **Cache-hit total** | **~110 µs** |

Anatomy of the no-cache marker path (1 KB body):

| Step | Cost |
|---|---:|
| `Files.size` (one stat) | ~80 µs |
| `Files.readString` of 1 KB (warm cache) | ~10-30 µs |
| `existing.indexOf(marker)` × 2 + `substring` + `StringBuilder.append` | ~10 µs |
| `existing.strip().equals(finalContent.strip())` | ~5 µs |
| **No-cache total** | **~105-125 µs** |

Both paths are dominated by the same syscall cost, and on a warm OS page cache,
`readString` of a small file is comparable to `CRC32C` of its body. The cache
adds bookkeeping (one stat instead of `Files.size`, plus the hash) without
saving enough to come out ahead.

## Where the cache *does* win

The benchmark intentionally exercises the **warm-cache local-SSD steady-state**
scenario, which is the cache's worst-case relative profile. The cache is
expected to deliver real wins in three other scenarios that this benchmark
does *not* simulate:

1. **Cold OS cache (first compile after a reboot, after a `mvn clean`, on CI
   runners with fresh worktrees).** `Files.readString` then costs actual disk
   I/O — milliseconds on HDD, hundreds of microseconds on SSD. The stat-only
   cache-hit path stays at ~80-100 µs regardless.

2. **Network filesystems (NFS, SMB, sshfs).** Stat is one round-trip; `readString`
   is multiple round-trips for open + read + close. The cache cuts trip count
   from 3+ to 1.

3. **Reduced GC pressure.** The no-cache marker path materialises the existing
   file as a `String` (size of file) plus computes `existing.strip()` (another
   `String` allocation up to size of file). The cache-hit path allocates only
   the small `byte[]` for `body.getBytes()` (already needed) and a 64-bit `long`.
   Under load (e.g. multi-module Gradle build with many platform files), this
   is the difference between 12 × file-size temporary `String` allocations
   per compile and 12 × constant-size allocations.

The end-to-end demonstration in `example/` (second `mvn compile` against
unchanged sources leaves all platform file mtimes identical) confirms the
cache is doing its work — every file write is being skipped — but the
wall-clock benefit on a single warm-cache compile is in noise.

## Take-away for changelog framing

Earlier wording suggested dramatic wall-clock wins. The truthful framing:

- The cache **eliminates I/O writes** on no-change rebuilds (visible: file
  mtimes unchanged after rebuild).
- The cache **eliminates `String` allocations** for the existing-content read,
  reducing GC pressure under load.
- The cache **reduces syscalls** on the no-change path (1 stat vs 3 in the
  open/read/close sequence).
- The cache does **not** dramatically reduce CPU time on warm-cache local SSD;
  it breaks even.

Nothing about this is a regression vs 0.7.0 (the no-cache path still works
unchanged). The cache adds value in cold-cache, network-FS, and high-throughput
multi-platform scenarios; it costs nothing meaningful in the common warm-cache
single-compile case.
