---
applyTo: "**/DocumentImportJob.java"
---

<!-- VIBETAGS-START -->
# Copilot Instructions for DocumentImportJob

### Rules for method importAll
- **Instruction**: Implement resumable import: checkpoint every 1000 rows and restart from the last checkpoint

## Strict Test Isolation
- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources.

### Rules for method mapLegacyColumn
- **Rule**: Compatibility bridge. Do not attempt to modernize, elegant-ize, or refactor structural patterns. Only modify internal business logic as explicitly requested.

### Rules for method useV2Pipeline
- **Flag**: 'import.v2.enabled' (default: false)
- **Rule**: This code is gated behind a feature flag. Preserve the flag check. Never assume the flag is always active.

### Rules for method reindexEverything
- **Scope**: Strictly sandbox or test environment only. Never use or invoke from production code.

## Polymorphic Extension Pattern
- **Pattern**: STRATEGY_PATTERN
- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.

## Experimental Prototype
- **Scope**: Rapid prototype. QA rules and strict coverage metrics are temporarily suspended.

### Rules for field LEGACY_COLUMNS
- **Rule**: Machine-generated. Read it, never write it — hand edits are silently overwritten.
- **Generated from**: schema/legacy-import.yaml
- **Edit instead**: schema/legacy-import.yaml
- **Regenerate with**: mvn generate-sources
<!-- VIBETAGS-END -->
