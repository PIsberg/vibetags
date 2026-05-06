# VibeTags Example Project

A practical demonstration of **VibeTags** — Java annotations that act as AI guardrails for code generation tools like Cursor, Claude, Gemini, Qwen, Codex CLI, and many more.

## 🎯 What This Example Demonstrates

This is a sample e-commerce application that shows all ten VibeTags annotations in action:

- **`@AILocked`** — Protects critical code from AI modifications
- **`@AIIgnore`** — Excludes elements from AI context entirely (treat as non-existent)
- **`@AIContext`** — Guides AI on how to work with specific classes (focus/avoid instructions)
- **`@AIDraft`** — Marks methods/classes that need AI implementation with detailed instructions
- **`@AIAudit`** — Tags critical infrastructure for continuous AI security auditing
- **`@AIPrivacy`** — Marks PII-handling fields and methods; AI must never log or expose their values
- **`@AICore`** — Marks well-tested core logic as highly sensitive (change with extreme caution)
- **`@AIPerformance`** — Sets strict time/space complexity constraints for hot-path code
- **`@AIContract`** — Freezes a public API signature while still inviting AI to refactor the body
- **`@AITestDriven`** — Requires AI to supply a matching test update alongside any code change

When compiled, the VibeTags annotation processor automatically generates AI configuration files for 27 platforms.

> **Note:** For an overview of the VibeTags project, installation instructions, and a quick start guide, see the [main README](../README.md).

## 📁 Project Structure

```
example/
├── pom.xml                                    # Maven build configuration
├── build.gradle                               # Gradle build configuration
├── reset-ai-files.sh                          # Clears generated files for a clean rebuild
├── README.md                                  # This file
└── src/main/java/com/example/
    ├── MainApplication.java                   # Demo application entry point
    ├── internal/
    │   └── GeneratedMetadata.java             # @AIIgnore example (auto-generated file)
    ├── payment/
    │   └── PaymentProcessor.java              # @AILocked + @AIPerformance example
    ├── security/
    │   └── SecurityConfig.java                # @AILocked + @AIContext + @AICore example
    ├── service/
    │   ├── NotificationService.java           # @AIDraft + @AIPrivacy example
    │   ├── OrderService.java                  # Mixed: @AILocked, @AIContext, @AIDraft,
    │   │                                      #        @AIPrivacy, @AITestDriven
    │   ├── InventoryService.java              # @AICore + @AIPerformance example
    │   └── PricingService.java                # @AIContract + @AIPerformance example
    ├── strategy/
    │   ├── PaymentStrategy.java               # @AIContext enforcing design patterns
    │   └── impl/
    │       └── CreditCardStrategy.java        # @AIDraft + @AIPrivacy (PCI-DSS card fields)
    ├── utils/
    │   └── StringParser.java                  # @AIContext optimization hints
    └── database/
        └── DatabaseConnector.java             # @AIAudit + @AIPrivacy example
```

## 🚀 Quick Start

### Prerequisites

- **Java 17 or higher** installed
- **Maven 3.6+** or **Gradle 7.0+** installed
- **VibeTags library** built and installed locally (see below)

### Step 1: Install VibeTags Library

Before using this example project, you need to build and install the VibeTags library:

```bash
# Annotations first (vibetags depends on this)
cd ../vibetags-annotations
mvn install                                # Maven
# OR
gradle clean build publishToMavenLocal    # Gradle

# Then the processor
cd ../vibetags
mvn clean install                          # Maven
# OR
gradle clean build publishToMavenLocal    # Gradle

# Then the BOM
cd ../vibetags-bom
mvn install
```

### Step 2: Create AI Config Signal Files

VibeTags uses a **file-existence opt-in** model — it only generates content for platforms whose config files already exist. Create empty placeholders for the platforms you use:

```bash
cd example

# Core platforms
touch CLAUDE.md .cursorrules AGENTS.md QWEN.md gemini_instructions.md
touch .aiexclude .cursorignore .claudeignore .copilotignore .qwenignore
mkdir -p .github && touch .github/copilot-instructions.md
touch llms.txt llms-full.txt CONVENTIONS.md .aiderignore

# Granular rules (per-class files)
mkdir -p .cursor/rules .windsurf/rules .continue/rules
mkdir -p .tabnine/guidelines .amazonq/rules .trae/rules .roo/rules
mkdir -p .ai/rules .pearai/rules

# Additional platforms
touch .windsurfrules .rules
mkdir -p .cody && touch .cody/config.json && touch .codyignore
touch .supermavenignore .mentatconfig.json sweep.yaml .plandex.yaml
touch .doubleignore .codeiumignore
mkdir -p .interpreter/profiles && touch .interpreter/profiles/vibetags.yaml
```

