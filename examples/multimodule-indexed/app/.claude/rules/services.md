---
paths: ["**/app/*.java"]
---

<!-- VIBETAGS-START -->
# Rules for services

## Security Audit Requirements

### com.example.indexed.app.DocumentService
When modifying this element, audit for:
- Path Traversal
- Insecure Deserialization

## Contract-Frozen Signature

### com.example.indexed.app.DocumentService.render(com.example.indexed.core.DocumentModel)
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public service surface consumed across module boundaries

## Test-Driven Requirements

### com.example.indexed.app.DocumentService
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 100%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/com/example/indexed/app

## Idempotency Guarantee

### com.example.indexed.app.DocumentService.storageKey(java.lang.String,java.lang.String)
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Derives the storage key from inputs only

## Mathematical Purity

### com.example.indexed.app.DocumentService.storageKey(java.lang.String,java.lang.String)
- **Rule**: Must remain a pure function. Forbid state modifications and side effects.

## Implementation Tasks

### com.example.indexed.app.DocumentImportJob.importAll(java.lang.String)
- **Instruction**: Implement resumable import: checkpoint every 1000 rows and restart from the last checkpoint

## Strict Test Isolation

### com.example.indexed.app.DocumentImportJob
- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources.
- **Reason**: Each import runs against its own temporary directory and shares no state

## Legacy Compatibility Bridge

### com.example.indexed.app.DocumentImportJob.mapLegacyColumn(java.lang.String)
- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested.
- **Reason**: Translates the pre-2020 column names; deleted once the last tenant is migrated

## Feature Flag Gate

### com.example.indexed.app.DocumentImportJob.useV2Pipeline()
- **Flag**: 'import.v2.enabled' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

## Sandbox Restriction

### com.example.indexed.app.DocumentImportJob.reindexEverything()
- **Scope**: Strictly sandbox or test environment only. Never use or invoke from production code.
- **Reason**: Writes directly to the index without validation; catastrophic against production data

## Polymorphic Extension Pattern

### com.example.indexed.app.DocumentImportJob
- **Pattern**: STRATEGY_PATTERN
- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.

## Experimental Prototype

### com.example.indexed.app.DocumentImportJob
- **Scope**: Rapid prototype. QA rules and strict coverage metrics are temporarily suspended.
- **Reason**: Shape of the import pipeline is still being decided; do not build on these types

## Generated — Edit The Source

### com.example.indexed.app.DocumentImportJob.LEGACY_COLUMNS
- **Rule**: Machine-generated. Read it, never write it — hand edits are silently overwritten.
- **Generated from**: schema/legacy-import.yaml
- **Edit instead**: schema/legacy-import.yaml
- **Regenerate with**: mvn generate-sources

## Observability Instrumentation

### com.example.indexed.app.DocumentSearchView.renderRow(java.lang.String)
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: search.render.count. Traces: search.render. Logs: search.render.slow. Note: Renaming a metric breaks the search dashboard and its alerts

## Public API Surface Protection

### com.example.indexed.app.DocumentSearchView
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.
- **Reason**: Returned from the public search endpoint; the field names are the wire format

## Strict Exception Handling

### com.example.indexed.app.DocumentSearchView
- **Rule**: Robust exception handling required. Prohibit catching/throwing generic Exception/Throwable. Use descriptive, specific/custom exceptions.
- **Reason**: Search failures must surface as SearchException, never a raw runtime type

## Strict Classpath Integrity

### com.example.indexed.app.DocumentSearchView
- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.
- **Reason**: Serialized by the platform's own Jackson; adding a second JSON library changes the output

## Security-Critical Code

### com.example.indexed.app.DocumentSearchView
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Query handling

## Access Restrictions

### com.example.indexed.app.DocumentSearchView.loadFromIndex(long)
- **Allowed Callers**: [com.example.indexed.app.DocumentService]

## Memory Budget Constraints

### com.example.indexed.app.DocumentSearchView.renderRow(java.lang.String)
- **Policy**: NO_AUTOBOXING
- **Rule**: Strictly limit or prevent object allocations.

## Input Sanitization

### com.example.indexed.app.DocumentSearchView.renderRow(java.lang.String)#highlight
- **Target Filters**: XSS
- **Rule**: Run raw input strings through approved sanitizers.

## Banned APIs

### com.example.indexed.app.DocumentSearchView.renderRow(java.lang.String)
- **Rule**: The following compile here but are prohibited at this element.
- **Forbidden**: java.lang.String.format, java.util.Date
- **Use instead**: StringBuilder and java.time
- **Reason**: Runs per result row; String.format dominates the profile at this call rate

## Thread Affinity

### com.example.indexed.app.DocumentSearchView.loadFromIndex(long)
- **Rule**: Safe on exactly one thread. This is NOT thread-safety — never add locks to "fix" it; marshal the call instead.
- **Affinity**: NEVER_MAIN (search-worker)
- **Marshal via**: SearchExecutor.submit
- **Symptom if violated**: The UI thread blocks on index I/O and the app stops painting

## Mirrored — Keep In Sync

### com.example.indexed.app.DocumentSearchView
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: com.example.indexed.core.DocumentIndexEntry
- **Reason**: Projects the index entry field for field; a field added there and not here is invisible to search
- **Enforced by**: DocumentIndexEntryTest#projectionCoversEveryField
<!-- VIBETAGS-END -->
