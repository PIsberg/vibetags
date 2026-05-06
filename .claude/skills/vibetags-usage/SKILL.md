---
name: vibetags-usage
description: This skill should be used when the user asks how to "use VibeTags", "add VibeTags annotations", "set up AI guardrails", "protect code from AI", "configure AI platforms", asks about @AILocked, @AIContext, @AIDraft, @AIAudit, @AIIgnore, @AIPrivacy, @AICore, @AIPerformance, @AIContract, @AITestDriven annotations, or wants to control how AI tools interact with Java code.
version: 0.8.0
---

# VibeTags Usage Guide

VibeTags is a **compile-time Java annotation processor** that generates AI platform-specific guardrail files from source annotations. Zero runtime overhead — all annotations have `RetentionPolicy.SOURCE`.

## Quick Setup

### 1. Add the dependency

**Maven** (`provided` scope — compile-only):
```xml
<dependency>
    <groupId>se.deversity.vibetags</groupId>
    <artifactId>vibetags-processor</artifactId>
    <version>0.7.1</version>
    <scope>provided</scope>
</dependency>
```

**Gradle:**
```groovy
compileOnly 'se.deversity.vibetags:vibetags-processor:0.7.1'
annotationProcessor 'se.deversity.vibetags:vibetags-processor:0.7.1'
```

### 2. Opt in to AI platforms (file-presence model)

VibeTags **never creates files** — it only updates files that already exist. Create empty placeholder files for each platform you want to support:

```bash
touch CLAUDE.md .claudeignore              # Claude / Claude Code
touch .cursorrules .cursorignore           # Cursor (traditional)
mkdir -p .cursor/rules                     # Cursor (granular per-class rules)
mkdir -p .trae/rules                       # Trae (granular per-class rules)
mkdir -p .roo/rules                        # Roo Code (per-class rules)
touch CONVENTIONS.md .aiderignore          # Aider
touch QWEN.md .qwenignore                  # Qwen
touch .aiexclude gemini_instructions.md    # Gemini
touch AGENTS.md                            # Codex CLI
mkdir -p .github && touch .github/copilot-instructions.md .copilotignore  # Copilot
touch llms.txt llms-full.txt               # Windsurf Cascade / llms.txt standard
touch .windsurfrules                       # Windsurf IDE (traditional)
mkdir -p .windsurf/rules                   # Windsurf IDE (granular per-class rules)
touch .rules                               # Zed Editor
mkdir -p .cody && touch .cody/config.json .codyignore  # Sourcegraph Cody
touch .supermavenignore                    # Supermaven
mkdir -p .continue/rules                   # Continue (granular per-class rules)
mkdir -p .tabnine/guidelines               # Tabnine (granular per-class rules)
mkdir -p .amazonq/rules                    # Amazon Q (granular per-class rules)
mkdir -p .ai/rules                         # Universal AI standard (granular)
mkdir -p .pearai/rules                     # PearAI (granular per-class rules)
touch .mentatconfig.json                   # Mentat
touch sweep.yaml                           # Sweep (GitHub App)
touch .plandex.yaml                        # Plandex
touch .doubleignore                        # Double.bot
mkdir -p .interpreter/profiles && touch .interpreter/profiles/vibetags.yaml  # Open Interpreter
touch .codeiumignore                       # Codeium
```

To remove a platform: delete its file — VibeTags will never recreate it.

### 3. Annotate your Java code

```java
import se.deversity.vibetags.annotations.*;
```

### 4. Compile — guardrails are generated automatically

```bash
mvn clean compile   # or: gradle clean build
```

---

## Annotations Reference

### `@AILocked` — Protect critical code from modification

Use on: **class, method, field**

```java
@AILocked(reason = "Tied to legacy database schema v2.3. Any change breaks production payment flow.")
public interface PaymentProcessor {
    String processPayment(double amount, String currency, String merchantId);
}
```

When to use: legacy integrations, compliance-regulated code (PCI-DSS, HIPAA), algorithms that took months to stabilize.

---

### `@AIContext` — Guide AI behavior for a class or method

Use on: **class, method**

```java
@AIContext(
    focus = "Optimize for memory usage over CPU speed",
    avoids = "java.util.regex, String.split(), StringBuilder in loops"
)
public class StringParser { ... }
```

Use `focus` to tell AI what to optimize for; use `avoids` to list libraries, patterns, or constructs it should not introduce.

