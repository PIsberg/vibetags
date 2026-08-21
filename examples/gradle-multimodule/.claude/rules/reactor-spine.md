---
paths: ["**/gmm/core/**", "**/gmm/app/**"]
---

<!-- VIBETAGS-START -->
# Rules for reactor-spine

<!-- VIBETAGS-MODULE: app -->
## Context & Focus

### com.example.gmm.app.App
- **Focus**: Wiring only: parsing and rendering live in their own modules
- **Avoid**: Business logic

## Security Audit Requirements

### com.example.gmm.app.App
When modifying this element, audit for:
- Path Traversal
<!-- VIBETAGS-MODULE-END: app -->
<!-- VIBETAGS-MODULE: core -->
## Locked Status

### com.example.gmm.core.IrNode
- **Reason**: Core IR node shape is depended on by every downstream module

## Context & Focus

### com.example.gmm.core
- **Focus**: Immutable IR data model shared across every module of the reactor.
- **Avoid**: Adding mutable state, framework annotations, or a dependency on any sibling module.

## Thread-Safety Guarantee

### com.example.gmm.core
- **Strategy**: IMMUTABLE
- **Note**: Every type in this package is safe to publish across threads without synchronization.

## Security-Critical Code

### com.example.gmm.core
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Node identity is a security boundary: never build an IrNode from unvalidated external input, and never expose its raw name in a URL or log line.
<!-- VIBETAGS-MODULE-END: core -->
<!-- VIBETAGS-END -->