The `reset-ai-files.sh` script does the same thing and can be used to clear generated content for a clean rebuild:

```bash
bash reset-ai-files.sh
```

### Step 3: Build the Example Project

**Using Maven:**
```bash
cd example
mvn clean compile
```

**Using Gradle:**
```bash
cd example
gradle clean build
```

### Step 4: Check Generated AI Guardrail Files

After compilation, VibeTags populates all opted-in files with guardrail content derived from your annotations. For example:

| File | Platform |
|---|---|
| `.cursorrules` | Cursor |
| `CLAUDE.md` | Claude |
| `AGENTS.md` | Codex CLI |
| `QWEN.md` | Qwen |
| `gemini_instructions.md` | Gemini |
| `.github/copilot-instructions.md` | GitHub Copilot |
| `.windsurfrules` | Windsurf |
| `.rules` | Zed Editor |
| `llms.txt` / `llms-full.txt` | All LLM agents (llms.txt standard) |
| `.cody/config.json` | Sourcegraph Cody |
| `.mentatconfig.json` | Mentat |
| `sweep.yaml` | Sweep (GitHub App) |
| `.plandex.yaml` | Plandex |
| `.interpreter/profiles/vibetags.yaml` | Open Interpreter |
| `.cursor/rules/*.mdc` | Cursor (granular, per-class) |
| `.pearai/rules/*.md` | PearAI (granular, per-class) |
| *(and more)* | |

See the [main README](../README.md) for the complete list of 27 supported platforms.

---

## 📖 Annotation Reference

### 1. `@AILocked` — Protect Critical Code

Use when code must **not** be modified by AI under any circumstances.

```java
@AILocked(reason = "Tied to legacy database schema v2.3. Changes will break production payment processing.")
public class PaymentProcessor {
    // AI cannot suggest modifications to this class
}
```

**Method-level locking:**
```java
@AILocked(reason = "Order validation implements 47 business rules. Last changed in Q2 2024 after 3-month testing cycle.")
public boolean validateOrder(Map<String, Object> orderData) {
    // LOCKED — AI must not touch this method
}
```

**Real-world use cases:**
- Legacy system integrations tied to an external schema
- Compliance-critical code (PCI-DSS, HIPAA, SOX)
- Complex algorithms that took months to stabilize
- Third-party API contracts where signature changes break downstream callers

**Difference from `@AIContract`:** `@AILocked` prohibits *all* changes (signature AND body). `@AIContract` prohibits only signature changes — the internal body is fair game. Use `@AILocked` when the algorithm itself is sensitive; use `@AIContract` when only the API surface is frozen.

---

### 2. `@AIIgnore` — Exclude from AI Context

Use when an element should be **completely invisible** to AI — not just protected from edits, but excluded from context entirely. The AI is instructed to treat the element as if it does not exist.

```java
@AIIgnore(reason = "Auto-generated at build time. Manual edits are overwritten on every build.")
public class GeneratedMetadata {
    public static final String BUILD_VERSION = "1.0.0-SNAPSHOT";
    // AI will never reference, suggest changes to, or even acknowledge this class
}
```

**Real-world use cases:**
- Auto-generated source files (JAXB, Protobuf, Thrift, ANTLR output)
- Build metadata classes overwritten on every compile
- Vendored / copied third-party code that must not be touched
- Internal scaffolding not meant for AI-assisted refactoring

**Difference from `@AILocked`:** `@AILocked` says "you can see this but cannot modify it". `@AIIgnore` says "this does not exist — never mention it, never reference it, never include it in suggestions."

**Generated ignore files:** Elements annotated with `@AIIgnore` are added as glob patterns to `.cursorignore`, `.claudeignore`, `.copilotignore`, `.qwenignore`, `.codyignore`, `.supermavenignore`, `.doubleignore`, `.codeiumignore`, `.aiderignore`, and `.aiexclude`.

