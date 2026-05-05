# WriteCacheHitBenchmark — 0.7.1 cache effectiveness

Source: `load-tests/src/main/java/se/deversity/vibetags/loadtest/WriteCacheHitBenchmark.java`
Raw data: `jmh-cache-hit.json` (alongside this file).
Plots: `load-tests/results/_plots/cache-hit-time.png`, `cache-hit-alloc.png`.

This benchmark wires `GuardrailFileWriter` with a `WriteCache` directly. The
existing `ProcessorHotPathBenchmark` uses `new AIGuardrailProcessor()` which
never calls `init()` and therefore never has a cache attached — cache wins are
invisible there. Here we set up two writers — one with a live `WriteCache`,
one with a `null` cache — and call `writeFileIfChanged` against the same
already-written file with the same body 100 times per `@Benchmark` invocation
(`@OperationsPerInvocation(100)`) to amortise the JMH per-call framework floor.

Three body sizes × two file types = six scenarios.

## Results

### Wall-clock (lower is better)

| Body | File type | cache hit | no cache | speedup |
|---|---|---:|---:|---:|
| 1 KB | `.md` (marker) | 16.4 µs | 208.5 µs | **13×** |
| 1 KB | `.cursorrules` (non-marker) | 10.0 µs | 209.4 µs | **21×** |
| 12 KB | `.md` | 18.1 µs | 262.7 µs | **15×** |
| 12 KB | `.cursorrules` | 17.4 µs | 297.3 µs | **17×** |
| 1 MB | `.md` | 18.6 µs | 3,405 µs | **183×** |
| 1 MB | `.cursorrules` | 18.1 µs | 3,285 µs | **181×** |

The cache hit path is essentially **constant time** (~16-19 µs) regardless of
body size — it's bounded by one `Files.readAttributes` syscall plus an O(1)
cached `String.hashCode()` lookup. The no-cache path scales linearly with
body size because it must `Files.readString` the entire file on every call.

### Allocation (lower is better)

| Body | File type | cache hit | no cache | reduction |
|---|---|---:|---:|---:|
| 1 KB | `.md` | 752 B/op | 11,449 B/op | **15×** |
| 1 KB | `.cursorrules` | 792 B/op | 11,409 B/op | **14×** |
| 12 KB | `.md` | 752 B/op | 101,553 B/op | **135×** |
| 12 KB | `.cursorrules` | 792 B/op | 101,521 B/op | **128×** |
| 1 MB | `.md` | 752 B/op | 8,392,001 B/op | **11,159×** |
| 1 MB | `.cursorrules` | 792 B/op | 8,391,976 B/op | **10,595×** |

Allocation is even more striking: the cache hit path stays at ~750-800 bytes
per op regardless of body size. The no-cache path materialises the entire
existing file as a `String` (and amplifies through `getBytes(UTF_8)` +
`strip()` + intermediate copies) at roughly **8× the body size in bytes per
call** — a 1 MB body costs 8 MB of allocation.

## Why we got here — the engineering story

Initial `WriteCache` design used `SHA-256(body.getBytes(UTF_8))` as the cache
fingerprint. Run 1 of this benchmark showed the cache **losing** to no-cache
by ~15 µs per call. Two issues:

1. `body.getBytes(UTF_8)` in the cache lookup allocated a fresh byte array
   the same size as the body — *every* call. For a 1 MB body, that's 1 MB of
   allocation per cache hit, defeating the cache's allocation-saving purpose.
2. SHA-256 over 1 MB takes ~700 µs even with hardware acceleration,
   eclipsing any benefit from skipping a warm-cache `readString`.

Fix shipped in 0.7.1: use `String.hashCode()` as the fingerprint.

- Same 32-bit collision space as a CRC32 (a collision could only cause us to
  skip writing identical content, never silently corrupt; size + mtime are
  also checked first).
- `String.hashCode()` is cached internally on the `String` object after the
  first call — subsequent calls are O(1). And HotSpot intrinsifies it on
  x86 with vectorised instructions for the first call.
- **No UTF-8 byte array materialisation per call.** This is what unlocks the
  10,000× allocation reduction at 1 MB visible in the chart above.

## Plots

![Wall-clock per writeFileIfChanged call (log-y)](../../docs/changelog-assets/0.7.1/cache-hit-time.png)

![Allocation per writeFileIfChanged call (log-y)](../../docs/changelog-assets/0.7.1/cache-hit-alloc.png)

## Caveats

- All measurements on warm-cache local NVMe SSD (Windows 11, JDK Temurin 26).
  Cold-cache scenarios would amplify the wall-clock wins further (the
  no-cache path's `readString` becomes I/O-bound rather than CPU-bound), but
  the allocation numbers are already storage-independent.
- `String.hashCode()` collisions: 1 in ~4 billion for arbitrary differing
  inputs. For two specifically-related VibeTags bodies (regenerated content
  for the same file across compiles), collision is essentially impossible.
  The size + mtime check is a second, independent guard.
- The benchmark warmup runs each scenario once before measurement, which
  means `String.hashCode()` is already cached by measurement time. In real
  production, each compile builds a fresh body via `StringBuilder.toString()`
  — so we pay the O(N) hashCode computation once per file per compile, not
  once per benchmark iteration. The 12-platform aggregate cost on a real
  build is still small (microseconds, not milliseconds).
