---
paths: ["**/*Entry.java", "**/*Rules.java"]
---

<!-- VIBETAGS-START -->
# Rules for domain-model

## Core Functionality

### com.example.alltiers.billing.LedgerEntry
- **Sensitivity**: critical
- **Note**: Every monetary total in the system is derived from these rows

## Immutable Type

### com.example.alltiers.billing.LedgerEntry
- **Rule**: This type is immutable. Never introduce non-final fields, setters, or mutating methods.
- **Note**: Shared across the reconciliation threads without copying

## Strict Type Safety

### com.example.alltiers.billing.LedgerEntry
- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety.

## Schema & Serialization Safety

### com.example.alltiers.billing.LedgerEntry
- **Rule**: Prohibit altering data formats, fields, database columns, or serialization structures without explicit backward-compatible migration paths.

## Load-Bearing Oddity

### com.example.alltiers.billing.LedgerEntry.sequence
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: Entries are appended in sequence order and never renumbered
- **Breaks if changed**: A caller reuses a sequence, which silently merges two entries in the warehouse

## Test-Driven Requirements

### com.example.alltiers.billing.TaxRules
- **Rule**: Changes MUST be accompanied by a matching test update.
- **Coverage Goal**: 100%
- **Frameworks**: JUNIT_5
- **Test Location**: src/test/java/com/example/alltiers/billing
- **Mock Policy**: Use fixed rate tables; never call the live rate service from a unit test

## Thread-Safety Guarantee

### com.example.alltiers.billing.TaxRules
- **Strategy**: IMMUTABLE
- **Note**: Rate tables are loaded once and never mutated

## Regulatory Compliance

### com.example.alltiers.billing.TaxRules
- **Standard**: EU VAT Directive
- **Clause**: Art. 98
- **Description**: Reduced rates apply per member state and per product category

## Architectural Boundary Constraints

### com.example.alltiers.billing.TaxRules
- **Layer**: domain
- **Prohibited References**: com.example.alltiers.shipping, javax.servlet

## Internationalization Mandate

### com.example.alltiers.billing.TaxRules
- **Rule**: Prohibit hardcoding user-facing strings, labels, or messages. All user-visible text must be resolved via localization resources.

## Idempotency Guarantee

### com.example.alltiers.billing.TaxRules.applyStandardRate(java.math.BigDecimal)
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Applying the rate twice must equal applying it once for the same input

## Mathematical Purity

### com.example.alltiers.billing.TaxRules.applyStandardRate(java.math.BigDecimal)
- **Rule**: Must remain a pure function. Forbid state modifications and side effects.

## Domain Model Boundary

### com.example.alltiers.billing.TaxRules
- **Purity**: Framework-free DDD Entity.
- **Allowed Imports**: java.math.BigDecimal

## Chain-of-Thought Explanation

### com.example.alltiers.billing.TaxRules.reducedRateFor(java.lang.String,java.lang.String)
- **Complexity Level**: HIGH
- **Rule**: Any logic modification requires updating a walkthrough/markdown file with structured architectural rationale.
<!-- VIBETAGS-END -->
