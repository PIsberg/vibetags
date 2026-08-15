<!-- VIBETAGS-START -->
# Rules for ModuleSidecar

## Core Functionality
- **Sensitivity**: high
- **Note**: Per-module sidecar for multi-module Maven/Gradle builds; the .vibetags-mod-* file format is shared across independently compiled modules — format changes break backward compatibility

### Rules for method mergeFor
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Sub-marker format constants (SUB_MARKER_*_FORMAT) are embedded in generated CLAUDE.md and .cursorrules; changing them silently corrupts multi-module merged output on the next compile

## Thread-Safety Guarantee
- **Strategy**: OTHER
- **Note**: Atomic temp-file moves (ATOMIC_MOVE with plain-move fallback); concurrent saves and reads never tear a sidecar or prune a sibling's (ModuleSidecarAsyncTest proves it)
<!-- VIBETAGS-END -->
