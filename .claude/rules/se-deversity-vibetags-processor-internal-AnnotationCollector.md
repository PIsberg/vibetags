---
paths: ["**/AnnotationCollector.java"]
---

<!-- VIBETAGS-START -->
# Rules for AnnotationCollector

## Context & Focus
- **Focus**: Accumulates annotated elements across multiple javac processing rounds; one LinkedHashSet per annotation type preserves insertion order for stable BuildFingerprint output
- **Avoid**: Replacing LinkedHashSet with HashSet — insertion order stability is required for deterministic fingerprints across recompiles
<!-- VIBETAGS-END -->
