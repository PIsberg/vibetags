---
paths: ["**/*Entry.java"]
---

<!-- VIBETAGS-START -->
# Rules for domain-model

## Core Functionality

### com.example.alltiers.billing.LedgerEntry
- **Sensitivity**: critical
- **Note**: Every monetary total in the system is derived from these rows

## Immutable Type

### com.example.alltiers.billing.LedgerEntry
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Shared across the reconciliation threads without copying

## Strict Type Safety

### com.example.alltiers.billing.LedgerEntry
- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety.

## Schema & Serialization Safety

### com.example.alltiers.billing.LedgerEntry
- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths.

## Load-Bearing Oddity

### com.example.alltiers.billing.LedgerEntry.sequence
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: Entries are appended in sequence order and never renumbered
- **Breaks if changed**: A caller reuses a sequence, which silently merges two entries in the warehouse
<!-- VIBETAGS-END -->
