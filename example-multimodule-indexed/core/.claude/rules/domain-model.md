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
<!-- VIBETAGS-END -->