---

### `@AIDraft` — Request an AI implementation

Use on: **class, method**

```java
@AIDraft(instructions = "Implement email sending via SMTP and push notifications via FCM. Include retry logic and rate limiting.")
public class NotificationService {
    public void sendNotification(String userId, String message) {
        // AI implements this
    }
}
```

Tip: `@AIDraft` and `@AILocked` on the same element produce a compile-time warning — they are contradictory.

---

### `@AIAudit` — Require continuous security auditing

Use on: **class, method**

```java
@AIAudit(checkFor = {"SQL Injection", "Thread Safety issues", "Path Traversal"})
public class DatabaseConnector { ... }
```

Every time an AI tool modifies tagged code it must explicitly state it audited the changes for each listed vulnerability.

Common values for `checkFor`: `"SQL Injection"`, `"XSS"`, `"CSRF"`, `"Command Injection"`, `"Thread Safety issues"`, `"Insecure Deserialization"`, `"Authentication Bypass"`.

Empty `checkFor` array produces a compile-time warning and is ignored.

---

### `@AIIgnore` — Exclude from AI context entirely

Use on: **class, method, field**

```java
@AIIgnore(reason = "Auto-generated at build time. Manual edits are overwritten on every build.")
public class GeneratedMetadata { ... }
```

Unlike `@AILocked` (visible but immutable), `@AIIgnore` tells AI to treat the element as if it does not exist. Use for: generated code, deprecated scaffolding, internal plumbing.

---

### `@AIPrivacy` — Protect PII fields and methods

Use on: **class, method, field**

```java
public class UserRepository {
    @AIPrivacy(reason = "GDPR - never log or include in error messages, test fixtures, or mock data")
    private final String email;

    @AIPrivacy(reason = "PCI-DSS - must not appear in logs, console output, or external API calls")
    private final String creditCardToken;
}
```

AI remains aware the element exists (for code assistance) but must never reproduce its runtime values in logs, suggestions, test fixtures, mock data, or external API calls.

Using `@AIPrivacy` together with `@AIIgnore` on the same element produces a compile-time warning (redundant — `@AIIgnore` already excludes the element).

---

### `@AICore` — Mark sensitive core logic

Use on: **class, method, field**

```java
@AICore(
    sensitivity = "Critical",
    note = "Core transaction engine. Well-tested. Changes require user approval."
)
public class TransactionEngine { ... }
```

Use `sensitivity` (default `"High"`) to indicate impact level, and `note` to provide specific warnings. AI will treat changes with extreme caution and must not refactor without explicit approval.

---

### `@AIPerformance` — Enforce complexity constraints

Use on: **class, method**

```java
@AIPerformance(
    constraint = "Must maintain O(1) time complexity. No heap allocations."
)
public class FastBuffer { ... }
```

Informs AI that logic is on a hot-path and suboptimal complexity is unacceptable. AI must reason about time and space complexity before proposing changes.

---

### `@AIContract` — Freeze a public API signature

Use on: **class, method**

```java
@AIContract(reason = "Signature locked by OpenAPI v2 contract. checkout-service and mobile-app bind to this exact signature. A type change is a breaking API change.")
@AIPerformance(constraint = "Must complete in <5ms p99. Called on every cart update.")
public double calculatePrice(String productId, int quantity, String customerId) {
    // Internal logic may be freely changed
}
```

Tells AI: the method name, parameter types, parameter order, return type, and checked exceptions are **frozen**. Internal logic may be refactored freely. Use when:

- The method signature is pinned by an OpenAPI / AsyncAPI contract
- Other services bind to it via generated clients or message schemas
- Changing the signature requires a major-version bump and migration coordination

Unlike `@AILocked` (which prohibits all changes), `@AIContract` explicitly invites AI to improve internal logic — it only protects the public surface.

**Compile-time warnings:**

- `@AIContract` + `@AIDraft` on the same element — contradictory (signature is frozen, but `@AIDraft` implies the element still needs implementing)
- `@AIContract` + `@AILocked` on the same element — overlapping intent (`@AILocked` already prohibits all changes; consider using only `@AILocked` if no changes at all are intended)

---

### `@AITestDriven` — Enforce a test-driven workflow

Use on: **class, method**

