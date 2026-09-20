---
paths: ["**/IrGraphFixturesTest.java", "**/tests/src/test/java/**/*.java"]
---

<!-- VIBETAGS-START -->
# Rules for IrGraphFixturesTest

## Locked Status
- **Reason**: The golden graphs below are the layout engine's recorded output for the 1.0 release. Regenerating them makes the suite agree with the current engine instead of with the release it is meant to protect.

## Context & Focus
- **Focus**: Build IrGraph fixtures through IrGraphFixtures.of(...), never by mutating a graph in place: the engine's layout cache keys on node identity
- **Avoid**: Sharing one fixture graph between cases; the engine mutates what it lays out
<!-- VIBETAGS-END -->
