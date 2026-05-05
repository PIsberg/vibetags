<!-- VIBETAGS-START -->
# AI Guidelines for PricingService

## Context & Focus
- **Focus**: Optimize pricing calculations for accuracy and throughput. Internal algorithms may use any efficient approach.
- **Avoid**: Floating-point arithmetic for monetary values — use BigDecimal internally, but note that the contract-frozen signatures use double for backwards compatibility

### Rules for method calculatePrice
- **Rule**: Optimal complexity required. O(n^2) is forbidden on hot paths.
- **Constraint**: Must complete in <5ms p99. Called on every cart update.

### Rules for method calculatePrice
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Signature locked by OpenAPI v2 contract. checkout-service and mobile-app bind to this exact signature. A type change is a breaking API change.

### Rules for method applyPromoCode
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: Promotions-service depends on this exact method signature for its async price-adjustment events. Changing parameter types would break the event deserialization.

### Rules for method getBulkPricing
- **Constraint**: You may change internal logic, but MUST NOT modify the method name, parameters, return type, or checked exceptions.
- **Reason**: B2B portal contract v1.2 — the List<Map<String,Object>> structure is serialized directly to JSON. Changing the return type breaks portal parsing.
<!-- VIBETAGS-END -->