**Smart Validation:** If you apply `@AIPrivacy` to an element already marked with `@AIIgnore`, the compiler warns you — `@AIIgnore` already hides the element entirely, making `@AIPrivacy` redundant.

---

### 3. `@AIContext` — Guide AI Behavior

Provide specific instructions on *how* AI should work with a class or method — what to focus on and what to avoid:

```java
@AIContext(
    focus = "Optimize for memory usage over CPU speed. Minimize object allocations.",
    avoids = "java.util.regex, String.split(), StringBuilder in loops"
)
public class StringParser {
    // AI will follow these guidelines when helping with this class
}
```

**Enforcing design patterns:**
```java
@AIContext(
    focus = "Follow the Strategy pattern strictly. Each payment method is a separate strategy class.",
    avoids = "Monolithic if-else chains, hard-coded payment logic in a single class"
)
public interface PaymentStrategy {
    double executePayment(double amount);
}
```

**Real-world use cases:**
- Performance-critical code (memory vs. CPU tradeoffs)
- Framework-specific code (must follow Spring/Quarkus/etc. conventions)
- Security-sensitive code (avoid certain libraries or patterns)
- Team conventions that the AI should respect

---

### 4. `@AIDraft` — Request AI Implementation

Mark methods or classes that need AI help, with detailed implementation instructions. These appear as "IMPLEMENTATION TASKS" in every generated AI config file:

```java
@AIDraft(instructions = "Implement email sending via JavaMail API. Include HTML template support and attachment handling. Add retry logic: max 3 retries with exponential backoff (1s, 2s, 4s).")
public boolean sendEmail(String to, String subject, String body) {
    // AI will implement this
    return false;
}
```

**Class-level draft:**
```java
@AIDraft(instructions = "Implement discount calculation supporting: percentage discounts, fixed amount discounts, buy-one-get-one-free, and tiered discounts based on cart value. Apply maximum one discount per order unless overridden by admin.")
public double calculateDiscount(String orderId, String discountCode) {
    return 0.0;
}
```

**Real-world use cases:**
- Skeleton classes needing business logic
- API integrations with known libraries
- Boilerplate CRUD operations
- Complex workflows where the spec is clear but the implementation is tedious

**Compile-time validation:** Combining `@AIDraft` + `@AILocked` on the same element triggers a warning — contradictory intent (locked but needs drafting).

---

### 5. `@AIAudit` — Continuous Security Auditing

Tag critical infrastructure so AI assistants must perform a security review before outputting any code changes:

```java
@AIAudit(checkFor = {"SQL Injection", "Thread Safety issues"})
public class DatabaseConnector {
    // Every time AI modifies this class, it must audit for the listed vulnerabilities
}
```

**Common vulnerability checks:**
```java
@AIAudit(checkFor = {
    "SQL Injection",
    "Thread Safety issues",
    "XSS (Cross-Site Scripting)",
    "CSRF (Cross-Site Request Forgery)",
    "Command Injection",
    "Path Traversal",
    "Insecure Deserialization",
    "Broken Access Control"
})
public class AuthController { }
```

**Real-world use cases:**
- Database connectors and ORM configurations
- Authentication and authorization modules
- Payment processing integrations
- API gateways and proxies
- Cryptographic operations
- Session management

**How AI platforms handle `@AIAudit`:**
- **Cursor / Codex / Qwen / Windsurf** — Mandatory `## 🛡️ SECURITY AUDITS` section; AI must explicitly state it has audited for each listed vulnerability before presenting code.
- **Claude** — XML `<audit_requirements>` block with a strict `<rule>` enforcing silent pre-output audit.
- **Gemini** — Continuous audit requirements phrased as "Senior Staff Engineer" instructions.
- **GitHub Copilot** — `## Security Audit Requirements` section in `.github/copilot-instructions.md`.
- **Mentat / Sweep / Plandex / Open Interpreter** — Audit checks embedded in their respective config formats.

**Compile-time validation:** Using `@AIAudit` with an empty `checkFor = {}` triggers a warning — no-op audit.

---

### 6. `@AIPrivacy` — Protect PII Fields and Methods

