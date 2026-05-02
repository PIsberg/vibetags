---
name: vibetags-usage
description: This skill should be used when the user asks how to "use VibeTags", "add VibeTags annotations", "set up AI guardrails", "protect code from AI", "configure AI platforms", asks about @AILocked, @AIContext, @AIDraft, @AIAudit, @AIIgnore, @AIPrivacy, @AICore, @AIPerformance annotations, or wants to control how AI tools interact with Java code.
version: 1.1.0
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
    <version>0.5.5</version>
    <scope>provided</scope>
</dependency>
```

**Gradle:**
```groovy
compileOnly 'se.deversity.vibetags:vibetags-processor:0.5.5'
annotationProcessor 'se.deversity.vibetags:vibetags-processor:0.5.5'
```

### 2. Opt in to AI platforms (file-presence model)

VibeTags **never creates files** — it only updates files that already exist. Create empty placeholder files for each platform you want to support:

```bash
touch CLAUDE.md .claudeignore              # Claude / Claude Code
touch .cursorrules .cursorignore           # Cursor (traditional)
mkdir -p .cursor/rules                     # Cursor (granular per-class rules)
mkdir -p .trae/rules                       # Trae (granular per-class rules)
mkdir -p .roo/rules                        # Roo Code (per-class rules)
touch CONVENTIONS.md .aiderignore         # Aider
touch QWEN.md .qwenignore                  # Qwen
touch .aiexclude gemini_instructions.md    # Gemini
touch AGENTS.md                            # Codex CLI
mkdir -p .github && touch .github/copilot-instructions.md .copilotignore  # Copilot
touch llms.txt llms-full.txt               # Windsurf Cascade / llms.txt standard
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

## Annotation Combinations

| Combination | Result |
|---|---|
| `@AIContext` + `@AIAudit` | Guide implementation AND enforce security checks |
| `@AIDraft` + `@AIContext` | Request implementation with style constraints |
| `@AIPrivacy` (field) + `@AIContext` (class) | Class-level guidance with PII fields protected |
| `@AICore` + `@AIPerformance` | Hot-path core logic with strict complexity rules |
| `@AILocked` + `@AIDraft` | **Warning**: contradictory — don't combine |
| `@AIIgnore` + `@AIPrivacy` | **Warning**: redundant — `@AIIgnore` already excludes |

---

## Granular Rules (Cursor, Trae, Roo Code)

When the granular rule directories exist, VibeTags generates **one rule file per annotated class** instead of a single monolithic config file.

- **Cursor** (`.cursor/rules/*.mdc`) — YAML front-matter with `globs` scoping
- **Trae** (`.trae/rules/*.md`) — YAML front-matter with `globs` scoping
- **Roo Code** (`.roo/rules/*.md`) — plain Markdown

Each rule file is automatically scoped to its class (e.g., `**/OrderService.java`). Orphaned files for classes that lose their annotations are cleaned up automatically after new files are written.

Enable by creating the directories:
```bash
mkdir -p .cursor/rules
mkdir -p .trae/rules
mkdir -p .roo/rules
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

---

## Supported Output Files

| File(s) | Platform |
|---|---|
| `CLAUDE.md`, `.claudeignore` | Claude / Claude Code |
| `.cursorrules`, `.cursorignore` | Cursor (traditional) |
| `.cursor/rules/*.mdc` | Cursor (granular per-class rules) |
| `.trae/rules/*.md` | Trae (granular per-class rules) |
| `.roo/rules/*.md` | Roo Code |
| `CONVENTIONS.md`, `.aiderignore` | Aider |
| `QWEN.md`, `.qwen/settings.json`, `.qwen/commands/refactor.md`, `.qwenignore` | Qwen |
| `gemini_instructions.md`, `.aiexclude` | Gemini |
| `AGENTS.md`, `.codex/config.toml`, `.codex/rules/` | Codex CLI |
| `.github/copilot-instructions.md`, `.copilotignore` | GitHub Copilot |
| `llms.txt` | Windsurf Cascade / all LLM agents |
| `llms-full.txt` | Large-context LLMs (Claude, Gemini) |
