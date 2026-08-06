---
paths: ["**/core/*.java"]
---

<!-- VIBETAGS-START -->
# Rules for domain-model

## Locked Status

### com.example.indexed.core.DocumentModel
- **Reason**: Core document model: structural changes ripple through every module

## Immutable Type

### com.example.indexed.core.DocumentModel
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Shared across threads without copies; every field is final

## Domain Model Boundary

### com.example.indexed.core.DocumentModel
- **Purity**: Framework-free DDD Entity.

## Context & Focus

### com.example.indexed.core.DocumentRetention
- **Focus**: Retention windows and the legal basis for them
- **Avoid**: Do not shorten a window to make a test pass; the windows are set by the standards named below

## Exclusion Rule

### com.example.indexed.core.DocumentRetention.cachedExpiryEpochDay
This element is strictly excluded from AI context. Do not reference it.

## PII / Privacy Guardrails

### com.example.indexed.core.DocumentRetention.ownerEmail
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Owner email identifies a natural person; never log it or put it in a suggestion

## Deprecated — Migrate Callers

### com.example.indexed.core.DocumentRetention.expiryFromNow(int)
- **Replaced by**: expiryEpochDay(long, int)
- **Migration**: Pass the creation day explicitly instead of relying on the system clock
- **Deadline**: 2027-01-01

## Regulatory Compliance

### com.example.indexed.core.DocumentRetention
- **Standard**: GDPR
- **Clause**: Art. 5(1)(e)
- **Description**: Storage limitation: documents are kept no longer than the stated purpose requires

## Internationalization Mandate

### com.example.indexed.core.DocumentRetention
- **Rule**: Prohibit hardcoding user-facing strings, labels, or messages. All user-visible text must be resolved via localization resources.

## Secure Logging Masking

### com.example.indexed.core.DocumentRetention.ownerEmail
- **Policy**: HASH
- **Rule**: Never pass this raw variable to log appenders or stdout streams.

## Chain-of-Thought Explanation

### com.example.indexed.core.DocumentRetention.expiryEpochDay(long,int)
- **Complexity Level**: HIGH
- **Rule**: Any logic modification requires updating a walkthrough/markdown file with structured architectural rationale.

## Sunset Element

### com.example.indexed.core.DocumentRetention.expiryFromNow(int)
- **Status**: Strict Deprecation (No new references)
- **JIRA Ticket**: DOC-4471
- **Replacement**: java.lang.Object

## Temporary Workaround

### com.example.indexed.core.DocumentRetention.legacyRetentionDays(java.lang.String)
- **Expiration**: 2026-12-31
- **Reason**: Bridges the pre-2026 records that stored a retention class instead of a day count
- **Rule**: Hotfix or stub that must be removed before expiration.

## Core Functionality

### com.example.indexed.core.DocumentIndexEntry
- **Sensitivity**: high
- **Note**: Index entries are read by every module; a field change is a format change

## Performance Constraints

### com.example.indexed.core.DocumentIndexEntry.compareBySequence(com.example.indexed.core.DocumentIndexEntry)
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: O(1). Called once per document per query; anything that allocates shows up in the p99

## Thread-Safety Guarantee

### com.example.indexed.core.DocumentIndexEntry
- **Strategy**: IMMUTABLE
- **Note**: Every field is final; share freely

## Architectural Boundary Constraints

### com.example.indexed.core.DocumentIndexEntry
- **Layer**: domain
- **Prohibited References**: com.example.indexed.app, javax.servlet

## Strict Type Safety

### com.example.indexed.core.DocumentIndexEntry
- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety.

## Schema & Serialization Safety

### com.example.indexed.core.DocumentIndexEntry
- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths.

## Load-Bearing Oddity

### com.example.indexed.core.DocumentIndexEntry.sequence
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: Sort order of the index depends on this being monotonically increasing
- **Breaks if changed**: A caller assigns a sequence lower than one already issued

## Mirrored — Keep In Sync

### com.example.indexed.core.DocumentIndexEntry.documentId
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: com.example.indexed.app.DocumentSearchView
- **Reason**: The search view projects these fields verbatim; adding one here without adding it there hides it from search
- **Enforced by**: DocumentIndexEntryTest#projectionCoversEveryField
<!-- VIBETAGS-END -->
