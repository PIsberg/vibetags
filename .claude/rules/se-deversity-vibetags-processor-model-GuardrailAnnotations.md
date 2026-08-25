---
paths: ["**/GuardrailAnnotations.java"]
---

<!-- VIBETAGS-START -->
# Rules for GuardrailAnnotations

### Rules for field ALL
- **Reason**: Append only. This order fixes the insertion order of every LinkedHashSet downstream, so reordering or removing an entry rewrites generated files in every consuming build, with nothing failing to name the cause. BuildFingerprint hashes in its own separately pinned order; the two are not the same list and must not be aligned.
<!-- VIBETAGS-END -->
