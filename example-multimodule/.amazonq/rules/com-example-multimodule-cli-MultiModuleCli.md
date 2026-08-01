<!-- VIBETAGS-START -->
# Amazon Q Rules for MultiModuleCli

## Security Audit Requirements
When modifying this element, audit for:
- Path Traversal

### Rules for method run
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public CLI surface; flags are documented downstream

## Test-Driven Requirements
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 100%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/com/example/multimodule/cli

### Rules for method outputPath
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Derives output path from inputs only

### Rules for method outputPath
- **Rule**: Must remain a pure function. Forbid state modifications and side effects.
<!-- VIBETAGS-END -->
