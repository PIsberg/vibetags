---
paths: ["**/GuardrailFileWriter.java"]
---

<!-- VIBETAGS-START -->
# Rules for GuardrailFileWriter

## Core Functionality
- **Sensitivity**: high
- **Note**: Atomic marker-aware file writer; invariant: hand-authored content outside VIBETAGS-START/END markers must never be overwritten or lost

### Rules for method writeFileIfChanged
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public API since v0.1; tests and the processor both bind to the (String path, String content, boolean hasNewRules) signature and return semantics

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 90%
- **Frameworks**: JUNIT_5
- **Mock Policy**: Write the failing test first; marker preservation is asserted on real files with hand content around the block, never on string fixtures alone

## Thread-Safety Guarantee
- **Strategy**: IMMUTABLE
- **Note**: Stateless aside from injected Messager/Logger references; every write is an atomic temp-file replace, so the parallel write phase never interleaves partial content (GuardrailFileWriterAsyncTest proves it)
<!-- VIBETAGS-END -->