```java
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5, AITestDriven.Framework.MOCKITO},
    coverageGoal = 90,
    mockPolicy = "Always mock external APIs and database calls",
    testLocation = "src/test/java/com/example/OrderServiceTest.java"
)
public class OrderService { ... }
```

Enforces a strict Red-Green-Refactor workflow: AI **must** include the corresponding test code in the same response as any proposed change. A change without matching tests is treated as incomplete.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `framework` | `Framework[]` | `{JUNIT_5}` | Testing frameworks the AI must use. Combine freely (e.g., `{JUNIT_5, MOCKITO}`). Options: `JUNIT_5`, `JUNIT_4`, `TESTNG`, `MOCKITO`, `ASSERTJ`, `SPOCK`, `NONE` |
| `coverageGoal` | `int` | `100` | Minimum statement-coverage % the AI must achieve in the generated or updated tests (0–100) |
| `testLocation` | `String` | `""` | Explicit path to the corresponding test file. Leave empty to let the AI infer the test class by naming convention |
| `mockPolicy` | `String` | `""` | Instruction describing how external dependencies should be handled in tests |

**Compile-time warnings:**

- `@AITestDriven` + `@AIIgnore` on the same element — contradictory (`@AIIgnore` excludes the element from AI context entirely; `@AITestDriven` cannot enforce test coverage on an ignored element)
- `@AITestDriven` + `@AILocked` on the same element — contradictory (`@AILocked` prohibits all modifications; `@AITestDriven` permits changes only when tests are updated — consider using only `@AILocked` if no changes at all are intended)
- `@AITestDriven` with `coverageGoal` outside 0–100 — invalid value

---

## Annotation Combinations

| Combination | Result |
|---|---|
| `@AIContext` + `@AIAudit` | Guide implementation AND enforce security checks |
| `@AIDraft` + `@AIContext` | Request implementation with style constraints |
| `@AIPrivacy` (field) + `@AIContext` (class) | Class-level guidance with PII fields protected |
| `@AICore` + `@AIPerformance` | Hot-path core logic with strict complexity rules |
| `@AIContract` + `@AIPerformance` | Contract-frozen signature with performance budget |
| `@AIContract` + `@AIContext` | Frozen signature with guidance on internal implementation |
| `@AILocked` + `@AIDraft` | **Warning**: contradictory — don't combine |
| `@AIIgnore` + `@AIPrivacy` | **Warning**: redundant — `@AIIgnore` already excludes |
| `@AIContract` + `@AIDraft` | **Warning**: contradictory — frozen signature can't need drafting |
| `@AIContract` + `@AILocked` | **Warning**: overlapping intent — consider using only `@AILocked` |
| `@AITestDriven` + `@AIContext` | Enforce TDD workflow AND guide implementation style |
| `@AITestDriven` + `@AIPerformance` | Any change must include tests AND meet complexity constraints |
| `@AITestDriven` + `@AIIgnore` | **Warning**: contradictory — `@AIIgnore` excludes element from AI context |
| `@AITestDriven` + `@AILocked` | **Warning**: contradictory — `@AILocked` prohibits all changes |

---

## Granular Rules

When the granular rule directories exist, VibeTags generates **one rule file per annotated class** instead of a single monolithic config file. Each rule file is automatically scoped to its class (e.g., `**/OrderService.java`). Orphaned files for classes that lose their annotations are cleaned up automatically.

| Directory | Platform | Format |
|---|---|---|
| `.cursor/rules/*.mdc` | Cursor | YAML front-matter + Markdown |
| `.windsurf/rules/*.md` | Windsurf IDE | YAML front-matter + Markdown |
| `.trae/rules/*.md` | Trae IDE | YAML front-matter + Markdown |
| `.roo/rules/*.md` | Roo Code | Markdown |
| `.continue/rules/*.md` | Continue | YAML front-matter + Markdown |
| `.tabnine/guidelines/*.md` | Tabnine | Markdown |
| `.amazonq/rules/*.md` | Amazon Q | Markdown |
| `.ai/rules/*.md` | Universal AI standard | Markdown |
| `.pearai/rules/*.md` | PearAI | YAML front-matter + Markdown |

Enable by creating the directories:
```bash
mkdir -p .cursor/rules .windsurf/rules .trae/rules .roo/rules
mkdir -p .continue/rules .tabnine/guidelines .amazonq/rules .ai/rules .pearai/rules
```

