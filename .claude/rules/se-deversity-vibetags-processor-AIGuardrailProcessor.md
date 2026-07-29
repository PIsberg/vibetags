---
paths: ["**/AIGuardrailProcessor.java"]
---

<!-- VIBETAGS-START -->
# Rules for AIGuardrailProcessor

### Rules for method generateFiles
- **Reason**: Step order is load-bearing: fingerprint check → sidecar write → sidecar read → merge → file write → cache flush; reordering steps silently skips regeneration or corrupts multi-module output

## Core Functionality
- **Sensitivity**: critical
- **Note**: JSR 269 entry point; orchestrates annotation discovery, fingerprint short-circuit, sidecar aggregation, and all file writes

### Rules for method process
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: JSR 269 contract: must return false so peer annotation processors can claim the same annotations; return type is fixed by AbstractProcessor
<!-- VIBETAGS-END -->
