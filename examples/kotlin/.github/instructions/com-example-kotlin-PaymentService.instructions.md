---
applyTo: "**/PaymentService.kt"
---

<!-- VIBETAGS-START -->
# Copilot Instructions for PaymentService

### Rules for method chargeKey
- **Reason**: Charge idempotency key derivation is contract-tested against the gateway. Changing it re-charges in-flight payments.

## Context & Focus
- **Focus**: Coordinates payment authorization and capture against the gateway
- **Avoid**: Retry logic — the gateway client already retries; a second layer double-charges
<!-- VIBETAGS-END -->
