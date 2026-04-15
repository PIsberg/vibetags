<!-- VIBETAGS-START -->
# Rules for generateOrderConfirmation

## Implementation Tasks
- **Instruction**: Generate order confirmation email content including: order summary, itemized list, shipping address, estimated delivery date, and customer support contact information. Support HTML and plain text formats.

## PII / Privacy Guardrails
- **Rule**: Never log or expose runtime values of this element.
- **Reason**: Output contains customer shipping address and contact details (PII)
<!-- VIBETAGS-END -->
