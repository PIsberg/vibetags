---
paths: ["**/WriteCache.java"]
---

<!-- VIBETAGS-START -->
# Rules for WriteCache

## Core Functionality
- **Sensitivity**: high
- **Note**: Per-file content cache backed by .vibetags-cache; false positives (wrongly treating stale output as unchanged) would silently corrupt generated files

### Rules for method isUnchanged
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: O(1): one stat(2) syscall plus one 8-char string compare; must not allocate byte[] — the prior CRC32C implementation did and was removed for this reason

## Thread-Safety Guarantee
- **Strategy**: SYNCHRONIZED
- **Note**: Safe for concurrent calls on one instance (WriteCacheAsyncTest proves it); instances must own disjoint roots, because two instances over the same .vibetags-cache race by design
<!-- VIBETAGS-END -->
