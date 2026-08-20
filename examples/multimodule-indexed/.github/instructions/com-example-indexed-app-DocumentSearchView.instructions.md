---
applyTo: "**/DocumentSearchView.java"
---

<!-- VIBETAGS-START -->
# Copilot Instructions for DocumentSearchView

### Rules for method renderRow
- **Rule**: Do not remove or rename instrumentation without flagging the affected dashboard.
- **Details**: Metrics: search.render.count. Traces: search.render. Logs: search.render.slow. Note: Renaming a metric breaks the search dashboard and its alerts

## Public API Surface Protection
- **Rule**: Exposes public API. Preserve signature, Javadoc, and behavior without breaking backwards or source compatibility.

## Strict Exception Handling
- **Rule**: Robust exception handling required. Prohibit catching/throwing generic Exception/Throwable. Use descriptive, specific/custom exceptions.

## Strict Classpath Integrity
- **Rule**: Prohibit dynamic class loading, custom classloaders, runtime reflection hacks, or execution of dynamic external code.

## Security-Critical Code
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Query handling

### Rules for method loadFromIndex
- **Allowed Callers**: [com.example.indexed.app.DocumentService]

### Rules for method renderRow
- **Policy**: NO_AUTOBOXING
- **Rule**: Strictly limit or prevent object allocations.

### Rules for parameter DocumentSearchView.renderRow(java.lang.String)#highlight
- **Target Filters**: XSS
- **Rule**: Run raw input strings through approved sanitizers.

### Rules for method renderRow
- **Rule**: The following compile here but are prohibited at this element.
- **Forbidden**: java.lang.String.format, java.util.Date
- **Use instead**: StringBuilder and java.time
- **Reason**: Runs per result row; String.format dominates the profile at this call rate

### Rules for method loadFromIndex
- **Rule**: Safe on exactly one thread. This is NOT thread-safety — never add locks to "fix" it; marshal the call instead.
- **Affinity**: NEVER_MAIN (search-worker)
- **Marshal via**: SearchExecutor.submit
- **Symptom if violated**: The UI thread blocks on index I/O and the app stops painting

## Mirrored — Keep In Sync
- **Rule**: Free to change, but every mirror must change in the same commit.
- **Mirrors**: com.example.indexed.core.DocumentIndexEntry
- **Reason**: Projects the index entry field for field; a field added there and not here is invisible to search
- **Enforced by**: DocumentIndexEntryTest#projectionCoversEveryField
<!-- VIBETAGS-END -->
