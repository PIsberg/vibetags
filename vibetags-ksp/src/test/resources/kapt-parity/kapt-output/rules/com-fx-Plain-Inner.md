---
paths: ["**/Inner.java"]
---

<!-- VIBETAGS-START -->
# Rules for Inner

## Locked Status
- **Reason**: inner class

### Rules for method inf
- **Reason**: inner fun

## Load-Bearing Oddity
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: inner ctor p
- **Applies to**: `Inner.Inner(int)#q`, `Inner.q`
<!-- VIBETAGS-END -->
