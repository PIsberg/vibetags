
# VibeTags - AI Guardrails for Java Development

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/PIsberg/vibetags/badge)](https://securityscorecards.dev/viewer/?uri=github.com/PIsberg/vibetags)
[![Build and Test](https://github.com/PIsberg/vibetags/actions/workflows/build.yml/badge.svg)](https://github.com/PIsberg/vibetags/actions/workflows/build.yml)
[![Java 11 | 17 | 21](https://img.shields.io/badge/Java-11%20%7C%2017%20%7C%2021-orange?logo=openjdk)](https://github.com/PIsberg/vibetags/actions/workflows/build.yml)
[![Maven](https://img.shields.io/badge/build-Maven-blue?logo=apachemaven)](https://github.com/PIsberg/vibetags/actions/workflows/build.yml)
[![Gradle](https://img.shields.io/badge/build-Gradle-blue?logo=gradle)](https://github.com/PIsberg/vibetags/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/PIsberg/vibetags/branch/main/graph/badge.svg)](https://codecov.io/gh/PIsberg/vibetags)


**VibeTags** is a Java annotation processor that acts as AI guardrails for code generation tools like Cursor, Claude, Gemini, and Codex CLI. It allows developers to control AI behavior through simple annotations, protecting critical code and guiding AI implementations.

## 🎯 What is VibeTags?

VibeTags provides Java annotations that serve as instructions for AI code generation tools. When your project is compiled, the VibeTags annotation processor automatically generates platform-specific configuration files that enforce your rules across different AI platforms.


![vibetags-infographics-v1](https://github.com/user-attachments/assets/964376dd-a8e2-4e45-9909-630641d7c82a)


### Key Features

- **🔒 @AILocked** - Protect critical code from AI modifications (legacy systems, compliance code, security-critical logic)
- **📋 @AIContext** - Guide AI on how to work with specific classes (performance optimizations, design patterns, frameworks)
- **✏️ @AIDraft** - Mark methods or classes that need AI implementation with detailed instructions
- **🛡️ @AIAudit** - Tag critical infrastructure for continuous AI security auditing (SQL injection, thread safety, etc.)
- **🚫 @AIIgnore** - Exclude classes, methods, or fields from AI context entirely (auto-generated code, deprecated scaffolding)

### Supported AI Platforms

Generated configuration files work out-of-the-box with:
- **Cursor** (`.cursorrules`)
- **Claude** (`CLAUDE.md`)
- **Gemini** (`.aiexclude` + `gemini_instructions.md`)
- **Codex CLI** (`AGENTS.md`, `.codex/config.toml`, `.codex/rules/*.rules`)
- **GitHub Copilot** (`.github/copilot-instructions.md`, `.copilotignore`)
- **Cursor** (`.cursorignore`)
- **Claude** (`.claudeignore`)
- **Qwen** (`QWEN.md`, `.qwen/settings.json`, `.qwenignore`)
- **Gemini/Codex** (`.aiexclude`)

## 📁 Project Structure

```
vibetags/
├── vibetags/              # Core annotation processor library
│   ├── pom.xml           # Maven build configuration
│   ├── build.gradle      # Gradle build configuration
│   └── src/              # Library source code
├── example/              # Example e-commerce application
│   ├── pom.xml           # Maven build configuration
│   ├── build.gradle      # Gradle build configuration (NEW)
│   ├── README.md         # Detailed usage guide and best practices
│   └── src/              # Example source code with annotations
└── README.md             # This file
```

## 🚀 Quick Start

### Prerequisites

- **Java 11 or higher**
- **Maven 3.6+** or **Gradle 7.0+**

### Option 1: Using Maven

```bash
# Step 1: Install the VibeTags library
cd vibetags
mvn clean install

# Step 2: Build the example project
cd ../example
mvn clean compile

# Step 3: Check generated AI guardrail files
# (Note: Files are only updated if they ALREADY exist on disk)
```

### Option 2: Using Gradle

```bash
# Step 1: Install the VibeTags library
cd vibetags
gradle clean build publishToMavenLocal

# Step 2: Build the example project
cd ../example
gradle clean build

# Step 3: Check generated AI guardrail files
```

## 📖 How It Works

1. **Add Annotations** - Place VibeTags annotations on your Java classes and methods
2. **Compile** - Run your normal build process (Maven/Gradle)
3. **Generate** - VibeTags automatically creates AI configuration files
4. **Use** - AI tools read these files and follow your guardrails

### Example Usage

```java
// Protect critical legacy code
@AILocked(reason = "Tied to legacy database schema. Changes will break production.")
public interface PaymentProcessor {
    String processPayment(double amount, String currency, String merchantId);
}

// Guide AI behavior for performance-critical code
@AIContext(
    focus = "Optimize for memory usage over CPU speed",
    avoids = "java.util.regex, String.split(), StringBuilder in loops"
)
public class StringParser {
    // AI will follow these guidelines
}

// Request AI implementation
@AIDraft(instructions = "Implement email sending with HTML template support and retry logic")
public boolean sendEmail(String to, String subject, String body) {
    // @DIDraft: AI should implement this
}

// Tag critical infrastructure for continuous security auditing
@AIAudit(checkFor = {"SQL Injection", "Thread Safety issues"})
public class DatabaseConnector {
    // AI must audit any modifications for SQL injection and thread safety
}

// Exclude auto-generated code from AI context entirely
@AIIgnore(reason = "Auto-generated at build time. Manual edits are overwritten on every build.")
public class GeneratedMetadata {
    // AI tools will not reference or suggest changes to this class
}
```

## 📚 Documentation

For detailed usage examples, best practices, and advanced configuration, see the **[Example Project README](example/README.md)**.

Key topics covered:
- Detailed annotation usage examples
- Generated AI configuration file formats
- Best practices for writing effective annotations
- Integration with CI/CD pipelines
- Troubleshooting common issues

## 🛠️ Building Both Projects

### Build Everything with Maven

```bash
# Build library
cd vibetags && mvn clean install

# Build example
cd ../example && mvn clean compile
```

### Build Everything with Gradle

```bash
# Build library
cd vibetags && gradle clean build publishToMavenLocal

# Build example
cd ../example && gradle clean build
```

## 🎓 When to Use VibeTags

| Scenario | Use Case |
|----------|----------|
| Legacy systems | Protect integrations that work and can't be changed |
| Compliance code | PCI-DSS, HIPAA, and other regulated code |
| Performance-critical | Guide AI toward specific optimization strategies |
| Boilerplate code | Let AI implement standard patterns safely |
| Team projects | Enforce consistent AI behavior across your team |
| Complex algorithms | Protect code that took months to stabilize |

## 🔧 Advanced Features

- **Selective service generation** — opt out of specific AI platforms with no config required
- **Mixed annotation usage** for fine-grained control
- **Platform-specific configurations** generated automatically
- **Version Stamping** — every generated file includes a VibeTags version header for easy traceability
- **Compile-time Validation** — proactive warnings for contradictory or empty annotations

### Choosing Which AI Services to Support (Opt-in Model)

VibeTags operates on a **Strict Opt-in Model**. It **never** creates new configuration files on its own. Instead, it only populates or updates files that **already exist** in your project root. 

> [!IMPORTANT]
> **File Presence = Opt-in**. The existence of a specific file (like `CLAUDE.md`) is the signal VibeTags uses to determine which AI service you are using. If the file doesn't exist, VibeTags will not generate content for that service.

**How to enable a service:** 
Create an empty placeholder file for the service you want to support, then compile your project.

**Getting started:** create empty placeholder files for the services you use, then compile:

```bash
touch .cursorrules .cursorignore             # Enable Cursor support
touch CLAUDE.md .claudeignore                # Enable Claude support
touch QWEN.md .qwenignore                   # Enable Qwen support
touch .aiexclude gemini_instructions.md      # Enable Gemini/Codex support
mkdir -p .github && touch .github/copilot-instructions.md .copilotignore # Enable Copilot

mvn compile                                  # VibeTags populates accurately
```

**Removing a service:** delete its file — it will never come back.

```bash
rm gemini_instructions.md   # permanently opt out of Gemini instructions
```

**If no files are present**, VibeTags logs a NOTE during compilation listing exactly which files you can create:

```
[NOTE] VibeTags: No AI config files found — nothing will be generated.
Create one or more of the following files in your project root to opt in:
  .cursorrules
  CLAUDE.md
  gemini_instructions.md
  .github/copilot-instructions.md
  .cursorignore
  .claudeignore
  .copilotignore
  .qwenignore
```

**Teams:** Only commit the config files for the AI tools your team actually uses.

### ⚠️ Orphaned Annotation Warnings

If you use a VibeTags annotation (like `@AIIgnore`) but haven't created the recommended standalone file for an active service, the compiler will issue a **WARNING** to guide you:

`[WARNING] VibeTags: @AIIgnore used but .cursorignore is missing for Cursor support. Consider creating it.`

`[WARNING] VibeTags: @AIIgnore used but .qwenignore is missing for Qwen support. Consider creating it.`

This helps you ensure your guardrails are correctly positioned without VibeTags forcing files into your project.

### 🛡️ @AIAudit - Continuous Security Auditing

The `@AIAudit` annotation enables continuous security auditing for critical infrastructure. When you tag a class or method with `@AIAudit`, AI assistants will automatically perform security reviews whenever they propose modifications to that code.

#### How It Works

1. **Annotate Critical Code**: Add `@AIAudit` with specific vulnerability checks
2. **Compile**: The annotation processor generates audit requirements
3. **AI Self-Audits**: When AI assistants modify tagged code, they must check for listed vulnerabilities

#### Example Usage

```java
@AIAudit(checkFor = {"SQL Injection", "Thread Safety issues"})
public class DatabaseConnector {
    // Database connection and query handling code
}
```

#### Generated Output by Platform

**Cursor (.cursorrules):**
```markdown
## 🛡️ MANDATORY SECURITY AUDITS
* `com.example.database.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety issues
```

**Claude (CLAUDE.md):**
```xml
<audit_requirements>
  <file path="com.example.database.DatabaseConnector">
    <vulnerability_check>SQL Injection</vulnerability_check>
    <vulnerability_check>Thread Safety issues</vulnerability_check>
  </file>
</audit_requirements>
```

**Gemini (gemini_instructions.md):**
```markdown
# CONTINUOUS AUDIT REQUIREMENTS
File: `com.example.database.DatabaseConnector`
Critical Vulnerabilities to Prevent: 
- SQL Injection
- Thread Safety issues
```

**Codex CLI (AGENTS.md):**
```markdown
## 🛡️ MANDATORY SECURITY AUDITS
* `com.example.database.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety issues
```

#### Common Vulnerability Checks

- SQL Injection
- Thread Safety issues
- XSS (Cross-Site Scripting)
- CSRF (Cross-Site Request Forgery)
- Command Injection
- Path Traversal
- Insecure Deserialization
- Authentication Bypass

### ✏️ @AIDraft - Requesting AI Implementations

The `@AIDraft` annotation allows you to mark specific classes or methods as drafts that require implementation. This surfaces actionable instructions directly to AI assistants in their respective configuration formats.

#### Example Usage

```java
@AIDraft(instructions = "Implement email sending via SMTP and push notifications via FCM. Ensure retry logic and rate limiting are applied.")
public class NotificationService {
    public void sendNotification(String userId, String message) {
        // AI will implement this based on instructions above
    }
}
```

#### Generated Output (Cursor .cursorrules)
```markdown
## 📝 IMPLEMENTATION TASKS (TODO)
* `com.example.NotificationService` - Task: Implement email sending via SMTP and push notifications via FCM. Ensure retry logic and rate limiting are applied.
```

### ⚠️ Smart Validation Warnings

VibeTags performs smart validation during compilation to ensure your guardrails are consistent. If it detects a contradiction or a missing configuration, it will issue a `WARNING` but will not break your build.

#### Contradictory Annotations
If you use both `@AIDraft` (implement this) and `@AILocked` (do not touch this) on the same element, VibeTags will warn you:
`[WARNING] VibeTags: com.example.MyClass is annotated with both @AIDraft and @AILocked. This is contradictory.`

#### Empty Audits
If you use `@AIAudit` without providing any items to check for, VibeTags will warn you:
`[WARNING] VibeTags: @AIAudit on com.example.MyClass has no 'checkFor' items list. It will be ignored.`

## 🤝 Contributing

VibeTags is designed to evolve based on community needs. Future extensions could include:

- `@AIPattern` - Specify design patterns AI should follow
- `@AITest` - Guide AI in generating tests
- Custom annotation processors for organization-specific needs

## 📊 Project Components

### vibetags/
The core annotation processor library. Contains the Java annotations (@AILocked, @AIContext, @AIDraft, @AIAudit) and the annotation processor that generates AI configuration files.

### example/
A practical e-commerce application demonstrating real-world usage of VibeTags annotations. Shows how to protect legacy payment processors, guide AI on security configurations, request AI implementations for notification services, and enforce continuous security auditing for critical database infrastructure.

## 📝 License

This project is licensed under the [MIT License](LICENSE).

## 🌟 Why VibeTags?

AI code generation tools are powerful but need guardrails. VibeTags gives developers a standardized, programmatic way to control AI behavior across platforms, ensuring critical code stays protected while AI gets clear guidance on where and how to help.

**Built with ❤️ for safer AI-assisted development**
