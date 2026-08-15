---
paths: ["**/ModuleSidecar.java"]
---

<!-- VIBETAGS-START -->
# Rules for ModuleSidecar

## Core Functionality
- **Sensitivity**: high
- **Note**: Per-module sidecar for multi-module Maven/Gradle builds; the .vibetags-mod-* file format is shared across independently compiled modules — format changes break backward compatibility

### Rules for method mergeFor
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Sub-marker format constants (SUB_MARKER_*_FORMAT) are embedded in generated CLAUDE.md and .cursorrules; changing them silently corrupts multi-module merged output on the next compile

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 90%
- **Frameworks**: JUNIT_5
- **Mock Policy**: Write the failing test first; the sidecar format is cross-module law, so tests read and write real .vibetags-mod-* files, never mocks of them

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Atomic temp-file moves (ATOMIC_MOVE with plain-move fallback); concurrent saves and reads never tear a sidecar or prune a sibling's (ModuleSidecarAsyncTest proves it)
<!-- VIBETAGS-END -->
