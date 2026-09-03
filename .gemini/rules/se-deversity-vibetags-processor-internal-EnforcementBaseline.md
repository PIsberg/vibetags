<!-- VIBETAGS-START -->
# Rules for EnforcementBaseline

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: update() alone is safe, and across processes as well as threads: a per-root monitor plus an exclusive lock on .vibetags-baseline.lock serialise the re-read and rename that a parallel reactor's modules run against one shared file. The read side is an unguarded snapshot on purpose
<!-- VIBETAGS-END -->
