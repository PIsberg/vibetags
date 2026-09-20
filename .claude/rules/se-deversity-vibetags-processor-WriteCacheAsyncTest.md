---
paths: ["**/WriteCacheAsyncTest.java"]
---

<!-- VIBETAGS-START -->
# Rules for WriteCacheAsyncTest

## Strict Test Isolation
- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources.
- **Reason**: Must keep running alone. Real platform threads plus detector instrumentation generate load that reaches the javac-based end-to-end tests beside it, and those fail as flakes far from this file, so the cost never points back here
<!-- VIBETAGS-END -->
