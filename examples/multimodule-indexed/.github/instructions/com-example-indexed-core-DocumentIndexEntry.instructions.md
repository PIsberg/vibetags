---
applyTo: "**/DocumentIndexEntry.java"
---

<!-- VIBETAGS-START -->
# Copilot Instructions for DocumentIndexEntry

## Core Functionality
- **Sensitivity**: high
- **Note**: Index entries are read by every module; a field change is a format change

### Rules for method compareBySequence
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: O(1). Called once per document per query; anything that allocates shows up in the p99

## Thread-Safety Guarantee
- **Strategy**: IMMUTABLE
- **Note**: Every field is final; share freely

## Architectural Boundary Constraints
- **Layer**: domain
- **Prohibited References**: com.example.indexed.app, javax.servlet

## Strict Type Safety
- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety.
- **Reason**: Identifiers are typed to stop a title being passed where an id belongs

## Schema & Serialization Safety
- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths.
- **Reason**: Persisted to the document store; field order and names are the on-disk format

### Rules for field sequence
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: Sort order of the index depends on this being monotonically increasing
- **Breaks if changed**: A caller assigns a sequence lower than one already issued

### Rules for field documentId
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: com.example.indexed.app.DocumentSearchView
- **Reason**: The search view projects these fields verbatim; adding one here without adding it there hides it from search
- **Enforced by**: DocumentIndexEntryTest#projectionCoversEveryField
<!-- VIBETAGS-END -->