Mark fields or methods that handle Personally Identifiable Information. AI tools will never include the runtime values of these elements in logs, code suggestions, test fixtures, mock data, or external API calls:

```java
public class DatabaseConnector {
    @AIPrivacy(reason = "Database credential — never log or include in error messages")
    private final String username;

    @AIPrivacy(reason = "Database credential — never log or include in error messages")
    private final String password;
}
```

```java
public class NotificationService {
    @AIPrivacy(reason = "Email address is PII under GDPR — never log the recipient address")
    public boolean sendEmail(String to, String subject, String body) { ... }

    @AIPrivacy(reason = "Phone number is PII — never log the destination number")
    public boolean sendSMS(String phoneNumber, String message) { ... }
}
```

**Real-world use cases:**
- Database credentials and connection strings
- Email addresses, phone numbers, full names (GDPR Article 4)
- Credit card numbers, CVV, expiry dates (PCI-DSS)
- Government IDs, health records (HIPAA)
- Authentication tokens, session keys

**How Claude handles `@AIPrivacy`:**
```xml
<pii_guardrails>
  <element path="com.example.database.DatabaseConnector.password">
    <reason>Database credential — never log or include in error messages</reason>
  </element>
</pii_guardrails>
<rule>
  Never include runtime values of elements listed in <pii_guardrails> in logs, console output,
  external API calls, test fixtures, mock data, or code suggestions.
</rule>
```

---

### 7. `@AICore` — Mark Sensitive Core Logic

Inform AI that a component is well-tested, battle-hardened core functionality where changes could have a high impact:

```java
@AICore(
    sensitivity = "Critical",
    note = "Reservation logic handles concurrent requests via optimistic locking. Took 18 months to stabilize under high load — do not refactor without running the full concurrency test suite."
)
public void reserveStock(String productId, int quantity, String reservationId) {
    // AI will treat this with extreme caution
}
```

```java
@AICore(
    sensitivity = "High",
    note = "Must be called as the exact inverse of reserveStock. Pair changes to both methods together."
)
public void releaseReservation(String reservationId) { }
```

**`sensitivity` values (freeform string):** `"Critical"`, `"High"`, `"Medium"` — or any label meaningful to your team.

**Real-world use cases:**
- Billing and financial calculation engines
- Concurrency primitives that took months to tune
- Core state machines or workflow engines
- Security-critical validation paths

---

### 8. `@AIPerformance` — Enforce Performance Constraints

Set strict time or space complexity requirements for performance-critical code. AI must reason about complexity before proposing any changes:

```java
@AIPerformance(constraint = "O(1) lookup required. Must complete in <2ms p99. No database calls permitted — reads from in-memory cache only.")
public int getAvailableStock(String productId) {
    return cache.get(productId);
}
```

```java
@AIPerformance(constraint = "Must process 10,000 SKU updates/second. O(n) acceptable; O(n log n) only if unavoidable; O(n²) is forbidden.")
public void bulkRestock(List<Map<String, Object>> updates) { }
```

**Real-world use cases:**
- Search algorithms and index lookups
- Cache-read hot paths
- Bulk processing pipelines
- Request-handling loops (called on every HTTP request)

---

### 9. `@AIContract` — Freeze a Public API Signature

Use when a method's **public surface** is pinned by an external contract (OpenAPI/AsyncAPI, message schema, generated client, downstream service binding) but the **internal implementation** is free to change.

AI is explicitly invited to refactor the body — it just must not alter:
- the method name
- parameter types or parameter order
- the return type
- the set of checked exceptions

```java
@AIContract(reason = "Signature locked by OpenAPI v2 contract. checkout-service and mobile-app bind to this exact signature. A type change is a breaking API change.")
@AIPerformance(constraint = "Must complete in <5ms p99. Called on every cart update.")
public double calculatePrice(String productId, int quantity, String customerId) {
    // Internal pricing logic is freely modifiable — switch from rule engine to ML model,
    // add caching, change data sources. Just don't rename the method or change parameter types.
}
```

**Real-world use cases:**
- Methods exposed via an OpenAPI / AsyncAPI specification
- Methods consumed by other services through generated clients
- Methods serialized to JSON / Avro / Protobuf where field shapes are part of the wire format
- Library / SDK public APIs with stable-version commitments

