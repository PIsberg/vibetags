# Project Knowledge

## Locked Files
- **com.example.payment.PaymentProcessor**: Tied to legacy database schema v2.3. Changes will break production payment processing. Contact the payments team before modifying.
- **com.example.security.SecurityConfig**: CRITICAL: Security configuration managed by DevOps team. Any changes require security review and approval ticket SEC-XXXX
- **getEncryptionAlgorithm()**: Encryption algorithm tied to compliance requirements (PCI-DSS)
- **getKeyRotationHours()**: Key rotation period mandated by company policy
- **getMaxLoginAttempts()**: Max login attempts set by security team to prevent brute force
- **validateToken(java.lang.String)**: Token validation must match auth server exactly. Changes will break all client authentication
- **validateOrder(java.util.Map<java.lang.String,java.lang.Object>)**: Order validation implements 47 business rules. Last changed in Q2 2024 after 3-month testing cycle. DO NOT MODIFY without running full test suite.
- **calculateTax(java.lang.String,double)**: Tax calculation uses Avalara API integration. Credentials and endpoint configuration managed by finance team.
- **processPayment(java.lang.String,double)**: Payment processing uses Stripe API v2024.10. Changes require PCI compliance review.

## Contextual Rules
- **com.example.service.NotificationService**: Focus on Implement notification delivery with retry logic and error handling. Avoid Hard-coded credentials, synchronous blocking calls.
- **com.example.security.SecurityConfig**: Focus on This class is READ-ONLY for AI assistants. Do not suggest modifications.. Avoid Any changes to encryption algorithms, key sizes, or validation logic.
- **com.example.utils.StringParser**: Focus on Optimize for memory usage over CPU speed. Minimize object allocations and avoid creating intermediate string objects.. Avoid java.util.regex, String.split(), StringBuilder in loops.
- **com.example.strategy.PaymentStrategy**: Focus on Follow the Strategy pattern strictly. Each payment method should be a separate strategy class implementing this interface.. Avoid Monolithic if-else chains, hard-coded payment logic, single class handling all payment types.
- **com.example.service.OrderService**: Focus on Maintain transactional integrity. All database operations must use proper transaction management.. Avoid Raw SQL queries, direct database connections without connection pooling.

### 🔎 SECURITY GUARDRAILS (ENFORCE STRICTLY)
Before generating any final code snippets for the files below, you must run a simulated security audit on your own output. 

Target File: `com.example.database.DatabaseConnector`
Audit Checklist:
1. Is this code vulnerable to SQL Injection?
2. Is this code vulnerable to Thread Safety issues?

If the answer to either is YES, discard your draft and rewrite the code securely.


