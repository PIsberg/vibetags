<!-- VIBETAGS-START -->
# Amazon Q Rules for PaymentDetails

## Strict Type Safety
- **Rule**: Loose typing (e.g., Object, raw types, generic Map<String, Object>) is strictly prohibited. Enforce type safety.
- **Reason**: Currency math broke in INC-4412 when a double leaked into amount; keep money as BigDecimal and never widen these fields to Object/Map
<!-- VIBETAGS-END -->
