---
description: "AI rules for com.example.service.OrderService"
globs: ["**/OrderService.java"]
alwaysApply: false
---

<!-- VIBETAGS-START -->
# Rules for OrderService

### Rules for method validateOrder
- **Reason**: Order validation implements 47 business rules. Last changed in Q2 2024 after 3-month testing cycle. DO NOT MODIFY without running full test suite.

### Rules for method calculateTax
- **Reason**: Tax calculation uses Avalara API integration. Credentials and endpoint configuration managed by finance team.

### Rules for method processPayment
- **Reason**: Payment processing uses Stripe API v2024.10. Changes require PCI compliance review.

## Context & Focus
- **Focus**: Maintain transactional integrity. All database operations must use proper transaction management.
- **Avoid**: Raw SQL queries, direct database connections without connection pooling

### Rules for method calculateDiscount
- **Instruction**: Implement discount calculation supporting: percentage discounts, fixed amount discounts, buy-one-get-one-free, and tiered discounts based on cart value. Apply maximum one discount per order unless overridden by admin.

### Rules for method updateOrderStatus
- **Instruction**: Implement order status workflow: CREATED -> PAYMENT_PENDING -> PAYMENT_CONFIRMED -> PROCESSING -> SHIPPED -> DELIVERED. Support status history tracking with timestamps. Allow cancellation only before SHIPPED status.

### Rules for method searchOrders
- **Instruction**: Implement order search with filters: date range, status, customer ID, minimum/maximum amount. Support pagination (default 20 items per page). Return results sorted by creation date descending.

### Rules for method generateOrderConfirmation
- **Instruction**: Generate order confirmation email content including: order summary, itemized list, shipping address, estimated delivery date, and customer support contact information. Support HTML and plain text formats.

### Rules for method generateOrderConfirmation
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Output contains customer shipping address and contact details (PII)
<!-- VIBETAGS-END -->
