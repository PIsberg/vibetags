# WriteCacheHitBenchmark — v0.9.7

`writeFileIfChanged` is called once per active platform file per compile round.
The `WriteCache` short-circuits the read+compare path when the output is
unchanged since the last write (recorded as `size + mtime` in `.vibetags-cache`).

## Results

All numbers are **avgt µs/op** (lower = faster), 3 warmup + 5 measurement
iterations, 1 JVM fork, `-prof gc` for allocation.

| Body size | Variant        | Cache hit (µs/op) | No cache (µs/op) | Speedup | Alloc hit (B/op) | Alloc no-cache (B/op) | Alloc reduction |
|-----------|----------------|------------------:|-----------------:|--------:|-----------------:|----------------------:|----------------:|
| 1 KB      | marker (.md)   |              39.7 |            824.2 |   20.8× |            3,232 |                11,454 |            3.5× |
| 1 KB      | non-marker     |              39.3 |            717.5 |   18.3× |            3,368 |                11,418 |            3.4× |
| 12 KB     | marker (.md)   |              29.8 |            641.0 |   21.5× |            3,296 |               101,554 |           30.8× |
| 12 KB     | non-marker     |              39.0 |            922.8 |   23.7× |            3,336 |               101,535 |           30.4× |
| 1 MB      | marker (.md)   |              14.2 |          3,870.2 |  272.5× |            3,248 |             8,392,007 |        2,583.7× |
| 1 MB      | non-marker     |              19.7 |          6,339.7 |  321.8× |            3,336 |             8,392,011 |        2,515.4× |

## Key takeaways

- **Cache-hit latency is body-size independent**: ~14–40 µs/op at all sizes.
  The cache path is one `stat(2)` syscall + one 8-char hex comparison — it does
  not read the file at all, so file size is irrelevant.

- **Allocation at cache hit is constant**: ~3,200 B/op regardless of body size,
  confirming there is no per-byte allocation on the fast path.

- **The speedup scales with file size**: 20× at 1 KB, 22× at 12 KB, 297× at 1 MB.
  This matters for projects with many active platforms: each incremental compile
  where the annotations haven't changed skips 30+ file reads entirely.

- **Allocation reduction at 1 MB**: ~2,550× — the prior CRC32C implementation
  allocated a full `byte[]` copy of the file body on every check. The current
  `size + mtime` approach allocates only the fixed overhead.

## Context: where this shows up for real users

The parallel-writes feature added in v0.9.7 runs all `writeFileIfChanged` calls
concurrently via `ForkJoinPool.commonPool()`. On an incremental compile where
nothing changed (the common case), every call immediately returns at the
`WriteCache.isUnchanged()` check — the parallel overhead is negligible and all
32 active-service files complete in milliseconds.
