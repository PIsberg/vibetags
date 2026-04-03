# VibeTags Example Project

A practical demonstration of **VibeTags** - Java annotations that act as AI guardrails for code generation tools like Cursor, Claude, Gemini, and ChatGPT.

## 🎯 What This Example Demonstrates

This is a sample e-commerce application that shows how VibeTags annotations control AI behavior:

- **`@AILocked`** - Protects critical code from AI modifications
- **`@AIContext`** - Guides AI on how to work with specific classes (focus/avoid instructions)
- **`@AIDraft`** - Marks methods that need AI implementation with detailed instructions

When compiled, the VibeTags annotation processor automatically generates AI configuration files for multiple platforms.

> **Note:** For an overview of the VibeTags project, installation instructions, and quick start guide, see the [main README](../README.md).

## 📁 Project Structure

```
example/
├── pom.xml                                    # Maven build configuration
├── README.md                                  # This file
└── src/main/java/com/example/
    ├── MainApplication.java                   # Demo application
    ├── payment/
    │   └── PaymentProcessor.java              # @AILocked example (legacy system)
    ├── security/
    │   └── SecurityConfig.java                # @AILocked + @AIContext example
    ├── service/
    │   ├── NotificationService.java           # @AIDraft example (AI should implement)
    │   └── OrderService.java                  # Mixed annotations example
    ├── strategy/
    │   ├── PaymentStrategy.java               # @AIContext enforcing design pattern
    │   └── impl/
    │       └── CreditCardStrategy.java        # @AIDraft implementation example
    └── utils/
        └── StringParser.java                  # @AIContext optimization hints
```

## 🚀 Quick Start

### Prerequisites

- **Java 11 or higher** installed
- **Maven 3.6+** or **Gradle 7.0+** installed
- **VibeTags library** built and installed locally (see below)

### Step 1: Install VibeTags Library

Before using this example project, you need to build and install the VibeTags library:

```bash
# Navigate to the vibetags library directory
cd ../vibetags

# Build and install to local Maven repository (Maven)
mvn clean install

# OR using Gradle
gradle clean build publishToMavenLocal
```

This creates the `com.vibetags:vibetags-processor:1.0.0-SNAPSHOT` artifact in your local Maven repository.

### Step 2: Build the Example Project

**Using Maven:**
```bash
# Navigate to this example directory
cd example

# Build the project
mvn clean compile
```

**Using Gradle:**
```bash
# Navigate to this example directory
cd example

# Build the project
gradle clean build
```

### Step 3: Check Generated AI Guardrail Files

After compilation, you'll find these files automatically generated in the project root:

- **`.cursorrules`** - Rules for Cursor AI
- **`CLAUDE.md`** - Instructions for Claude
- **`.aiexclude`** - Exclusion list for Gemini
- **`chatgpt_instructions.md`** - Knowledge file for ChatGPT

### Step 4: Run the Application

**Using Maven:**
```bash
mvn exec:java -Dexec.mainClass="com.example.MainApplication"
```

**Using Gradle:**
```bash
gradle runApp
```

Or run directly from your IDE.

## 📖 Annotation Usage Examples

### 1. `@AILocked` - Protect Critical Code

Use when code must NEVER be modified by AI:

```java
@AILocked(reason = "Tied to legacy database schema. Changes will break production.")
public interface PaymentProcessor {
    String processPayment(double amount, String currency, String merchantId);
}
```

**Real-world use cases:**
- Legacy system integrations
- Compliance-critical code (PCI-DSS, HIPAA, etc.)
- Complex algorithms that took months to stabilize
- Third-party API contracts

### 2. `@AIContext` - Guide AI Behavior

Provide specific instructions on HOW AI should work with a class:

```java
@AIContext(
    focus = "Optimize for memory usage over CPU speed",
    avoids = "java.util.regex, String.split(), StringBuilder in loops"
)
public class StringParser {
    // AI will follow these guidelines when implementing methods
}
```

**Real-world use cases:**
- Performance-critical code (memory vs CPU tradeoffs)
- Framework-specific code (must follow framework conventions)
- Security-sensitive code (avoid certain libraries)
- Team conventions (naming, architecture patterns)

