---
paths: ["**/Point.java"]
---

<!-- VIBETAGS-START -->
# Rules for Point

## Locked Status
- **Reason**: data

## Load-Bearing Oddity
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: data param
- **Applies to**: `Point.Point(int,int)#y`, `Point.copy(int,int)#y`, `Point.y`
<!-- VIBETAGS-END -->
