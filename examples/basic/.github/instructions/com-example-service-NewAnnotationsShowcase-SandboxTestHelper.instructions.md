---
applyTo: "**/SandboxTestHelper.java"
---

<!-- VIBETAGS-START -->
# Copilot Instructions for SandboxTestHelper

## Sandbox Restriction
- **Scope**: Strictly sandbox or test environment only. Never use or invoke from production code.
- **Reason**: Spins up an in-memory mock DB and seeds fake credentials; a prod call path once imported this in a hotfix and leaked test data into staging
<!-- VIBETAGS-END -->