---

## Advanced Configuration

### Processor options (Maven)

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <!-- Set project name in llms.txt / llms-full.txt H1 -->
            <arg>-Avibetags.project=MyProjectName</arg>
            <!-- Custom log path (relative to project root or absolute) -->
            <arg>-Avibetags.log.path=logs/vibetags.log</arg>
            <!-- Log level: TRACE, DEBUG, INFO, WARN, ERROR, OFF -->
            <arg>-Avibetags.log.level=DEBUG</arg>
            <!-- Override output root directory -->
            <arg>-Avibetags.root=/path/to/output</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### Processor options (Gradle)

```groovy
tasks.withType(JavaCompile) {
    options.compilerArgs += [
        '-Avibetags.project=MyProjectName',
        '-Avibetags.log.path=logs/vibetags.log',
        '-Avibetags.log.level=DEBUG'
    ]
}
```

---

## Diagnosing Issues

| Symptom | Cause | Fix |
|---|---|---|
| No files updated after compile | Target files don't exist | `touch CLAUDE.md` (or whichever platform file) then recompile |
| `[NOTE] No AI config files found` | No opt-in files present | Create one or more platform files (see step 2) |
| `[WARNING] @AIIgnore used but .cursorignore is missing` | Orphaned annotation | Create the missing file to fully support that platform |
| `[WARNING] contradictory @AIDraft and @AILocked` | Both annotations on same element | Remove one of them |
| `[WARNING] @AIAudit has no checkFor items` | Empty `checkFor` array | Add at least one vulnerability string |
| `[WARNING] contradictory @AIContract and @AIDraft` | Both annotations on same element | Remove one — a frozen signature can't also need drafting |
| `[WARNING] overlapping @AIContract and @AILocked` | Both annotations on same element | Use only `@AILocked` if no changes at all are intended |
| `[WARNING] contradictory @AITestDriven and @AIIgnore` | Both annotations on same element | Remove one — `@AIIgnore` excludes the element entirely |
| `[WARNING] contradictory @AITestDriven and @AILocked` | Both annotations on same element | Remove one — `@AILocked` prohibits all changes |
| `[WARNING] @AITestDriven has invalid coverageGoal` | `coverageGoal` outside 0–100 | Set a value between 0 and 100 (inclusive) |

---

## Supported Output Files

| File(s) | Platform |
|---|---|
| `CLAUDE.md`, `.claudeignore` | Claude / Claude Code |
| `.cursorrules`, `.cursorignore` | Cursor (traditional) |
| `.cursor/rules/*.mdc` | Cursor (granular per-class rules) |
| `.windsurfrules` | Windsurf IDE (traditional) |
| `.windsurf/rules/*.md` | Windsurf IDE (granular per-class rules) |
| `.trae/rules/*.md` | Trae IDE (granular per-class rules) |
| `.roo/rules/*.md` | Roo Code |
| `CONVENTIONS.md`, `.aiderignore` | Aider |
| `QWEN.md`, `.qwen/settings.json`, `.qwen/commands/refactor.md`, `.qwenignore` | Qwen |
| `gemini_instructions.md`, `.aiexclude` | Gemini |
| `AGENTS.md`, `.codex/config.toml`, `.codex/rules/` | Codex CLI |
| `.github/copilot-instructions.md`, `.copilotignore` | GitHub Copilot |
| `.rules` | Zed Editor |
| `.cody/config.json`, `.codyignore` | Sourcegraph Cody |
| `.supermavenignore` | Supermaven |
| `.continue/rules/*.md` | Continue (granular per-class rules) |
| `.tabnine/guidelines/*.md` | Tabnine (granular per-class rules) |
| `.amazonq/rules/*.md` | Amazon Q (granular per-class rules) |
| `.ai/rules/*.md` | Universal AI standard (granular) |
| `llms.txt` | Windsurf Cascade / all LLM agents |
| `llms-full.txt` | Large-context LLMs (Claude, Gemini) |
| `.pearai/rules/*.md` | PearAI (granular per-class rules) |
| `.mentatconfig.json` | Mentat |
| `sweep.yaml` | Sweep (GitHub App) |
| `.plandex.yaml` | Plandex |
| `.doubleignore` | Double.bot |
| `.interpreter/profiles/vibetags.yaml` | Open Interpreter |
| `.codeiumignore` | Codeium |
