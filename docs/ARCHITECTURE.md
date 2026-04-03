# VibeTags Architecture

## Overview

VibeTags is a Java annotation processor that generates AI platform-specific configuration files from Java source code annotations. It operates at **compile-time only**, with zero runtime overhead.

```
Developer Annotations → javac + Annotation Processor → AI Config Files
```

## Table of Contents

- [System Architecture](#system-architecture)
- [Component Diagram](#component-diagram)
- [Build Sequence](#build-sequence)
- [Core Components](#core-components)
  - [Annotations](#annotations)
  - [Annotation Processor](#annotation-processor)
  - [Generated Output Files](#generated-output-files)
- [Build Flow](#build-flow)
- [Directory Structure](#directory-structure)
- [Design Decisions](#design-decisions)
- [Limitations](#limitations)
- [Future Architecture](#future-architecture)

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Developer Workspace                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌──────────────────┐         ┌──────────────────────────────┐     │
│  │  Java Source     │         │  VibeTags Library             │     │
│  │  Files           │         │  ┌─────────────────────────┐  │     │
│  │                  │         │  │  Annotations             │  │     │
│  │  @AILocked       │         │  │  - AILocked.java         │  │     │
│  │  @AIContext      │────────>│  │  - AIContext.java        │  │     │
│  │  @AIDraft        │  uses   │  │  - AIDraft.java          │  │     │
│  │  @AIAudit        │         │  │  - AIAudit.java          │  │     │
│  └────────┬─────────┘         │  └─────────────────────────┘  │     │
│           │                   │  ┌─────────────────────────┐  │     │
│           │                   │  │  Annotation Processor     │  │     │
│           │                   │  │  AIGuardrailProcessor     │  │     │
│           │                   │  │  (JSR 269 compliant)      │  │     │
│           │                   │  └─────────────────────────┘  │     │
│           │                   └──────────────────────────────┘     │
│           │                                    │                   │
│           ▼                                    ▼                   │
│  ┌───────────────────────────────────────────────────────┐         │
│  │         javac Compiler (with annotation processing)    │         │
│  └───────────────────────────────────────────────────────┘         │
│                            │                                        │
│                            ▼                                        │
│  ┌───────────────────────────────────────────────────────┐         │
│  │         Generated AI Configuration Files               │         │
│  │  ┌─────────────┬──────────┬───────────┬──────────┐   │         │
│  │  │.cursorrules │ CLAUDE.md│ .aiexclude│chatgpt   │   │         │
│  │  │             │          │           │gemini    │   │         │
│  │  └─────────────┴──────────┴───────────┴──────────┘   │         │
│  └───────────────────────────────────────────────────────┘         │
└─────────────────────────────────────────────────────────────────────┘
         │                                        │
         ▼                                        ▼
┌──────────────────┐                    ┌──────────────────────┐
│  AI Platforms    │                    │  AI Assistants        │
│                  │                    │                       │
│  • Cursor IDE   │                    │  Read config files    │
│  • Claude       │                    │  Enforce guardrails   │
│  • Gemini       │                    │  During code gen      │
│  • ChatGPT      │                    │                       │
└──────────────────┘                    └──────────────────────┘
```

---

## Component Diagram

![Class Diagram](diagrams/class-diagram.png)

*Figure 1: Class architecture showing annotations, processor, and generated outputs*

---

## Build Sequence

![Build Sequence](diagrams/build-sequence.png)

*Figure 2: Sequence diagram of annotation processing during compilation*

---

## Core Components

### Annotations

All annotations use `@Retention(RetentionPolicy.SOURCE)` — they exist only at compile time and are stripped from the final bytecode.

| Annotation | Targets | Attributes | Purpose |
|---|---|---|---|
| **`@AILocked`** | TYPE, METHOD, FIELD | `reason: String` | Protects critical code from AI modifications |
| **`@AIContext`** | TYPE, METHOD | `focus: String`, `avoids: String` | Guides AI behavior with positive/negative instructions |
| **`@AIDraft`** | TYPE, METHOD | `instructions: String` | Marks incomplete code needing AI implementation |
| **`@AIAudit`** | TYPE, METHOD | `checkFor: String[]` | Triggers mandatory security vulnerability checks |

### Annotation Processor

**Class:** `com.vibetags.processor.AIGuardrailProcessor`

**Key Characteristics:**
- Extends `javax.annotation.processing.AbstractProcessor` (JSR 269)
- Registered via SPI: `META-INF/services/javax.annotation.processing.Processor`
- Supports Java 11+ source versions
- Processes all `com.vibetags.annotations.*` annotations (wildcard matching)

**Processing Logic:**

```
1. Early exit if no annotations found
2. Determine output directory (current working directory)
3. Initialize 5 StringBuilder accumulators (one per output file)
4. Pass 1: Process @AILocked → append to all builders
5. Pass 2: Process @AIContext → append to all builders
6. Pass 3: Process @AIAudit → append audit sections to all builders
7. Merge audit sections (if any @AIAudit annotations found)
8. Write all 5 files to project root
9. Return true (claim annotations)
```

**Output File Generation:**

| File | Format | Platform | Content |
|---|---|---|---|
| `.cursorrules` | Markdown | Cursor IDE | Locked files, context rules, security audits |
| `CLAUDE.md` | XML + Markdown | Claude | `<locked_files>`, `<contextual_instructions>`, `<audit_requirements>` |
| `.aiexclude` | Glob patterns | Gemini | Binary blocklist of locked files |
| `chatgpt_instructions.md` | Markdown | ChatGPT | Locked files, context rules, security guardrails |
| `gemini_instructions.md` | Markdown | Gemini | Continuous audit requirements |

### Generated Output Files

#### Example: @AIAudit Output

**Source:**
```java
@AIAudit(checkFor = {"SQL Injection", "Thread Safety issues"})
public class DatabaseConnector { }
```

**Generated in `.cursorrules`:**
```markdown
## 🛡️ MANDATORY SECURITY AUDITS
* `com.example.database.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety issues
```

**Generated in `CLAUDE.md`:**
```xml
<audit_requirements>
  <file path="com.example.database.DatabaseConnector">
    <vulnerability_check>SQL Injection</vulnerability_check>
    <vulnerability_check>Thread Safety issues</vulnerability_check>
  </file>
</audit_requirements>
```

**Generated in `gemini_instructions.md`:**
```markdown
# CONTINUOUS AUDIT REQUIREMENTS
File: `com.example.database.DatabaseConnector`
Critical Vulnerabilities to Prevent: 
- SQL Injection
- Thread Safety issues
```

---

## Build Flow

### Maven Flow

```
mvn clean compile
    ↓
Resolve vibetags-processor dependency (provided scope)
    ↓
javac discovers processor via META-INF/services/
    ↓
Compile Java sources
    ↓
AIGuardrailProcessor.process() executes
    ↓
Generate 5 AI config files at project root
    ↓
Compilation complete
```

### Gradle Flow

```
gradle clean build
    ↓
Resolve vibetags-processor (compileOnly + annotationProcessor)
    ↓
javac with explicit annotation processor path
    ↓
Compile Java sources
    ↓
AIGuardrailProcessor.process() executes
    ↓
Generate 5 AI config files at project root
    ↓
Build complete
```

---

## Directory Structure

```
vibetags/
├── vibetags/                          # Core annotation processor library
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/vibetags/
│   │   │   │   ├── annotations/       # Annotation definitions (SOURCE retention)
│   │   │   │   │   ├── AILocked.java
│   │   │   │   │   ├── AIContext.java
│   │   │   │   │   ├── AIDraft.java
│   │   │   │   │   └── AIAudit.java
│   │   │   │   └── processor/         # JSR 269 annotation processor
│   │   │   │       └── AIGuardrailProcessor.java
│   │   │   └── resources/META-INF/services/
│   │   │       └── javax.annotation.processing.Processor
│   │   └── test/                      # Unit + integration tests
│   ├── pom.xml                        # Maven build config
│   └── build.gradle                   # Gradle build config
│
├── example/                           # Demo e-commerce application
│   ├── src/main/java/com/example/
│   │   ├── database/
│   │   │   └── DatabaseConnector.java         # @AIAudit example
│   │   ├── payment/
│   │   │   └── PaymentProcessor.java          # @AILocked example
│   │   ├── security/
│   │   │   └── SecurityConfig.java            # @AILocked + @AIContext
│   │   ├── service/
│   │   │   ├── NotificationService.java       # @AIContext + @AIDraft
│   │   │   └── OrderService.java              # Mixed annotations
│   │   ├── strategy/
│   │   │   ├── PaymentStrategy.java           # @AIContext
│   │   │   └── impl/CreditCardStrategy.java   # @AIDraft
│   │   └── utils/
│   │       └── StringParser.java              # @AIContext
│   ├── .cursorrules                   # Generated: Cursor rules
│   ├── CLAUDE.md                      # Generated: Claude guardrails
│   ├── .aiexclude                     # Generated: Gemini blocklist
│   ├── chatgpt_instructions.md        # Generated: ChatGPT instructions
│   ├── gemini_instructions.md         # Generated: Gemini audit requirements
│   ├── pom.xml                        # Maven build config
│   └── build.gradle                   # Gradle build config
│
├── docs/                              # Documentation
│   ├── ARCHITECTURE.md                # This file
│   ├── CONCEPT_PLUGIN.md              # Future plugin architecture
│   └── diagrams/                      # PlantUML source + images
│       ├── class-diagram.puml
│       ├── class-diagram.png
│       ├── build-sequence.puml
│       └── build-sequence.png
│
├── src/                               # Web UI (React + Vite)
├── package.json
└── README.md
```

---

## Design Decisions

### 1. SOURCE Retention

**Decision:** All annotations use `RetentionPolicy.SOURCE`

**Rationale:**
- Zero runtime overhead — annotations stripped during compilation
- No dependency pollution in production artifacts
- Processor only needed at compile-time
- Consumer projects have no runtime dependency on VibeTags

### 2. Single Processor, Multiple Outputs

**Decision:** One processor generates all 5 output files in a single pass

**Rationale:**
- Single source of truth for annotation data
- Consistent content across all platforms
- No duplication of parsing logic
- Atomic generation (all or nothing)

### 3. Working Directory Output

**Decision:** Output files written to `Paths.get("").toAbsolutePath()`

**Rationale:**
- Works for standard Maven/Gradle builds from project root
- No configuration required
- Files land where developers expect them

**Trade-off:** Can break in IDE builds or subdirectory builds (see [Limitations](#limitations))

### 4. StringBuilder Accumulation

**Decision:** Build entire file content in memory before writing

**Rationale:**
- Simple implementation
- Easy to reason about
- Files are small (< 10KB typically)
- Atomic write (write succeeds or fails completely)

### 5. Wildcard Annotation Matching

**Decision:** `@SupportedAnnotationTypes("com.vibetags.annotations.*")`

**Rationale:**
- Automatically picks up new annotations without code changes
- Single processor handles all VibeTags annotations
- Easy to extend with new annotation types

---

## Limitations

### 1. Output Location Fragility

**Problem:** Uses `Paths.get("")` which resolves to JVM working directory

**Impact:**
- Can write to wrong directory in IDE builds
- Breaks if build invoked from subdirectory
- No way to customize output location

**Workaround:** Always build from project root directory

### 2. No Incremental Build Awareness

**Problem:** Regenerates all files on every compilation

**Impact:**
- Unnecessary file I/O
- Can interfere with build caching
- No way to skip generation if annotations unchanged

### 3. Hardcoded Output Formats

**Problem:** Each platform's format is hardcoded in the processor

**Impact:**
- Cannot customize template structure
- Adding new platforms requires code changes
- No user control over formatting

### 4. No Validation

**Problem:** No validation of annotation values

**Impact:**
- Empty `checkFor` arrays silently skipped
- No warnings for conflicting annotations
- No enforcement of best practices

---

## Future Architecture

See [CONCEPT_PLUGIN.md](CONCEPT_PLUGIN.md) for the proposed migration to a plugin/CLI architecture.

### Proposed Components

```
vibetags-core/          # Shared scanning + generation logic
vibetags-cli/           # Standalone CLI (any language support)
vibetags-maven-plugin/  # Maven plugin with configurable output paths
vibetags-gradle-plugin/ # Gradle plugin with task configuration avoidance
vibetags-processor/     # Legacy wrapper (deprecated)
```

### Key Improvements

- **Configurable output paths** via `vibetags.yaml`
- **Language-agnostic** support for comment-based annotations
- **Incremental build support** with file change detection
- **Customizable templates** for output formats
- **Validation and warnings** for annotation misuse

---

## Testing Strategy

### Unit Tests (vibetags/)

| Test Class | Tests | Purpose |
|---|---|---|
| `AnnotationDefinitionsTest` | 17 | Verify annotation structure and defaults |
| `AIGuardrailProcessorTest` | 3 | Processor configuration validation |
| `AIGuardrailProcessorUnitTest` | 5 | Processor structure and inheritance |
| `AnnotationProcessorEndToEndTest` | 13 | Generated file content validation |
| `AIGuardrailProcessorIntegrationTest` | 9 | Full workflow end-to-end (conditional) |

### Integration Tests

Run with: `mvn test -Drun.integration.tests=true`

Tests require the example project to be compiled first.

### CI/CD

GitHub Actions workflow tests:
- **Maven builds:** JDK 11, 17, 21
- **Gradle builds:** JDK 17, 21 (Gradle requires 17+)
- Verifies generated file existence
- Validates @AIAudit content in all outputs

---

## Dependencies

### vibetags-processor

| Dependency | Scope | Purpose |
|---|---|---|
| `javax.annotation.processing.*` | JDK (compile) | JSR 269 API |
| `javax.lang.model.*` | JDK (compile) | Language model API |
| `org.junit.jupiter` | test | Unit testing |

### example

| Dependency | Scope | Purpose |
|---|---|---|
| `com.vibetags:vibetags-processor` | provided / compileOnly | Annotations + processor |

**Note:** Annotations have zero runtime footprint — they are completely stripped during compilation.

---

## Build Commands

### Maven

```bash
# Build library
cd vibetags && mvn clean install

# Build example (generates AI config files)
cd example && mvn clean compile

# Run tests
cd vibetags && mvn test

# Run integration tests
cd vibetags && mvn test -Drun.integration.tests=true
```

### Gradle

```bash
# Build library
cd vibetags && gradle clean build publishToMavenLocal

# Build example (generates AI config files)
cd example && gradle clean build

# Run tests
cd vibetags && gradle test
```

---

## AI Platform Integration

### Cursor

**File:** `.cursorrules`

**Behavior:** Cursor reads this file automatically and injects it into every AI request. AI respects locked files and follows context rules.

### Claude

**File:** `CLAUDE.md`

**Behavior:** Claude treats this as foundational context. XML tags appeal to Claude's parsing strengths. Enforces `<rule>` elements strictly.

### Gemini

**Files:** `.aiexclude` + `gemini_instructions.md`

**Behavior:** `.aiexclude` is a binary blocklist (hard guardrail). `gemini_instructions.md` should be pasted into Custom Instructions.

### ChatGPT

**File:** `chatgpt_instructions.md`

**Behavior:** Upload to Project Knowledge base. Add "Always review chatgpt_instructions.md before writing code" to Custom Instructions.

---

*Last updated: April 2026*