### 3. `@AIDraft` - Request AI Implementation

Mark methods that need AI help with detailed instructions:

```java
@AIDraft(instructions = "Implement email sending using JavaMail API. 
    Include HTML template support and attachment handling. 
    Add retry logic for transient failures (max 3 retries with exponential backoff).")
public boolean sendEmail(String to, String subject, String body) {
    // @AIDraft: Implement this
    return false;
}
```

**Real-world use cases:**
- Boilerplate CRUD operations
- API integrations with known libraries
- Standard algorithms (sorting, validation, etc.)
- Test implementations

### 4. Mixed Usage - Fine-Grained Control

You can combine annotations in the same class for precise control:

```java
@AIContext(focus = "Maintain transactional integrity")
public class OrderService {
    
    @AILocked(reason = "47 business rules, 3-month testing cycle")
    public boolean validateOrder(Map<String, Object> orderData) {
        // LOCKED - AI cannot touch this
    }
    
    @AIDraft(instructions = "Implement discount calculation...")
    public double calculateDiscount(String orderId, String discountCode) {
        // AI should implement this
    }
}
```

## 🛠️ Generated AI Configuration Files

When you run `mvn clean compile`, the VibeTags annotation processor scans your code and generates platform-specific configuration files:

### `.cursorrules` (Cursor)

```markdown
# AUTO-GENERATED AI RULES
# Generated from VibeTags annotations

## LOCKED FILES (DO NOT EDIT)
- `PaymentProcessor.java` - Reason: Tied to legacy DB schema
- `SecurityConfig.java` - Reason: Managed by DevOps team

## CONTEXTUAL RULES
- `StringParser.java` - Focus: Optimize for memory, Avoid: java.util.regex
```

**How it works:** Cursor automatically reads this file and injects it into every AI request, ensuring the AI respects your guardrails.

### `CLAUDE.md` (Claude)

```markdown
<project_guardrails>
  <locked_files>
    <file path="PaymentProcessor.java">
      <reason>Tied to legacy DB schema</reason>
    </file>
  </locked_files>
</project_guardrails>

<rule>Never propose edits to files listed in <locked_files>.</rule>
```

**How it works:** Claude treats this file as foundational context. XML tags appeal to Claude's parsing strengths.

### `.aiexclude` (Gemini)

```plaintext
# AUTO-GENERATED BY VIBETAGS
# Files excluded from Gemini context
src/main/java/com/example/payment/PaymentProcessor.java
src/main/java/com/example/security/SecurityConfig.java
```

**How it works:** Gemini literally cannot see or modify files listed in `.aiexclude`. This is a hard guardrail, not just a suggestion.

### `chatgpt_instructions.md` (ChatGPT)

```markdown
# AI Guardrails - ChatGPT Instructions

## Locked Files
Do not modify these files under any circumstances:
- PaymentProcessor.java (Reason: Legacy DB schema)

## Contextual Guidance
When working with these files, follow these guidelines:
- StringParser.java: Focus on memory optimization
```

**How it works:** Upload this file to ChatGPT's Knowledge Base and add "Always review chatgpt_instructions.md before writing code" to Custom Instructions.

## 🎓 Best Practices

### 1. Be Specific in Reasons

❌ **Bad:**
```java
@AILocked(reason = "Important")
```

✅ **Good:**
```java
@AILocked(reason = "Implements PCI-DSS compliance requirements. Changes require security review ticket SEC-XXXX and approval from compliance team.")
```

### 2. Provide Actionable Context

❌ **Bad:**
```java
@AIContext(focus = "Make it fast")
```

✅ **Good:**
```java
@AIContext(
    focus = "Optimize for O(1) lookup time. Use HashMap instead of ArrayList.",
    avoids = "Linear searches, nested loops"
)
```

### 3. Give Detailed Implementation Instructions

❌ **Bad:**
```java
@AIDraft(instructions = "Implement this")
```

