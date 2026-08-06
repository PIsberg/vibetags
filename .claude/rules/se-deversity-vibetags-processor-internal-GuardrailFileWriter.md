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
<!-- VIBETAGS-END -->
