<!-- VIBETAGS-START -->
# Rules for reactor-spine

<!-- VIBETAGS-MODULE: cli -->
## Security Audit Requirements

### com.example.multimodule.cli.MultiModuleCli
When modifying this element, audit for:
- Path Traversal

## Contract-Frozen Signature

### com.example.multimodule.cli.MultiModuleCli.run(java.lang.String[])
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Public CLI surface; flags are documented downstream

## Test-Driven Requirements

### com.example.multimodule.cli.MultiModuleCli
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 100%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/com/example/multimodule/cli

## Idempotency Guarantee

### com.example.multimodule.cli.MultiModuleCli.outputPath(java.lang.String,java.lang.String)
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Derives output path from inputs only

## Mathematical Purity

### com.example.multimodule.cli.MultiModuleCli.outputPath(java.lang.String,java.lang.String)
- **Rule**: Must remain a pure function. Forbid state modifications and side effects.
<!-- VIBETAGS-MODULE-END: cli -->
<!-- VIBETAGS-MODULE: core -->
## Locked Status

### com.example.multimodule.core.IrNode
- **Reason**: Core IR node: structural changes break every downstream module

## Domain Model Boundary

### com.example.multimodule.core.IrNode
- **Purity**: Framework-free DDD Entity.

## Context & Focus

### com.example.multimodule.core
- **Focus**: Immutable IR data model shared across every module of the reactor.
- **Avoid**: Adding mutable state, framework annotations, or a dependency on any sibling module.

## Thread-Safety Guarantee

### com.example.multimodule.core
- **Strategy**: IMMUTABLE
- **Note**: Every type in this package is safe to publish across threads without synchronization.

## Security-Critical Code

### com.example.multimodule.core
- **Rule**: This code is security-critical. Do not weaken security properties. Every change must be explicitly reviewed for security impact.
- **Aspect**: Node identity is a security boundary: never build an IrNode from unvalidated external input, and never expose its raw id in a URL or log line.

## Immutable Type

### com.example.multimodule.core.IrGraph
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
<!-- VIBETAGS-MODULE-END: core -->
<!-- VIBETAGS-MODULE: engine -->
## Thread-Safety Guarantee

### com.example.multimodule.engine.LayoutEngine
- **Strategy**: SYNCHRONIZED
- **Note**: Stateless; safe to share across render threads

## Polymorphic Extension Pattern

### com.example.multimodule.engine.LayoutEngine
- **Pattern**: STRATEGY_PATTERN
- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.
<!-- VIBETAGS-MODULE-END: engine -->
<!-- VIBETAGS-END -->
