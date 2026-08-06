---
paths: ["**/AnnotationCollector.java"]
---

<!-- VIBETAGS-START -->
# Rules for AnnotationCollector

## Context & Focus
- **Focus**: Accumulates annotated elements across multiple javac processing rounds, then snapshots them into a compiler-free GuardrailModel. Ordering is settled in GuardrailModel, which sorts every bucket by TaggedElement.path() — javac's getElementsAnnotatedWith has no specified iteration order, so anything that preserves it makes generated output depend on which machine compiled it
- **Avoid**: Restoring javac's iteration order as the output order, here or in GuardrailModel — it differs between Maven and Gradle and between machines, which churns committed guardrail files and misses the write cache. OutputOrderDeterminismTest pins it
<!-- VIBETAGS-END -->
