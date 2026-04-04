<project_guardrails>
  <locked_files>
    <file path="com.example.payment.PaymentProcessor">
      <reason>Tied to legacy database schema v2.3. Changes will break production payment processing. Contact the payments team before modifying.</reason>
    </file>
    <file path="com.example.security.SecurityConfig">
      <reason>CRITICAL: Security configuration managed by DevOps team. Any changes require security review and approval ticket SEC-XXXX</reason>
    </file>
    <file path="getEncryptionAlgorithm()">
      <reason>Encryption algorithm tied to compliance requirements (PCI-DSS)</reason>
    </file>
    <file path="getKeyRotationHours()">
      <reason>Key rotation period mandated by company policy</reason>
    </file>
    <file path="getMaxLoginAttempts()">
      <reason>Max login attempts set by security team to prevent brute force</reason>
    </file>
    <file path="validateToken(java.lang.String)">
      <reason>Token validation must match auth server exactly. Changes will break all client authentication</reason>
    </file>
    <file path="validateOrder(java.util.Map<java.lang.String,java.lang.Object>)">
      <reason>Order validation implements 47 business rules. Last changed in Q2 2024 after 3-month testing cycle. DO NOT MODIFY without running full test suite.</reason>
    </file>
    <file path="calculateTax(java.lang.String,double)">
      <reason>Tax calculation uses Avalara API integration. Credentials and endpoint configuration managed by finance team.</reason>
    </file>
    <file path="processPayment(java.lang.String,double)">
      <reason>Payment processing uses Stripe API v2024.10. Changes require PCI compliance review.</reason>
    </file>
  </locked_files>
  <contextual_instructions>
    <file path="com.example.security.SecurityConfig">
      <focus>This class is READ-ONLY for AI assistants. Do not suggest modifications.</focus>
      <avoids>Any changes to encryption algorithms, key sizes, or validation logic</avoids>
    </file>
    <file path="com.example.service.NotificationService">
      <focus>Implement notification delivery with retry logic and error handling</focus>
      <avoids>Hard-coded credentials, synchronous blocking calls</avoids>
    </file>
    <file path="com.example.service.OrderService">
      <focus>Maintain transactional integrity. All database operations must use proper transaction management.</focus>
      <avoids>Raw SQL queries, direct database connections without connection pooling</avoids>
    </file>
    <file path="com.example.strategy.PaymentStrategy">
      <focus>Follow the Strategy pattern strictly. Each payment method should be a separate strategy class implementing this interface.</focus>
      <avoids>Monolithic if-else chains, hard-coded payment logic, single class handling all payment types</avoids>
    </file>
    <file path="com.example.utils.StringParser">
      <focus>Optimize for memory usage over CPU speed. Minimize object allocations and avoid creating intermediate string objects.</focus>
      <avoids>java.util.regex, String.split(), StringBuilder in loops</avoids>
    </file>
  </contextual_instructions>

  <audit_requirements>
    <file path="com.example.database.DatabaseConnector">
      <vulnerability_check>SQL Injection</vulnerability_check>
      <vulnerability_check>Thread Safety issues</vulnerability_check>
    </file>
  </audit_requirements>

<rule>
  If you are asked to modify any file listed in <audit_requirements>, you must first silently analyze your proposed code for the listed <vulnerability_check> items. If your code introduces these vulnerabilities, you must rewrite it before displaying it to the user.
</rule>
  <ignored_elements>
    <file path="com.example.internal.GeneratedMetadata"/>
  </ignored_elements>

<rule>Never reference or suggest changes to any element listed in <ignored_elements>. Treat these as if they do not exist.</rule>
</project_guardrails>

<rule>Never propose edits to files listed in <locked_files>.</rule>

