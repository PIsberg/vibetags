
# VibeTags - AI Guardrails for Java Development

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/PIsberg/vibetags/badge)](https://securityscorecards.dev/viewer/?uri=github.com/PIsberg/vibetags)
[![Build and Test](https://github.com/PIsberg/vibetags/actions/workflows/build.yml/badge.svg)](https://github.com/PIsberg/vibetags/actions/workflows/build.yml)
[![Java 11 | 17 | 21](https://img.shields.io/badge/Java-11%20%7C%2017%20%7C%2021-orange?logo=openjdk)](https://github.com/PIsberg/vibetags/actions/workflows/build.yml)
[![Maven](https://img.shields.io/badge/build-Maven-blue?logo=apachemaven)](https://github.com/PIsberg/vibetags/actions/workflows/build.yml)
[![Gradle](https://img.shields.io/badge/build-Gradle-blue?logo=gradle)](https://github.com/PIsberg/vibetags/actions/workflows/build.yml)

**VibeTags** is a Java annotation processor that acts as AI guardrails for code generation tools like Cursor, Claude, Gemini, and ChatGPT. It allows developers to control AI behavior through simple annotations, protecting critical code and guiding AI implementations.

## 🎯 What is VibeTags?

VibeTags provides Java annotations that serve as instructions for AI code generation tools. When your project is compiled, the VibeTags annotation processor automatically generates platform-specific configuration files that enforce your rules across different AI platforms.


![vibetags-infographics-v1](https://github.com/user-attachments/assets/964376dd-a8e2-4e45-9909-630641d7c82a)


### Key Features

- **🔒 @AILocked** - Protect critical code from AI modifications (legacy systems, compliance code, security-critical logic)
- **📋 @AIContext** - Guide AI on how to work with specific classes (performance optimizations, design patterns, frameworks)
- **✏️ @AIDraft** - Mark methods that need AI implementation with detailed instructions
- **🛡️ @AIAudit** - Tag critical infrastructure for continuous AI security auditing (SQL injection, thread safety, etc.)

### Supported AI Platforms

Generated configuration files work out-of-the-box with:
- **Cursor** (`.cursorrules`)
- **Claude** (`CLAUDE.md`)
- **Gemini** (`.aiexclude` + `gemini_instructions.md`)
- **ChatGPT** (`chatgpt_instructions.md`)

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
# You'll find .cursorrules, CLAUDE.md, .aiexclude, and chatgpt_instructions.md
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

### Choosing Which AI Services to Support

VibeTags only regenerates config files that already exist on disk — their presence is your opt-in. Nothing is generated automatically, so you always stay in control of which AI tools your project supports.

**Getting started:** create empty placeholder files for the services you use, then compile:

```bash
touch CLAUDE.md .cursorrules   # opt in to Claude and Cursor
mvn compile                    # VibeTags fills them with content
```

**Removing a service:** delete its file — it will never come back.

```bash
rm gemini_instructions.md .aiexclude   # permanently opt out of Gemini
```

**If no files are present**, VibeTags logs a NOTE during compilation listing exactly which files you can create:

```
[NOTE] VibeTags: No AI config files found — nothing will be generated.
Create one or more of the following files in your project root to opt in:
  .cursorrules
  CLAUDE.md
  .aiexclude
  chatgpt_instructions.md
  gemini_instructions.md
```

**Teams:** commit only the files you want. Fresh clones will regenerate only the committed set.

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

**ChatGPT (chatgpt_instructions.md):**
```markdown
### 🔎 SECURITY GUARDRAILS (ENFORCE STRICTLY)
Target File: `com.example.database.DatabaseConnector`
Audit Checklist:
1. Is this code vulnerable to SQL Injection?
2. Is this code vulnerable to Thread Safety issues?
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
