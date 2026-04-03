<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://github.com/user-attachments/assets/0aa67016-6eaf-458a-adb2-6e31a0763ed6" />
</div>

# VibeTags - AI Guardrails for Java Development

**VibeTags** is a Java annotation processor that acts as AI guardrails for code generation tools like Cursor, Claude, Gemini, and ChatGPT. It allows developers to control AI behavior through simple annotations, protecting critical code and guiding AI implementations.

## 🎯 What is VibeTags?

VibeTags provides Java annotations that serve as instructions for AI code generation tools. When your project is compiled, the VibeTags annotation processor automatically generates platform-specific configuration files that enforce your rules across different AI platforms.

### Key Features

- **🔒 @AILocked** - Protect critical code from AI modifications (legacy systems, compliance code, security-critical logic)
- **📋 @AIContext** - Guide AI on how to work with specific classes (performance optimizations, design patterns, frameworks)
- **✏️ @AIDraft** - Mark methods that need AI implementation with detailed instructions

### Supported AI Platforms

Generated configuration files work out-of-the-box with:
- **Cursor** (`.cursorrules`)
- **Claude** (`CLAUDE.md`)
- **Gemini** (`.aiexclude`)
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
- **Node.js 18+** (for the web UI)

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

### Run the Web UI (Optional)

The web UI provides a visual interface for working with AI assistants:

```bash
npm install
npm run dev
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

- **Custom output directories** for generated files
- **Selective annotation processing** (ignore certain annotation types)
- **Mixed annotation usage** for fine-grained control
- **Platform-specific configurations** generated automatically

## 🤝 Contributing

VibeTags is designed to evolve based on community needs. Future extensions could include:

- `@AIPattern` - Specify design patterns AI should follow
- `@AITest` - Guide AI in generating tests
- `@AIReview` - Mark code for AI code review with specific criteria
- Custom annotation processors for organization-specific needs

## 📊 Project Components

### vibetags/
The core annotation processor library. Contains the Java annotations and the annotation processor that generates AI configuration files.

### example/
A practical e-commerce application demonstrating real-world usage of VibeTags annotations. Shows how to protect legacy payment processors, guide AI on security configurations, and request AI implementations for notification services.

### Web UI (Root)
A React-based web interface for interacting with AI assistants and managing your VibeTags configuration.

## 📝 License

This project is provided as-is for demonstration purposes. Feel free to use it as a template for your own projects!

## 🌟 Why VibeTags?

AI code generation tools are powerful but need guardrails. VibeTags gives developers a standardized, programmatic way to control AI behavior across platforms, ensuring critical code stays protected while AI gets clear guidance on where and how to help.

**Built with ❤️ for safer AI-assisted development**