**Compile-time validation:**
- `@AIContract` + `@AIDraft` → warning: contradictory (signature frozen, but `@AIDraft` implies the element still needs implementing)
- `@AIContract` + `@AILocked` → warning: overlapping intent (`@AILocked` already prohibits all changes; `@AIContract` is redundant)

---

### 10. `@AITestDriven` — Require Matching Test Updates

Enforce that AI must supply both the implementation change **and** the corresponding test code in a single response. Changes without tests are treated as incomplete proposals:

```java
@AIDraft(instructions = "Implement discount calculation: percentage discounts, fixed amount, BOGO, tiered.")
@AITestDriven(
    coverageGoal = 100,
    framework = {AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.ASSERTJ},
    mockPolicy = "Use fixed prices — no external pricing calls in unit tests"
)
public double calculateDiscount(String orderId, String discountCode) {
    return 0.0;
}
```

```java
@AITestDriven(
    coverageGoal = 95,
    framework = {AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.MOCKITO},
    testLocation = "src/test/java/com/example/service/OrderServiceTest.java",
    mockPolicy = "Mock OrderRepository and EventPublisher; use real state machine logic"
)
public String updateOrderStatus(String orderId, String newStatus) {
    return "CREATED";
}
```

**Attributes:**

| Attribute | Type | Description |
|---|---|---|
| `coverageGoal` | `int` | Target line/branch coverage % for this element |
| `framework` | `Framework[]` | `JUNIT_5`, `JUNIT_4`, `TESTNG`, `MOCKITO`, `ASSERTJ`, `SPOCK`, `KOTEST` |
| `testLocation` | `String` | Relative path to the test class (optional hint for AI) |
| `mockPolicy` | `String` | What to mock/stub and what to leave real (optional) |

**Real-world use cases:**
- Draft methods that need both implementation and tests written together
- Business logic with complex branching (high coverage goals)
- State machines where every transition needs a test case
- Financial calculations where test coverage is a compliance requirement

**Compile-time validation:** `@AITestDriven` generates a `<test_driven_requirements>` block in Claude, a `🧪 TEST-DRIVEN REQUIREMENTS` section in Cursor/Codex/Windsurf/Qwen, and corresponding entries in all other platform configs.

---

## 🔀 Mixed Usage — Fine-Grained Control

You can combine multiple annotations on the same class or method for precise, layered control:

```java
@AIContext(
    focus = "Maintain transactional integrity. All database operations must use proper transaction management.",
    avoids = "Raw SQL queries, direct database connections without connection pooling"
)
public class OrderService {

    @AILocked(reason = "47 business rules, 3-month testing cycle — DO NOT MODIFY")
    public boolean validateOrder(Map<String, Object> orderData) {
        return orderData != null && orderData.containsKey("items");
    }

    @AIDraft(instructions = "Implement discount calculation supporting percentage, fixed, and tiered discounts.")
    @AITestDriven(
        coverageGoal = 100,
        framework = {AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.ASSERTJ},
        mockPolicy = "Use fixed prices — no external pricing calls in unit tests"
    )
    public double calculateDiscount(String orderId, String discountCode) {
        return 0.0;
    }

    @AIPrivacy(reason = "Output contains customer shipping address and contact details (PII)")
    @AIDraft(instructions = "Generate order confirmation email with order summary, itemized list, shipping address.")
    public String generateOrderConfirmation(String orderId) {
        return "";
    }
}
```

---

## 🛠️ Generated AI Configuration Files

When you run `mvn clean compile`, the processor scans your annotations and generates platform-specific content for every opted-in file. Below are representative examples.

### `.cursorrules` (Cursor)

```markdown
# AUTO-GENERATED AI RULES
# Generated by VibeTags | https://github.com/PIsberg/vibetags

## LOCKED FILES (DO NOT EDIT)
* `com.example.payment.PaymentProcessor` - Reason: Tied to legacy database schema v2.3.

## CONTEXTUAL RULES
* `com.example.utils.StringParser`
  * Focus: Optimize for memory usage over CPU speed.
  * Avoid: java.util.regex, String.split(), StringBuilder in loops

## 🛡️ MANDATORY SECURITY AUDITS
* `com.example.database.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety issues

## 🔒 PII / PRIVACY GUARDRAILS
* `com.example.database.DatabaseConnector.password` - Database credential — never log

