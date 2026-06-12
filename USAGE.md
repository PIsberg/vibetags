# VibeTags — Usage & Annotation Reference

> The complete guide to configuring VibeTags and using every annotation. New here? Start with the [README](README.md) for the 60-second quickstart and installation, then come back for the deep dive.

## Contents

- [Logging Configuration](#logging-configuration)
- [Choosing Which AI Services to Support (Opt-in Model)](#choosing-which-ai-services-to-support-opt-in-model)
- [Granular Rules (Cursor, Trae, Roo Code)](#-granular-rules-cursor-trae-roo-code)
- [Qwen Configuration](#-qwen-configuration)
- [llms.txt Standard](#-llmstxt-standard-windsurf-cascade--llm-agents)
- [Orphaned Annotation Warnings](#️-orphaned-annotation-warnings)
- [@AIAudit — Continuous Security Auditing](#️-aiaudit---continuous-security-auditing)
- [@AIDraft — Requesting AI Implementations](#️-aidraft---requesting-ai-implementations)
- [Smart Validation Warnings](#️-smart-validation-warnings)
- [@AIContract — Freezing Public API Signatures](#-aicontract---freezing-public-api-signatures)
- [@AITestDriven — Test-Driven AI Requirements](#-aitestdriven---test-driven-ai-requirements)
- [Design-Intent Annotations (v0.9.8)](#-new-in-v098-five-design-intent-annotations)
- [Platform Guardrail Annotations (v0.9.8)](#️-new-in-v098-continued-nine-platform-guardrail-annotations)
- [Precision Guardrail Annotations (v0.9.9)](#-new-in-v099-twelve-precision-guardrail-annotations)

For the full annotation table, processor options, and output-file formats, see also [CLAUDE.md](CLAUDE.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## 🔧 Advanced Features

- **Selective service generation** — opt out of specific AI platforms with no config required
- **Mixed annotation usage** for fine-grained control
- **Platform-specific configurations** generated automatically
- **Version Stamping** — every generated file includes a VibeTags version header for easy traceability
- **Compile-time Validation** — proactive warnings for contradictory or empty annotations
- **Configurable Logging** — full control over log file path and level, including turning it off
- **Granular Rules Support** — automatic generation of `.mdc` and `.md` files with YAML front-matter for precise AI scoping
- **Expanded Tool Support** — built-in support for Aider, Roo Code, and Trae

### Logging Configuration

VibeTags uses a dedicated file-based logger to record its operations during compilation. By default, it writes to `vibetags.log` in the project root at `INFO` level.

You can customize the log path and level using annotation processor options:

| Option | Default | Description |
|---|---|---|
| `vibetags.log.path` | `vibetags.log` | Path to the log file. Relative paths are resolved against the project root. Absolute paths are used as-is. |
| `vibetags.log.level` | `INFO` | Logback level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, or `OFF`. Set to `OFF` to disable file logging entirely. |

#### Maven Configuration

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>-Avibetags.log.path=logs/vibetags.log</arg>
            <arg>-Avibetags.log.level=DEBUG</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

#### Gradle Configuration

```groovy
tasks.withType(JavaCompile) {
    options.compilerArgs += [
        '-Avibetags.log.path=logs/vibetags.log',
        '-Avibetags.log.level=DEBUG'
    ]
}
```

### Check Mode — CI Drift Enforcement (Opt-in)

By default VibeTags *generates* guardrail files and never fails your build. **Check mode** flips that for CI: it verifies that every generated file is in sync with the annotations and **fails the compile** if anything has drifted — without writing a single byte to disk.

| Option | Default | Description |
|---|---|---|
| `vibetags.check` | `false` | When `true`, verify instead of generate: run the full content build and multi-module merge, compare against the files on disk, and report every file a normal compile would change as a compile **error**. Nothing is written — no output files, no sidecars, no cache. |

Drift is detected for all of it: changed annotations not yet regenerated, hand-edited generated blocks, stale granular rule files, and orphaned granular files a normal compile would clean up. The write cache and fingerprint short-circuit are bypassed, so the verdict never depends on local cache state — a fresh CI checkout works.

Javac `-A` options can't be passed as a Maven user property, so opt in through a profile.

**Maven profile** (CI opts in with `mvn compile -P vibetags-check`; the default build is untouched):

```xml
<profile>
    <id>vibetags-check</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <compilerArgs>
                        <arg>-Avibetags.check=true</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

**Gradle:**

```groovy
// Opt in via a project property: gradle compileJava -PvibetagsCheck
tasks.withType(JavaCompile) {
    if (project.hasProperty('vibetagsCheck')) {
        options.compilerArgs += ['-Avibetags.check=true']
    }
}
```

**GitHub Actions:**

```yaml
- name: Verify AI guardrails are in sync
  run: mvn -B compile -P vibetags-check
```

When the check fails, the error lists every out-of-date file:

```text
[ERROR] VibeTags: check failed — 3 guardrail file(s) are out of date with the annotations:
  - CLAUDE.md
  - .cursorrules
  - .cursor/rules/com-example-PaymentProcessor.mdc
Run a normal compile (without -Avibetags.check=true) and commit the regenerated files.
```

To fix: run a normal compile locally and commit the regenerated files.

### Locked-Code PR Guard (GitHub Action)

Guardrail files *ask* AI tools to keep out of `@AILocked` code — the locked-files guard *verifies* nothing slipped through. It fails a pull request whose diff touches locked code.

**1. Opt in to the machine-readable lock report** (same file-existence model as every platform):

```bash
touch .vibetags-locks
mvn compile
```

The compile writes `.vibetags-locks` — JSON Lines between `# VIBETAGS` hash markers, one entry per `@AILocked` element with its source file and 1-based line range:

```text
# VIBETAGS-START
{"type":"locked","element":"com.example.PaymentProcessor","kind":"CLASS","file":"src/main/java/com/example/PaymentProcessor.java","startLine":12,"endLine":240,"reason":"Core payment logic"}
# VIBETAGS-END
```

Line positions come from the javac Compiler Tree API; under other compilers (ECJ) entries omit positions and tools fall back to file-level matching. In Maven multi-module builds the report aggregates every module's locks via module sub-markers.

**2. Add the action to your PR workflow:**

```yaml
jobs:
  locked-files:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 21
      - uses: PIsberg/vibetags/action/locked-files@main
```

The action touches `.vibetags-locks` itself, rebuilds the PR head (so the report is never stale), and flags three things as inline PR annotations: edits inside a locked line range, removal of an `@AILocked` annotation line, and deletion of a file that contained `@AILocked`. Set `warn-only: true` to report without failing. See [action/locked-files/README.md](action/locked-files/README.md) for all inputs.

### Choosing Which AI Services to Support (Opt-in Model)

VibeTags operates on a **Strict Opt-in Model**. It **never** creates new configuration files on its own. Instead, it only populates or updates files that **already exist** in your project root.

> [!IMPORTANT]
> **File Presence = Opt-in**. The existence of a specific file (like `CLAUDE.md`) is the signal VibeTags uses to determine which AI service you are using. If the file doesn't exist, VibeTags will not generate content for that service.

**How to enable a service:**
Create an empty placeholder file for the service you want to support, then compile your project.

**Getting started:** create empty placeholder files for the services you use, then compile:

```bash
# --- Cursor ---
touch .cursorrules .cursorignore             # Traditional + ignore
mkdir -p .cursor/rules                       # Granular rules (per-class .mdc)

# --- Windsurf ---
touch .windsurfrules                         # Traditional .windsurfrules
mkdir -p .windsurf/rules                     # Granular rules (per-class .md)

# --- Zed, Cody, Supermaven ---
touch .rules                                 # Zed Editor
touch .codyignore && mkdir -p .cody && touch .cody/config.json  # Sourcegraph Cody
touch .supermavenignore                      # Supermaven

# --- Continue, Tabnine, Amazon Q, Universal AI ---
mkdir -p .continue/rules                     # Continue
mkdir -p .tabnine/guidelines                 # Tabnine
mkdir -p .amazonq/rules                      # Amazon Q
mkdir -p .ai/rules                           # Universal .ai/rules standard

# --- Trae, Roo Code ---
mkdir -p .trae/rules                         # Trae IDE
mkdir -p .roo/rules                          # Roo Code

# --- PearAI ---
mkdir -p .pearai/rules                       # PearAI granular rules (per-class .md)

# --- Amazon Kiro ---
mkdir -p .kiro/steering                      # Amazon Kiro steering files (per-class .md)

# --- Mentat, Sweep, Plandex ---
touch .mentatconfig.json                     # Mentat AI assistant
touch sweep.yaml                             # Sweep AI code review (GitHub App)
touch .plandex.yaml                          # Plandex AI coding agent

# --- Double.bot, Open Interpreter, Codeium, Antigravity ---
touch .doubleignore                          # Double.bot exclusion list
mkdir -p .interpreter/profiles && touch .interpreter/profiles/vibetags.yaml  # Open Interpreter
touch .codeiumignore                         # Codeium exclusion list
touch .antigravityignore                     # Antigravity AI exclusion list

# --- Cline, JetBrains Junie ---
touch .clinerules                            # Cline AI assistant
mkdir -p .junie && touch .junie/guidelines.md  # JetBrains Junie

# --- Other platforms ---
touch CONVENTIONS.md .aiderignore            # Aider
touch CLAUDE.md .claudeignore                # Claude
touch QWEN.md .qwenignore                   # Qwen
touch .aiexclude gemini_instructions.md GEMINI.md  # Gemini
mkdir -p .github && touch .github/copilot-instructions.md .copilotignore  # GitHub Copilot
touch AGENTS.md                              # Codex CLI
touch llms.txt llms-full.txt                 # Windsurf Cascade / llms.txt standard

mvn compile                                  # VibeTags populates all opted-in files
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

### 🧩 Granular Rules (Cursor, Trae, Roo Code)

For modern AI IDEs like **Cursor** and **Trae**, VibeTags supports a granular rule system. Instead of one giant configuration file, VibeTags generates specific files for each annotated element.

- **Scoping**: Each rule is automatically scoped via `globs`. A rule for `OrderService` will only apply when the AI is working on `OrderService.java`.
- **Triggers**: Uses YAML front-matter (`.mdc` for Cursor, `.md` for Trae) to help the AI understand exactly when to apply each rule.
- **Cleanup**: VibeTags automatically cleans up "orphaned" rule files in these directories if you remove the corresponding annotations from your source code.

**Enable this mode** by creating the target directories:
```bash
mkdir -p .cursor/rules
mkdir -p .trae/rules
mkdir -p .roo/rules
```

### 🤖 Qwen Configuration

VibeTags generates comprehensive Qwen configuration files:

**QWEN.md** - Main project context file:
```markdown
# PROJECT CONTEXT
## LOCKED FILES (DO NOT EDIT)
* `com.example.PaymentProcessor` — Reason here

## CONTEXTUAL RULES
* `com.example.StringParser`
  * Focus: Optimize for memory usage
  * Avoid: java.util.regex, String.split()

## 🛡️ MANDATORY SECURITY AUDITS
* `com.example.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety

## IGNORED ELEMENTS
* `com.example.GeneratedMetadata`
```

**.qwen/settings.json** - Qwen model configuration:
```json
{
  "project": {
    "model": "qwen3-coder-plus",
    "mcp": {
      "enabled": true
    }
  }
}
```

**.qwen/commands/refactor.md** - Custom `/refactor` command for code refactoring

**.qwenignore** - Glob patterns for files to exclude from Qwen's context

### 🌐 llms.txt Standard (Windsurf Cascade & LLM Agents)

VibeTags generates two files following the [llms.txt standard](https://llmstxt.org/) — a format that lets AI agents quickly discover and consume project rules without parsing messy HTML or bloating the context window.

| File | Role | Best for |
|---|---|---|
| `llms.txt` | **The Map** — concise directory, one bullet per rule | Windsurf Cascade, agents with limited context |
| `llms-full.txt` | **The Book** — fully expanded reference with all details | Claude 4.6, Gemini 1.5 Pro, Windsurf Cascade with large context |

Both files follow the standard hierarchy: `# ProjectName` (H1), `> summary blockquote`, informational text, and `## Section` resource groups.

**Opt in** by creating the files:

```bash
touch llms.txt llms-full.txt
mvn compile
```

**Sample `llms.txt` output:**

```markdown
# My Project

> AI guardrail rules generated from source annotations by VibeTags.

AI tools reading this file should respect the guardrails defined below.

## Locked Files
- [PaymentProcessor](com.example.payment.PaymentProcessor): Tied to legacy database schema v2.3

## Contextual Rules
- [StringParser](com.example.utils.StringParser): Focus — Optimize for memory usage. Avoid — java.util.regex, String.split()

## Security Audit Requirements
- [DatabaseConnector](com.example.database.DatabaseConnector): check for SQL Injection, Thread Safety issues

## Ignored Elements
- [GeneratedMetadata](com.example.internal.GeneratedMetadata): excluded from AI context
```

**Setting the project name:** Pass `-Avibetags.project=MyProjectName` to the compiler (Maven: `<compilerArg>`, Gradle: `annotationProcessorArgs`) to set the `# H1` title in both files. Defaults to `"This Project"`.

### ⚠️ Orphaned Annotation Warnings

If you use a VibeTags annotation (like `@AIIgnore`) but haven't created the recommended standalone file for an active service, the compiler will issue a **WARNING** to guide you:

`[WARNING] VibeTags: @AIIgnore used but .cursorignore is missing for Cursor support. Consider creating it.`

`[WARNING] VibeTags: @AIIgnore used but .qwenignore is missing for Qwen support. Consider creating it.`

`[WARNING] VibeTags: @AILocked used but .qwenignore is missing for Qwen support. Consider creating it.`

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

**Qwen (QWEN.md):**
```markdown
## 🛡️ MANDATORY SECURITY AUDITS
When proposing edits or writing code for the following files, you MUST perform a security review. Explicitly state that you have audited the changes for the listed vulnerabilities.

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

#### Redundant Privacy Annotations
If you use `@AIPrivacy` on an element that is already annotated with `@AIIgnore`, VibeTags will warn you — `@AIIgnore` already excludes the element from AI context entirely, making `@AIPrivacy` redundant:
`[WARNING] VibeTags: com.example.MyField is annotated with both @AIPrivacy and @AIIgnore. @AIIgnore already excludes the element from AI context; @AIPrivacy is redundant.`

#### Contradictory Contract Annotations
If you use `@AIContract` (signature frozen but logic can change) alongside `@AIDraft` (please implement this), VibeTags will warn you:
`[WARNING] VibeTags: com.example.PaymentGateway.charge is annotated with both @AIContract and @AIDraft. @AIContract freezes the signature, but @AIDraft implies the element is not yet implemented. Remove one of the two annotations.`

If you use `@AIContract` alongside `@AILocked` (no changes at all), VibeTags will warn about the overlapping intent:
`[WARNING] VibeTags: com.example.LegacyApi.process is annotated with both @AIContract and @AILocked. @AILocked prohibits all modifications; @AIContract permits internal-logic changes. Consider using only @AILocked if no changes at all are intended.`

#### Contradictory Test-Driven Annotations
If you use `@AITestDriven` on an element that is also annotated with `@AIIgnore`, VibeTags will warn you — an ignored element is excluded from AI context entirely, so test enforcement cannot apply:
`[WARNING] VibeTags: com.example.OrderService.processPayment is annotated with both @AITestDriven and @AIIgnore. @AIIgnore excludes the element from AI context entirely; @AITestDriven cannot enforce test coverage on an ignored element. Remove one of the two annotations.`

If you use `@AITestDriven` on an element that is also annotated with `@AILocked`, VibeTags will warn about the conflicting intent — `@AILocked` prohibits all changes while `@AITestDriven` permits changes when tests are provided:
`[WARNING] VibeTags: com.example.LegacyService.compute is annotated with both @AITestDriven and @AILocked. @AILocked prohibits all modifications; @AITestDriven permits changes only when tests are updated. Consider using only @AILocked if no changes at all are intended.`

#### Invalid Coverage Goal
If you use `@AITestDriven` with a `coverageGoal` outside the valid 0-100 range, VibeTags will warn you:
`[WARNING] VibeTags: @AITestDriven on com.example.Service.method has an invalid coverageGoal (150). Value must be between 0 and 100 (inclusive).`

#### @AILegacyBridge and @AIDraft Contradiction
Compatibility bridges should not be actively drafted or structurally modified:
`[WARNING] VibeTags: com.example.OldBridge is annotated with both @AILegacyBridge and @AIDraft; compatibility bridges should not be actively drafted or structurally modified.`

#### @AIPublicAPI and @AILocked Redundancy
Since `@AILocked` completely prohibits changes, public API checks are redundant:
`[WARNING] VibeTags: com.example.PublicController is annotated with both @AIPublicAPI and @AILocked; @AILocked already completely prohibits modifications, making public API rules redundant.`

#### @AIParallelTests and @AILocked Redundancy
Since `@AILocked` completely prohibits changes, parallel test rules are redundant:
`[WARNING] VibeTags: com.example.MyTest is annotated with both @AIParallelTests and @AILocked; @AILocked already completely prohibits modifications, making test-driven specifications redundant.`

#### @AISchemaSafe and @AIIgnore Redundancy
Since `@AIIgnore` completely excludes the class, DB schema rules are redundant:
`[WARNING] VibeTags: com.example.UserEntity is annotated with both @AISchemaSafe and @AIIgnore; ignore already completely excludes this element from AI context.`

#### @AIStrictClasspath and @AILocked Redundancy
Since `@AILocked` completely prohibits changes, classpath restriction checks are redundant:
`[WARNING] VibeTags: com.example.Utils is annotated with both @AIStrictClasspath and @AILocked; locked elements already completely prohibit changes.`

#### @AIArchitecture Configuration Check
Using `@AIArchitecture` with no configured layers is a no-op:
`[WARNING] VibeTags: com.example.DomainEntity is annotated with @AIArchitecture but has no configured 'belongsTo' or 'cannotReference' attributes.`

### 📜 @AIContract - Freezing Public API Signatures

The `@AIContract` annotation draws a hard line between **interface** and **implementation**. It tells AI assistants: *"You're welcome to change what happens inside this method — replace the algorithm, swap the data source, optimize the logic — but you must leave the method name, parameter types, parameter order, return type, and checked exceptions exactly as they are."*

#### Why it's needed

AI assistants often try to be helpful in ways that introduce breaking changes. When asked to optimize `calculateTax(double amount, String zipCode)`, an AI might decide `BigDecimal` is more appropriate than `double` and change the signature. For isolated private methods that's fine. For a method that's part of a public API contract shared with three other microservices, it's a deployment incident.

#### Rules of Engagement

When an element is annotated with `@AIContract`, the AI is instructed to respect:

1. **Signature Freeze** — method name, parameter types, parameter order, and return type are off-limits
2. **Exception Integrity** — no new checked exceptions that weren't already declared
3. **Behavioral Consistency** — if the contract says "returns a sorted list", the AI can change the sorting algorithm but cannot return an unsorted `Set`

#### Example Usage

```java
public class PricingService {

    /**
     * Signature pinned by OpenAPI v2 contract shared with checkout-service and mobile-app.
     * Internal pricing algorithm can be freely replaced (rule engine, ML model, lookup table).
     */
    @AIContract(reason = "Signature locked by OpenAPI v2 contract. checkout-service and mobile-app bind to this exact signature.")
    @AIPerformance(constraint = "Must complete in <5ms p99. Called on every cart update.")
    public double calculatePrice(String productId, int quantity, String customerId) {
        return 0.0; // AI can implement/optimize this freely
    }
}
```

**What the AI is ALLOWED to do:**
```java
// ✅ Internal logic replaced entirely — signature identical
public double calculatePrice(String productId, int quantity, String customerId) {
    PriceRule rule = ruleEngine.evaluate(productId, customerId);
    return rule.apply(quantity);
}
```

**What the AI is FORBIDDEN from doing:**
```java
// ❌ Changed double → BigDecimal and String → ProductId
public BigDecimal calculatePrice(ProductId productId, int quantity, CustomerId customerId) { ... }
```

#### Generated Output by Platform

**Cursor (.cursorrules):**
```markdown
## 🔐 CONTRACT-FROZEN SIGNATURES
The following elements have contract-frozen public signatures. You MAY change internal implementation logic, but MUST NOT modify method names, parameter types, parameter order, return types, or checked exceptions.

* `com.example.PricingService.calculatePrice` - Signature locked by OpenAPI v2 contract.
```

**Claude (CLAUDE.md):**
```xml
<contract_signatures>
  <element path="com.example.PricingService.calculatePrice">
    <reason>Signature locked by OpenAPI v2 contract.</reason>
  </element>
</contract_signatures>
<rule>You may refactor the internal logic of elements listed in <contract_signatures>, but you MUST NOT change their public signatures: method names, parameter types, parameter order, return types, or checked exceptions.</rule>
```

#### Best Use Cases

- **Public APIs / SDKs** — where external users depend on your method signatures
- **Microservice contracts** — where signatures are shared via OpenAPI specs or Protobuf definitions
- **Legacy bridges** — where modern code talks to older systems that expect exact data shapes
- **Framework-wired methods** — where Spring, Dagger, or another framework locates methods by exact signature

### 🧪 @AITestDriven - Test-Driven AI Requirements

The `@AITestDriven` annotation is the **accountability officer** of the VibeTags suite. It transforms the AI from a simple code generator into a disciplined engineer that follows a strict Red-Green-Refactor workflow.

When an AI tool scans a project and encounters this tag, it must treat the test suite as an immutable part of the "Definition of Done." Changes without tests are considered incomplete and must not be proposed.

#### How It Works: The Enforcement Loop

1. **Context Mapping** — The AI identifies the associated test class (via naming convention or explicit `testLocation`).
2. **Requirement Analysis** — Before implementing, the AI reads the existing tests to understand expected behavior.
3. **Synchronous Update** — New logic and new test cases are generated in a single pass. If the logic changes, the tests must reflect the new expected behavior.
4. **Regression Check** — The AI verifies its changes do not break existing tests; if they do, it must fix the logic or explain why the test contract needs to evolve.

#### Annotation Attributes

| Attribute | Type | Default | Description |
|---|---|---|---|
| `testLocation` | `String` | `""` | Explicit path to the test file when it doesn't follow standard naming (e.g. `src/test/integration/ProcTest.java`). Leave empty for convention-based resolution. |
| `coverageGoal` | `int` | `100` | Minimum statement-coverage percentage the AI must achieve in generated or updated tests. |
| `framework` | `Framework[]` | `{JUNIT_5}` | Testing frameworks the AI must use. Multiple values may be combined (e.g. `{JUNIT_5, MOCKITO}`). |
| `mockPolicy` | `String` | `""` | Instruction describing how external dependencies should be handled (e.g. `"Always mock external APIs"`, `"Use H2 for database tests"`). |

**Available frameworks:** `JUNIT_5`, `JUNIT_4`, `TESTNG`, `MOCKITO`, `ASSERTJ`, `SPOCK`, `NONE`

#### Example Usage

```java
public class OrderService {

    /**
     * Discount engine — the logic here evolves frequently as business rules change.
     * Every change MUST be accompanied by a full test update.
     */
    @AIDraft(instructions = "Implement discount calculation: percentage, fixed amount, BOGO, tiered.")
    @AITestDriven(
        coverageGoal = 100,
        framework = {AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.ASSERTJ},
        mockPolicy = "Use fixed prices — no external pricing calls in unit tests"
    )
    public double calculateDiscount(String orderId, String discountCode) {
        return 0.0; // AI implements this, but must also provide the tests
    }

    /**
     * Order status state machine — complex workflow that must stay fully tested.
     */
    @AITestDriven(
        coverageGoal = 95,
        framework = {AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.MOCKITO},
        testLocation = "src/test/java/com/example/service/OrderServiceTest.java",
        mockPolicy = "Mock OrderRepository and EventPublisher; use real state machine logic"
    )
    public String updateOrderStatus(String orderId, String newStatus) {
        return "CREATED";
    }
}
```

#### Generated Output by Platform

**Cursor (.cursorrules):**
```markdown
## 🧪 TEST-DRIVEN REQUIREMENTS
The following elements require a corresponding test update whenever their logic is modified.
AI MUST NOT propose changes to these elements without also providing the matching test code.

* `com.example.service.OrderService.calculateDiscount` - Coverage goal: 100%. Framework: JUNIT_5, ASSERTJ. Mock policy: Use fixed prices — no external pricing calls in unit tests.
* `com.example.service.OrderService.updateOrderStatus` - Coverage goal: 95%. Framework: JUNIT_5, MOCKITO. Test file: src/test/java/com/example/service/OrderServiceTest.java.
```

**Claude (CLAUDE.md):**
```xml
<test_driven_requirements>
  <element path="com.example.service.OrderService.calculateDiscount">
    <coverage_goal>100</coverage_goal>
    <frameworks>JUNIT_5, ASSERTJ</frameworks>
    <mock_policy>Use fixed prices — no external pricing calls in unit tests</mock_policy>
  </element>
  <element path="com.example.service.OrderService.updateOrderStatus">
    <coverage_goal>95</coverage_goal>
    <frameworks>JUNIT_5, MOCKITO</frameworks>
    <test_location>src/test/java/com/example/service/OrderServiceTest.java</test_location>
    <mock_policy>Mock OrderRepository and EventPublisher; use real state machine logic</mock_policy>
  </element>
</test_driven_requirements>
<rule>For any element listed in <test_driven_requirements>, you MUST provide both the implementation change AND the corresponding test code update in a single response. Changes without tests are incomplete and must not be proposed.</rule>
```

#### Compile-time Validation

`@AITestDriven` has three dedicated compile-time checks:

- **`@AITestDriven` + `@AIIgnore`** — contradictory. The element is excluded from AI context, so enforcing test coverage on it is impossible. Remove one of the two.
  `[WARNING] VibeTags: com.example.OrderService.processPayment is annotated with both @AITestDriven and @AIIgnore. @AIIgnore excludes the element from AI context entirely; @AITestDriven cannot enforce test coverage on an ignored element. Remove one of the two annotations.`

- **`@AITestDriven` + `@AILocked`** — contradictory. `@AILocked` prohibits all changes; `@AITestDriven` permits changes when tests are provided. Use only `@AILocked` if no changes are intended.
  `[WARNING] VibeTags: com.example.LegacyService.compute is annotated with both @AITestDriven and @AILocked. @AILocked prohibits all modifications; @AITestDriven permits changes only when tests are updated. Consider using only @AILocked if no changes at all are intended.`

- **`@AITestDriven` with invalid `coverageGoal`** — `coverageGoal` must be between 0 and 100 inclusive.
  `[WARNING] VibeTags: @AITestDriven on com.example.Service.method has an invalid coverageGoal (150). Value must be between 0 and 100 (inclusive).`

#### Best Use Cases

- **Business logic** — discount engines, pricing algorithms, order state machines that change often
- **Security-sensitive operations** — payment processing, authentication flows where regressions are costly
- **Draft implementations** — combine with `@AIDraft` to ensure the AI writes both the implementation and its tests in one shot
- **Core algorithms** — pair with `@AICore` when well-tested stability is critical
- **API surface evolution** — ensure any behavioral change is validated by tests before it reaches production

### 🆕 New in v0.9.8: Five Design-Intent Annotations

VibeTags v0.9.8 adds five annotations that capture *design intent* rather than process rules — they tell the AI what an element **is** so it cannot accidentally undo a property the team relies on.

#### 🧵 `@AIThreadSafe(strategy)`

Declares that the annotated class or method is explicitly designed to be thread-safe and names the strategy. Different from `@AIAudit` (which says "check for bugs") — this preserves a design invariant.

```java
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE,
              note = "Backed by ConcurrentHashMap; do not introduce a synchronized block on the cache map.")
public class SessionCache { ... }
```

Strategies: `SYNCHRONIZED`, `LOCK_FREE`, `IMMUTABLE`, `THREAD_LOCAL`, `OTHER`. Generated guidance: *"This class is explicitly designed as thread-safe via [strategy]. Any modification must preserve that guarantee and document its synchronization reasoning."*

#### ❄️ `@AIImmutable`

Declares a class immutable. Stronger than `@AIContext(avoids = "mutable state")` because it is a first-class intent. The processor warns at compile time when an `@AIImmutable` class declares a non-final, non-static instance field.

```java
@AIImmutable(note = "Used by every test runner; safe to share across threads.")
public final class AsyncTestConfig {
    private final int timeoutMs;
    public AsyncTestConfig(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
```

#### ⚠️ `@AIDeprecated(replacedBy, migrationGuide, deadline)`

Richer than Java's `@Deprecated`. Where `@AILocked` preserves an element, `@AIDeprecated` actively routes AI toward killing it — the AI is told to suggest migrating callers to `replacedBy` rather than extending the deprecated element.

```java
@AIDeprecated(
    replacedBy = "com.example.payment.PaymentProcessor",
    migrationGuide = "Switch callers to PaymentProcessor.charge(); the new API uses Money instead of double.",
    deadline = "v2.0 (2026-Q4)")
public class OldPaymentApi { ... }
```

Generated guidance: *"Do not extend this element. Suggest migration to [replacedBy] for any caller. Scheduled for removal in [deadline]."*

#### 📡 `@AIObservability(metrics, traces, logs)`

Marks code with instrumentation that downstream dashboards/alerts depend on. AI assistants often delete metric counters or trace spans when refactoring surrounding code; this annotation makes the cost explicit.

```java
@AIObservability(
    metrics = {"orders.placed.total", "orders.placed.failed"},
    traces  = {"order.place"},
    logs    = {"OrderPlaced", "OrderPlacementFailed"},
    note    = "Watched by the Orders SLO dashboard.")
public void recordOrderPlaced(String orderId, boolean success) { ... }
```

#### 📜 `@AIRegulation(standard, clause, description)`

Ties code to a specific compliance requirement. Stronger than `@AIAudit` because it names the exact article. Generated guidance: *"This element implements [standard] [clause]. Any change must document its compliance impact and must not weaken the requirement."*

```java
@AIRegulation(standard = "GDPR", clause = "Art. 17",
              description = "Right to erasure — deletes all PII for the given user.")
public void deleteAllUserData(String userId) { ... }
```

#### Validation warnings for the v0.9.8 annotations

- `@AIImmutable` on a type with a non-final, non-static instance field — violates the immutability declaration
- `@AIDeprecated` + `@AILocked` — contradictory (locked preserves; deprecated routes callers away)
- `@AIThreadSafe(IMMUTABLE)` + `@AIImmutable` — redundant; `@AIImmutable` already implies thread-safety
- `@AIObservability` with no metrics, traces, or logs — no-op; nothing to preserve
- `@AIRegulation` with a blank `standard` — required attribute missing

### 🛡️ New in v0.9.8 (continued): Nine Platform Guardrail Annotations

In addition to design-intent specifications, v0.9.8 introduces nine platform-wide guardrails to prevent AI tools from breaking test isolation, refactoring compatibility bridges, or violating architectural boundaries.

#### 🧪 `@AIParallelTests`

Mandates strict test isolation in test generation and execution. AI assistants must avoid shared mutable state, specific execution orders, or static/resource contention (ports, DB rows).

```java
@AIParallelTests
public class ParallelTestSettings {
    public static final int THREAD_COUNT = 4;
}
```

Generated guidance: *"AI-generated or modified tests for this element must remain strictly isolated. Shared mutable state, execution order dependencies, or resource conflicts are strictly prohibited."*

#### 🌉 `@AILegacyBridge`

Protects legacy compatibility/helper bridges from unnecessary modernization or refactoring. Instructs AI assistants that the class works around a quirk or bug in upstream dependencies and its structural patterns must be left as-is, modifying only internal business logic.

```java
@AILegacyBridge
public class LegacyBridgeService {
    public String adaptLegacyCall(String key, String value) {
        return "KEY=" + key + ";VAL=" + value;
    }
}
```

Generated guidance: *"This class is a legacy compatibility bridge. Do not modernize or refactor its structural pattern. Modify internal business logic only if explicitly requested."*

#### 🧱 `@AIArchitecture(belongsTo, cannotReference)`

Defines architectural boundaries and package layering rules. The processor checks and warns at compile time if an architectural boundary is crossed.

```java
@AIArchitecture(belongsTo = "domain", cannotReference = {"infrastructure", "ui"})
public class LayeredDomainService {
    public void processCoreDomainLogic() { }
}
```

Generated guidance: *"This class belongs to the '[belongsTo]' layer. Any modification or implementation MUST NOT import, reference, or depend on classes in forbidden layers: [cannotReference]."*

#### 🌐 `@AIPublicAPI`

Exposes a public API surface and demands complete backwards-compatibility.

```java
@AIPublicAPI
public class PublicPaymentController {
    public String executeExternalPayment(String paymentToken, double amount) {
        return "SUCCESS";
    }
}
```

Generated guidance: *"This element is a public API. Any change must be strictly additive. Do not remove, rename, or modify signatures, checked exceptions, or serialization formats."*

#### 🛡️ `@AIStrictExceptions`

Enforces precise, robust exception handling. AI assistants must not catch or throw generic exceptions like `Exception`, `Throwable`, or `RuntimeException`.

```java
@AIStrictExceptions
public class TransactionalPaymentService {
    public void processTransaction(String accountId, double amount) throws IllegalArgumentException {
        if (accountId == null) throw new IllegalArgumentException("Account ID required");
    }
}
```

Generated guidance: *"Exceptions thrown or caught must be highly specific. Swallow-all catch blocks, throwing generic Exceptions/Throwables, or losing root cause stack traces are strictly prohibited."*

#### 📐 `@AIStrictTypes`

Prohibits loose typing (like `Object`, generic maps, or raw types) where well-defined, strongly-typed domain entities should be used.

```java
@AIStrictTypes
public class PaymentDetails {
    private final String accountHolder;
    private final BigDecimal amount;

    public PaymentDetails(String accountHolder, BigDecimal amount) {
        this.accountHolder = accountHolder;
        this.amount = amount;
    }
}
```

Generated guidance: *"Avoid loose typing (e.g. Object, raw types, or generic Map<String, Object>). Use well-defined, strongly-typed domain models and high-precision types."*

#### 🗣️ `@AIInternationalized`

Prohibits hardcoding of user-facing strings or messages, mandating i18n bundle message keys.

```java
@AIInternationalized
public class I18nMessageHelper {
    private final ResourceBundle messages;
    public I18nMessageHelper(Locale locale) {
        this.messages = ResourceBundle.getBundle("messages", locale);
    }
}
```

Generated guidance: *"All user-visible strings, messages, labels, or errors must be resolved via localization resource bundles. Hardcoding strings is strictly prohibited."*

#### 📦 `@AIStrictClasspath`

Prevents dependency bloat by restricting imports to standard JDK and existing classpath libraries. Prohibits dynamic class loading or runtime reflection hacks.

```java
@AIStrictClasspath
public class StrictUtility {
    public static String computeSecureHash(String input) {
        return String.valueOf(input.hashCode());
    }
}
```

Generated guidance: *"Dependencies are strictly constrained. Do not introduce new third-party imports, dynamic class loading, custom classloaders, or reflection hacks."*

#### 🗄️ `@AISchemaSafe`

Protects database entities and persistent schemas. Forbids destructive changes, dropping tables/columns, or breaking serialization/DTO backward compatibility.

```java
@AISchemaSafe
public class UserEntity {
    private final String userId;
    private final String emailAddress;

    public UserEntity(String userId, String emailAddress) {
        this.userId = userId;
        this.emailAddress = emailAddress;
    }
}
```

Generated guidance: *"Guarantees schema and serialization safety. Destructive modifications (dropping columns/tables, changing field names, or breaking serialization schemas) are strictly prohibited."*

#### ♻️ `@AIIdempotent`

Declares that the annotated method or type guarantees idempotency — calling it multiple times must produce the same result as calling it once. AI assistants must never introduce side effects (such as unconditional inserts or non-idempotent state mutations) that would break this guarantee.

```java
public class GdprService {
    @AIIdempotent(reason = "Deleting a user's data multiple times must produce the same result — must not throw on second invocation.")
    public void deleteAllUserData(String userId) {
        // idempotent delete — safe to re-call
    }
}
```

Generated guidance: *"Idempotency guaranteed. Multiple invocations must produce the same result as one. Never introduce side effects that cause repeated invocations to produce different results."*

#### 🚩 `@AIFeatureFlag(flag, defaultValue)`

Marks code gated behind a feature flag. AI assistants must preserve the flag check and never assume the flag is always active when refactoring.

```java
@AIFeatureFlag(flag = "checkout.new-payment-flow", defaultValue = false)
public void processWithNewFlow(Order order) { ... }
```

Generated guidance: *"This element is gated behind a feature flag. Do not assume the flag is always active. Preserve the flag check."*

#### 🔐 `@AISecure(aspect)`

Marks security-critical code. AI must never weaken security properties (authentication, encryption, input validation, …) and must flag every change for security review.

```java
@AISecure(aspect = "authentication")
public boolean verifyToken(String jwt) { ... }
```

Generated guidance: *"Security-critical. AI must not weaken security properties. Any change must be reviewed for security impact."*

### 🆕 New in v0.9.9: Twelve Precision Guardrail Annotations

v0.9.9 extends the set to 39 annotations with element-precise guardrails — including the first two annotations that target **method parameters** (`@AIInputSanitized`, `@AISecureLogging`).

#### 🚫 `@AICallersOnly(value)`

Restricts who may invoke the element. AI must not introduce calls from outside the allowed callers.

```java
@AICallersOnly({"com.example.billing.InvoiceService", "com.example.billing.RefundService"})
public void postLedgerEntry(LedgerEntry entry) { ... }
```

#### 🛡️ `@AISandboxOnly`

Marks sandbox/test-harness code. Production classes must never import or reference it.

#### ⚡ `@AIMemoryBudget(value)`

Declares a strict allocation budget (`ZERO_ALLOCATION`, `NO_AUTOBOXING`, …) on hot-path code. AI must optimize allocations and never introduce per-call garbage.

```java
@AIMemoryBudget(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION)
public long checksum(byte[] frame) { ... }
```

#### 🧠 `@AIPure`

The method must remain a pure function: no side effects, no state mutation, deterministic for the same inputs.

#### 🧱 `@AIDomainModel(allow)`

A framework-free domain entity. AI must not import Spring, JPA/Hibernate, Jackson, or other framework packages; explicit exceptions go in `allow`.

#### ❄️ `@AIExtensible(value)`

The type is extended via the declared polymorphic pattern (`STRATEGY_PATTERN`, `VISITOR_PATTERN`, …). AI must implement extensions polymorphically, never by appending branch conditionals.

#### 🚨 `@AIInputSanitized(value)` — *parameter/field*

The annotated parameter or field must pass approved sanitizers (`SQL_INJECTION`, `XSS`, `PATH_TRAVERSAL`, …) before reaching queries or renderers.

```java
public void exportKeys(
        @AIInputSanitized({AIInputSanitized.SanitizerType.PATH_TRAVERSAL}) String filePath) { ... }
```

Parameter entries are rendered with their full element path, e.g. `com.example.KeyStore.exportKeys(java.lang.String)#filePath`.

#### 🔒 `@AISecureLogging(value)` — *field/parameter*

A sensitive value that must never reach logs or stdout unprotected. AI must enforce the masking policy (`OMIT`, `HASH`, `MASK_CREDIT_CARD`, …) in any logging it writes.

```java
public void registerUserSession(
        @AISecureLogging(AISecureLogging.MaskingPolicy.HASH) String passwordRaw) { ... }
```

#### 📋 `@AIExplain(value)`

Any change requires a step-by-step proof of correctness in the PR/walkthrough, scaled to the declared complexity level (`HIGH`, `MEDIUM`, …).

#### 🛠️ `@AIPrototype`

An experimental stub: QA and test constraints are relaxed, but production classes must never import it.

#### 🌅 `@AISunset(jira, replacement)`

Strict deprecation tied to a JIRA ticket. Introducing *new* references is forbidden; AI routes callers to `replacement`.

```java
@AISunset(jira = "PAY-1234", replacement = PaymentProcessorV2.class)
public class LegacyPaymentProcessor { ... }
```

#### 🚧 `@AITemporary(expiresOn, reason)`

A hotfix or stub with an expiration date. The processor warns at compile time when `expiresOn` (`YYYY-MM-DD`) has passed.

```java
@AITemporary(expiresOn = "2026-09-01", reason = "Stub until the upstream rate-limit fix ships.")
public Response retryWithBackoffHack(Request req) { ... }
```

#### Validation warnings for the v0.9.9 annotations

- `@AISunset` + `@AIDraft` — contradictory (sunset elements must not be actively drafted or expanded)
- `@AISunset` with a blank `jira` — required attribute missing
- `@AITemporary` with a blank or unparseable `expiresOn` date — invalid value
- `@AITemporary` whose `expiresOn` date has passed — expired workaround still in the codebase

