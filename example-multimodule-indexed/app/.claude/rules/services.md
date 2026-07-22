---
paths: ["**/app/*.java"]
---

<!-- VIBETAGS-START -->
# Rules for services

## com.example.indexed.app.DocumentService

## Security Audit Requirements
When modifying this element, audit for:
- Path Traversal
- Insecure Deserialization

### Rules for method render
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public service surface consumed across module boundaries

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 100%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/com/example/indexed/app

### Rules for method storageKey
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Derives the storage key from inputs only

### Rules for method storageKey
- **Rule**: Must remain a pure function. Forbid state modifications and side effects.
<!-- VIBETAGS-END -->