✅ **Good:**
```java
@AIDraft(instructions = "Implement using Apache HttpClient 5.x. Include:
  1. Connection pooling (max 10 connections)
  2. Timeout configuration (connect: 5s, read: 10s)
  3. Retry logic (3 retries with exponential backoff)
  4. Proper resource cleanup in finally block
  5. Logging for debugging (DEBUG level for requests, ERROR for failures)")
```

### 4. Layer Your Annotations

Use multiple annotations together for maximum control:

```java
@AIContext(focus = "This is a critical security component")
@AILocked(reason = "Managed by security team only")
public class SecurityConfig {
    // Double protection: context + lock
}
```

### 5. Document Business Rules

```java
@AILocked(reason = "Implements 47 business rules:
  - Rule 1-10: Customer validation
  - Rule 11-20: Pricing calculations
  - Rule 21-30: Tax calculations
  - Rule 31-40: Discount logic
  - Rule 41-47: Fraud detection
  Last modified: 2024-Q2 after 3-month testing cycle")
```

## 🔧 Advanced Configuration

### Gradle Configuration

This project includes a complete `build.gradle` file. The key configuration is:

```groovy
dependencies {
    compileOnly 'com.vibetags:vibetags-processor:1.0.0-SNAPSHOT'
    annotationProcessor 'com.vibetags:vibetags-processor:1.0.0-SNAPSHOT'
}

compileJava {
    options.annotationProcessorPath = configurations.annotationProcessor
}
```

The project also includes a `runApp` task for easy execution:
```bash
gradle runApp
```

### Excluding Annotations from Processing

If you want certain annotations to be ignored by the processor, you can configure this in your `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.11.0</version>
    <configuration>
        <compilerArgs>
            <arg>-AaiGuardrails.ignore=AIDraft</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### Custom Output Directory

By default, AI guardrail files are generated in the project root. To change this:

```xml
<compilerArgs>
    <arg>-AaiGuardrails.outputDir=src/main/resources/ai-rules</arg>
</compilerArgs>
```

## 🐛 Troubleshooting

### Annotation processor not running

**Symptom:** No AI guardrail files generated after `mvn compile`

**Solution:**
1. Ensure VibeTags is installed: `mvn -f ../vibetags/pom.xml clean install`
2. Check dependency scope is `provided` in pom.xml
3. Run with debug logging: `mvn compile -X` and look for "AIGuardrailProcessor"

### Files generated in wrong directory

**Symptom:** Guardrail files appear in unexpected location

**Solution:**
The processor uses `Paths.get("")` which resolves to the directory where Maven/Gradle is executed. Run build from project root.

### Annotations not recognized

**Symptom:** Compilation errors like "cannot find symbol @AILocked"

**Solution:**
1. Verify import statements: `import com.vibetags.annotations.AILocked;`
2. Check VibeTags version matches in pom.xml
3. Run `mvn dependency:tree` to verify dependency resolution

## 📊 When to Use Each Annotation

| Scenario | Annotation | Example |
|----------|-----------|---------|
| Legacy code that works | `@AILocked` | Database schemas, compliance code |
| Performance-critical | `@AIContext` | Algorithms, data structures |
| Boilerplate code | `@AIDraft` | CRUD operations, DTOs |
| Security code | `@AILocked` | Auth, encryption, validation |
| New features | `@AIDraft` | Unimplemented methods |
| Framework code | `@AIContext` | Spring configs, DI setup |
| Mixed concerns | All three | Service classes (see `OrderService.java`) |

## 🎯 Next Steps

1. **Try it yourself:** Add annotations to your own Java projects
2. **Contribute:** Suggest new annotations like `@AIPattern`, `@AITest`, `@AIReview`
3. **Integrate:** Add to your CI/CD pipeline to enforce guardrails
4. **Customize:** Modify the processor to generate custom AI config formats

## 📚 Resources

- **VibeTags Library:** `../vibetags/` - The annotation processor library
- **Official Docs:** [Link to documentation when available]
- **Issue Tracker:** [Link to GitHub issues when available]

## 📝 License

This example project is provided as-is for demonstration purposes. Feel free to use it as a template for your own projects!

---

**Built with ❤️ for safer AI-assisted development**
