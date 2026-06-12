<!-- VIBETAGS-START -->
# AI Guidelines for NewAnnotationsShowcase

### Rules for method executeSecureDatabaseWipe
- **Allowed Callers**: [com.example.service.PricingService, com.example.payment.PaymentProcessor]

### Rules for method calculateFastFibonacci
- **Policy**: ZERO_ALLOCATION
- **Rule**: Strictly limit or prevent object allocations.

### Rules for method calculateFastFibonacci
- **Rule**: Must remain a pure function. Forbid state modifications and side effects.

### Rules for parameter NewAnnotationsShowcase.executeDatabaseQuery(java.lang.String)#sqlRawInput
- **Target Filters**: SQL_INJECTION
- **Rule**: Run raw input strings through approved sanitizers.

### Rules for parameter NewAnnotationsShowcase.registerUserSession(java.lang.String,java.lang.String,java.lang.String)#passwordRaw
- **Policy**: HASH
- **Rule**: Never pass this raw variable to log appenders or stdout streams.

### Rules for parameter NewAnnotationsShowcase.registerUserSession(java.lang.String,java.lang.String,java.lang.String)#creditCardNumber
- **Policy**: MASK_CREDIT_CARD
- **Rule**: Never pass this raw variable to log appenders or stdout streams.

### Rules for method runComplexMatrixMath
- **Complexity Level**: HIGH
- **Rule**: Any logic modification requires updating a walkthrough/markdown file with structured architectural rationale.

### Rules for method deprecatedLegacyCalculatePrice
- **Status**: Strict Deprecation (No new references)
- **JIRA Ticket**: DEBT-742
- **Replacement**: com.example.service.PricingService

### Rules for method temporaryUpstreamBypass
- **Expiration**: 2028-12-31
- **Reason**: Hotfix workaround until upstream payment provider updates their API.
- **Rule**: Hotfix or stub that must be removed before expiration.
<!-- VIBETAGS-END -->