## 🧪 TEST-DRIVEN REQUIREMENTS
* `com.example.service.OrderService.calculateDiscount(...)` - Coverage goal: 100%. Framework: JUNIT_5, ASSERTJ.
```

### `CLAUDE.md` (Claude)

```xml
<project_guardrails>
  <locked_files>
    <file path="com.example.payment.PaymentProcessor">
      <reason>Tied to legacy database schema v2.3.</reason>
    </file>
  </locked_files>
  <audit_requirements>
    <file path="com.example.database.DatabaseConnector">
      <vulnerability_check>SQL Injection</vulnerability_check>
      <vulnerability_check>Thread Safety issues</vulnerability_check>
    </file>
  </audit_requirements>
  <ignored_elements>
    <file path="com.example.internal.GeneratedMetadata"/>
  </ignored_elements>
  <pii_guardrails>
    <element path="com.example.database.DatabaseConnector.password">
      <reason>Database credential — never log or include in error messages</reason>
    </element>
  </pii_guardrails>
  <test_driven_requirements>
    <element path="com.example.service.OrderService.calculateDiscount(...)">
      <coverage_goal>100</coverage_goal>
      <frameworks>JUNIT_5, ASSERTJ</frameworks>
      <mock_policy>Use fixed prices — no external pricing calls in unit tests</mock_policy>
    </element>
  </test_driven_requirements>
</project_guardrails>
```

### `sweep.yaml` (Sweep GitHub App)

```yaml
# AUTO-GENERATED BY VIBETAGS
# Sweep AI code review rules

rules:
  - "Do not modify com.example.payment.PaymentProcessor: Tied to legacy database schema v2.3"
  - "Security audit required for com.example.database.DatabaseConnector: SQL Injection, Thread Safety issues"
  - "PII protection required for com.example.database.DatabaseConnector.password: never log or expose runtime values"
```

### `.mentatconfig.json` (Mentat)

```json
{
  "_generated_by": "VibeTags",
  "rules": {
    "locked_files": [
      {"path": "com.example.payment.PaymentProcessor", "reason": "Tied to legacy database schema v2.3"}
    ],
    "audit": [
      {"path": "com.example.database.DatabaseConnector", "checks": ["SQL Injection", "Thread Safety issues"]}
    ],
    "ignored": [
      {"path": "com.example.internal.GeneratedMetadata"}
    ]
  }
}
```

---

## ⚠️ Smart Validation Warnings

VibeTags warns you at compile time when annotation combinations are contradictory or redundant:

| Combination | Warning |
|---|---|
| `@AIDraft` + `@AILocked` | Contradictory — locked but flagged for drafting |
| `@AIAudit(checkFor = {})` | No-op — nothing to audit |
| `@AIPrivacy` + `@AIIgnore` | Redundant — `@AIIgnore` already hides the element |
| `@AIContract` + `@AIDraft` | Contradictory — signature frozen but marked for implementation |
| `@AIContract` + `@AILocked` | Overlapping — `@AILocked` already prohibits all changes |
| `@AIIgnore` present, no ignore files exist | Orphaned — no `.cursorignore`/`.claudeignore`/etc. to write to |
| `@AILocked` present, no `.aiexclude` | Gemini lock not active |

---

## 📊 When to Use Each Annotation

| Scenario | Annotation | Example |
|---|---|---|
| Code AI must never modify | `@AILocked` | Legacy schema integrations, compliance code |
| Code AI should not even see | `@AIIgnore` | Auto-generated files, vendored code |
| How AI should approach a class | `@AIContext` | Algorithm constraints, framework conventions |
| Code AI should implement | `@AIDraft` | Skeleton methods, CRUD boilerplate |
| Security-critical infrastructure | `@AIAudit` | Auth, DB connectors, payment gateways |
| PII fields and methods | `@AIPrivacy` | Credentials, email, phone, card data |
| Battle-tested core logic | `@AICore` | Billing engines, concurrency primitives |
| Hot-path performance code | `@AIPerformance` | Cache reads, request handlers |
| Public API signature frozen by contract | `@AIContract` | OpenAPI methods, message-schema bindings |
| Changes must include test updates | `@AITestDriven` | Business logic, financial calculations |

---

## 🔧 Configuration Reference

### Processor Options

Pass via `<compilerArg>-A...</compilerArg>` in Maven or `compilerArgs` in Gradle:

| Option | Default | Description |
|---|---|---|
| `vibetags.project` | `"This Project"` | Sets the `# H1` project name in `llms.txt` and `llms-full.txt` |
| `vibetags.root` | JVM working directory | Override the output directory for all generated files |
| `vibetags.log.path` | `vibetags.log` in root | Custom log file path (relative to root, or absolute) |
| `vibetags.log.level` | `INFO` | Log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF` |

#### Maven

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>-Avibetags.project=My Project</arg>
            <arg>-Avibetags.log.path=logs/vibetags.log</arg>
            <arg>-Avibetags.log.level=DEBUG</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

#### Gradle

```groovy
tasks.withType(JavaCompile) {
    options.compilerArgs += [
        '-Avibetags.project=My Project',
        '-Avibetags.log.path=logs/vibetags.log',
        '-Avibetags.log.level=DEBUG'
    ]
}
```

---

## 🎓 Best Practices

### Be specific in reasons

```java
// Bad
@AILocked(reason = "Important")

