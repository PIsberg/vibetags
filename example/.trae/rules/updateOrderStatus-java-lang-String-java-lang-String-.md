---
alwaysApply: false
globs: ["**/updateOrderStatus.java"]
description: "AI rules for updateOrderStatus(java.lang.String,java.lang.String)"
---

<!-- VIBETAGS-START -->
# Rules for updateOrderStatus

## Implementation Tasks
- **Instruction**: Implement order status workflow: CREATED -> PAYMENT_PENDING -> PAYMENT_CONFIRMED -> PROCESSING -> SHIPPED -> DELIVERED. Support status history tracking with timestamps. Allow cancellation only before SHIPPED status.
<!-- VIBETAGS-END -->
