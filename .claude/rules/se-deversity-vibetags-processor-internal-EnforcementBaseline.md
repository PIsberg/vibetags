---
paths: ["**/EnforcementBaseline.java"]
---

<!-- VIBETAGS-START -->
# Rules for EnforcementBaseline

## Context & Focus
- **Focus**: Consumers commit .vibetags-baseline, so a new format must keep reading format 1
- **Avoid**: Changing FORMAT_MARKER alone: exists() then reports the committed baseline as absent, and GuardrailEnforcer answers that with a warning and a pass, so enforcement switches off on upgrade with green builds

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: update() alone is safe, and across processes as well as threads: a per-root monitor plus an exclusive lock on .vibetags-baseline.lock serialise the re-read and rename that a parallel reactor's modules run against one shared file. The read side is an unguarded snapshot on purpose
<!-- VIBETAGS-END -->
