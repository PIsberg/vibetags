<!-- VIBETAGS-START -->
# Rules for PaymentStrategy

## Context & Focus
- **Focus**: Follow the Strategy pattern strictly. Each payment method should be a separate strategy class implementing this interface.
- **Avoid**: Monolithic if-else chains, hard-coded payment logic, single class handling all payment types

### Rules for method executePayment
- **Instruction**: Implement payment execution specific to the payment method (credit card, PayPal, cryptocurrency, etc.). Return transaction ID on success.

### Rules for method validatePaymentMethod
- **Instruction**: Validate payment method specific data (card numbers, email addresses, wallet addresses, etc.). Return true if valid, false otherwise.
<!-- VIBETAGS-END -->
