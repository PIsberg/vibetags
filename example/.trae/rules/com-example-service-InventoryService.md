---
alwaysApply: false
globs: ["**/InventoryService.java"]
description: "AI rules for com.example.service.InventoryService"
---

<!-- VIBETAGS-START -->
# Rules for InventoryService

## Context & Focus
- **Focus**: Maintain inventory consistency across concurrent requests. All stock updates must be atomic.
- **Avoid**: Non-atomic read-modify-write sequences, unsynchronized shared state

### Rules for field customerId
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Customer identifiers linked to purchase history — PII under GDPR

### Rules for method reserveStock
- **Sensitivity**: Critical
- **Note**: Reservation logic handles concurrent requests via optimistic locking. Took 18 months to get right under high load — do not refactor without running the full concurrency test suite.

### Rules for method releaseReservation
- **Sensitivity**: High
- **Note**: Must be called as the exact inverse of reserveStock. Pair changes to both methods together.

### Rules for method getAvailableStock
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: O(1) lookup required. Must complete in <2ms p99. No database calls permitted; reads from in-memory cache only.

### Rules for method bulkRestock
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: Must process 10 000 SKU updates/second. O(n) acceptable; O(n log n) only if unavoidable; O(n²) is forbidden.
<!-- VIBETAGS-END -->
