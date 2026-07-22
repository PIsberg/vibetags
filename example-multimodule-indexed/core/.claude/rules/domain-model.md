---
paths: ["**/core/*.java"]
---

<!-- VIBETAGS-START -->
# Rules for domain-model

## com.example.indexed.core.DocumentModel

## Locked Status
- **Reason**: Core document model: structural changes ripple through every module

## Immutable Type
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Shared across threads without copies; every field is final

## Domain Model Boundary
- **Purity**: Framework-free DDD Entity.
<!-- VIBETAGS-END -->
