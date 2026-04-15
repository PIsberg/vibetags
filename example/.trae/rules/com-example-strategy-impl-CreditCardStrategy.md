---
alwaysApply: false
globs: ["**/CreditCardStrategy.java"]
description: "AI rules for com.example.strategy.impl.CreditCardStrategy"
---

<!-- VIBETAGS-START -->
# Rules for CreditCardStrategy

### Rules for method executePayment
- **Instruction**: Implement credit card payment processing via Stripe or similar payment gateway. Include: card tokenization, 3D Secure authentication, and proper error handling for declined cards. Return transaction ID on success.

### Rules for method validatePaymentMethod
- **Instruction**: Implement Luhn algorithm validation for card number, expiry date validation (must be future date), and CVV format check (3-4 digits). Return true only if all validations pass.

### Rules for field cardNumber
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: PCI-DSS cardholder data - never log or expose in suggestions

### Rules for field expiryDate
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: PCI-DSS cardholder data - never log or expose in suggestions

### Rules for field cvv
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: PCI-DSS security code - never log or expose in suggestions
<!-- VIBETAGS-END -->
