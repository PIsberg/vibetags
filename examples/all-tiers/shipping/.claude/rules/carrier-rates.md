---
paths: ["**/*Adapter.java", "**/*Calculator.java"]
---

<!-- VIBETAGS-START -->
# Rules for carrier-rates

## Implementation Tasks

### com.example.alltiers.shipping.RateCalculator.zoneFor(java.lang.String)
- **Instruction**: Implement zone lookup from the rate table, falling back to zone 9 for unknown postcodes, and cache the result per (carrier, zone) pair

## Idempotency Guarantee

### com.example.alltiers.shipping.RateCalculator.quote(java.lang.String,int)
- **Rule**: This operation is idempotent. Calling it multiple times must produce the same result as calling it once.
- **Reason**: Re-quoting the same shipment must return the same price, not a new one

## Sandbox Restriction

### com.example.alltiers.shipping.RateCalculator.primeTestRates()
- **Scope**: Strictly sandbox or test environment only. Never use or invoke from production code.
- **Reason**: Talks to the carrier's test endpoint with seeded credentials

## Experimental Prototype

### com.example.alltiers.shipping.RateCalculator
- **Scope**: Rapid prototype. QA rules and strict coverage metrics are temporarily suspended.
- **Reason**: Spike for the Q3 carrier-rate evaluation; no error handling on purpose

## Generated — Edit The Source

### com.example.alltiers.shipping.RateCalculator.RATE_COLUMNS
- **Rule**: Machine-generated. Read it, never write it — hand edits are silently overwritten.
- **Generated from**: schema/carrier-rates.yaml
- **Edit instead**: schema/carrier-rates.yaml
- **Regenerate with**: mvn generate-sources

## Thread-Safety Guarantee

### com.example.alltiers.shipping.CarrierAdapter
- **Strategy**: LOCK_FREE
- **Note**: Adapters are looked up from a ConcurrentHashMap; never wrap the lookup in synchronized

## Strict Test Isolation

### com.example.alltiers.shipping.CarrierAdapter
- **Rule**: Strict test isolation required. AI-generated or modified tests must not share mutable state, rely on execution order, or conflict on external resources.
- **Reason**: Each adapter test binds its own mock carrier port and shares no state

## Memory Budget Constraints

### com.example.alltiers.shipping.CarrierAdapter.estimateGrams(int,int,int)
- **Policy**: ZERO_ALLOCATION
- **Rule**: Strictly limit or prevent object allocations.

## Mathematical Purity

### com.example.alltiers.shipping.CarrierAdapter.cacheKey(java.lang.String,java.lang.String)
- **Rule**: Must remain a pure function. Forbid state modifications and side effects.
- **Reason**: Used inside the rate cache key; a side effect here would poison every cached rate

## Polymorphic Extension Pattern

### com.example.alltiers.shipping.CarrierAdapter
- **Pattern**: STRATEGY_PATTERN
- **Rule**: Open for extension, closed for modification. Use strategy or visitor subclasses instead of changing this file.
<!-- VIBETAGS-END -->
