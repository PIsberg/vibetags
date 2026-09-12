---
description: "AI rules for com.example.concurrent.SessionCache"
alwaysApply: true
---

<!-- VIBETAGS-START -->
# Rules for SessionCache

## Thread-Safety Guarantee
- **Strategy**: LOCK_FREE
- **Note**: All mutations go through ConcurrentHashMap; never introduce a synchronized block on the cache map.
<!-- VIBETAGS-END -->