// Good
@AILocked(reason = "Implements PCI-DSS compliance requirements. Changes require security review ticket SEC-XXXX and approval from the compliance team.")
```

### Provide actionable context

```java
// Bad
@AIContext(focus = "Make it fast")

// Good
@AIContext(
    focus = "Optimize for O(1) lookup time. Use HashMap instead of ArrayList for the product index.",
    avoids = "Linear searches, nested loops, Stream.filter() on large collections"
)
```

### Give detailed implementation instructions

```java
// Bad
@AIDraft(instructions = "Implement this")

// Good
@AIDraft(instructions = "Implement using Apache HttpClient 5.x. Include: " +
    "1. Connection pooling (max 10 connections). " +
    "2. Timeout configuration (connect: 5s, read: 10s). " +
    "3. Retry logic (3 retries with exponential backoff). " +
    "4. Proper resource cleanup in finally block.")
```

### Layer annotations for maximum control

```java
// Three annotations working together: the interface is stable, the
// implementation is performance-critical, and AI must not change the signature.
@AIContract(reason = "Signature locked by OpenAPI v2 contract.")
@AIPerformance(constraint = "Must complete in <5ms p99. Called on every cart update.")
@AIContext(focus = "Accuracy over speed — use BigDecimal internally for monetary values.")
public double calculatePrice(String productId, int quantity, String customerId) { }
```

---

## 🐛 Troubleshooting

### Annotation processor not running

**Symptom:** No AI guardrail files generated after `mvn compile`

**Solution:**
1. Ensure VibeTags is installed: `mvn -f ../vibetags/pom.xml clean install`
2. Check the processor is on the annotation processor path (not just compile classpath)
3. Run with debug logging: `mvn compile -X` and look for `AIGuardrailProcessor`
4. Check that at least one signal file exists — VibeTags only writes to files that already exist

### No content in generated files

**Symptom:** Files exist but are empty after compilation

**Solution:**
- Verify signal files existed *before* the compile (the processor won't create files, only populate them)
- Run `bash reset-ai-files.sh` to clear stale content, then `mvn clean compile`
- Check `vibetags.log` in the project root for processor output

### Annotations not recognized

**Symptom:** Compilation errors like `cannot find symbol @AILocked`

**Solution:**
1. Verify import statements: `import se.deversity.vibetags.annotations.AILocked;`
2. Ensure `vibetags-annotations` is on the *compile* classpath (not only the AP path)
3. Run `mvn dependency:tree` to verify dependency resolution

### Files generated in wrong directory

**Symptom:** Guardrail files appear in an unexpected location

**Solution:**
The processor uses `Paths.get("")` which resolves to the JVM working directory. Run the build from the project root, or set `-Avibetags.root=<absolute-path>` to override.

---

## 📚 Resources

- **Main README:** [`../README.md`](../README.md) — installation, all 27 platforms, architecture overview
- **Architecture:** [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) — deep-dive into processor internals
- **Issue Tracker:** [GitHub Issues](https://github.com/PIsberg/vibetags/issues)

## 📝 License

This example project is licensed under the [MIT License](../LICENSE).
