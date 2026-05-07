---
alwaysApply: false
globs: ["**/OldPaymentApi.java"]
description: "AI rules for com.example.legacy.OldPaymentApi"
---

<!-- VIBETAGS-START -->
# Rules for OldPaymentApi

## Deprecated — Migrate Callers
- **Replaced by**: com.example.payment.PaymentProcessor
- **Migration**: Switch callers to PaymentProcessor.charge(). The new API uses Money instead of double.
- **Deadline**: v2.0 (2026-Q4)
<!-- VIBETAGS-END -->
