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
<!-- VIBETAGS-END -->
