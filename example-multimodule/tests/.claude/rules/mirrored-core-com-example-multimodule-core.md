---
paths: ["**/core/**/*.java", "**/tests/src/test/java/**/*.java"]
---

<!-- VIBETAGS-START -->
# Rules for core

## Context & Focus
- **Focus**: Immutable IR data model shared across every module of the reactor.
- **Avoid**: Adding mutable state, framework annotations, or a dependency on any sibling module.

## Thread-Safety Guarantee
- **Strategy**: IMMUTABLE
- **Note**: Every type in this package is safe to publish across threads without synchronization.

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Node identity is a security boundary: never build an IrNode from unvalidated external input, and never expose its raw id in a URL or log line.
<!-- VIBETAGS-END -->
