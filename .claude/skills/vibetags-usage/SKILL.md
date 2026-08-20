---
name: vibetags-usage
description: This skill should be used when the user asks how to "use VibeTags", "add VibeTags annotations", "set up AI guardrails", "protect code from AI", "configure AI platforms", asks about @AILocked, @AIContext, @AIDraft, @AIAudit, @AIIgnore, @AIPrivacy, @AICore, @AIPerformance, @AIContract, @AITestDriven, @AIThreadSafe, @AIImmutable, @AIDeprecated, @AIObservability, @AIRegulation, @AIArchitecture, @AILegacyBridge, @AIStrictClasspath, @AIInternationalized, @AIPublicAPI, @AISchemaSafe, @AIStrictExceptions, @AIStrictTypes, @AIParallelTests, @AIIdempotent, @AIFeatureFlag, @AISecure, @AICallersOnly, @AISandboxOnly, @AIMemoryBudget, @AIPure, @AIDomainModel, @AIExtensible, @AIInputSanitized, @AISecureLogging, @AIExplain, @AIPrototype, @AISunset, @AITemporary, @AIGenerated, @AILoadBearing, @AIBannedApi, @AIThreadAffinity, @AIKeepInSync annotations, or wants to control how AI tools interact with Java code.
version: 1.2.4
---

# VibeTags Usage Guide

VibeTags is a **compile-time Java annotation processor** that generates AI platform-specific guardrail files from source annotations. Zero runtime overhead — all annotations have `RetentionPolicy.SOURCE`.

## Quick Setup

### 1. Add the two artifacts

VibeTags ships as **two** artifacts and you need both. Depending on only one of them is the most
common "VibeTags is broken" report, and neither failure produces a useful error:

| Artifact | Belongs on | Gives you | If you omit it |
|---|---|---|---|
| `vibetags-annotations` | the **compile** classpath | the 44 `@AI*` annotation types you write in source | `cannot find symbol` on every `@AI*` |
| `vibetags-processor` | the **annotation-processor** path | the processor that reads them and writes the guardrail files | compiles green, generates nothing |

**Maven**. Annotations as an ordinary dependency, processor on `annotationProcessorPaths`:

```xml
<dependencies>
    <dependency>
        <groupId>se.deversity.vibetags</groupId>
        <artifactId>vibetags-annotations</artifactId>
        <version>1.2.4</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>se.deversity.vibetags</groupId>
                        <artifactId>vibetags-processor</artifactId>
                        <version>1.2.4</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

> **Do not** declare `vibetags-processor` as a plain `<scope>provided</scope>` dependency instead
> of the block above. Since **JDK 23**, `javac` no longer discovers processors sitting on the class
> path, so that shape compiles cleanly and writes no files: no error, no warning. If the processor
> has to stay on the class path, add `<proc>full</proc>` to the compiler plugin's configuration.

`vibetags-processor` does pull `vibetags-annotations` in transitively (kept from 0.5.x for
backwards compatibility), so a single-artifact setup can work, but only until JDK 23, and it puts
the whole processor and its SLF4J/Logback dependencies on your compile classpath. Declare both.

**Gradle:**

```groovy
dependencies {
    compileOnly         'se.deversity.vibetags:vibetags-annotations:1.2.4'
    annotationProcessor 'se.deversity.vibetags:vibetags-processor:1.2.4'
}
```

Kotlin replaces `annotationProcessor` with `kapt` (KSP does not run JSR 269 processors, so it is
not supported); Groovy needs the same two lines plus `groovyOptions.javaAnnotationProcessing = true`
on the `GroovyCompile` task. Scala has no JSR 269 support at all, so annotate thin Java types beside
the Scala code instead.

### 2. Tell the processor where the project root is

VibeTags writes at **the JVM's working directory** unless `-Avibetags.root` overrides it. For
`mvn compile` run from the project root that is already correct and you can skip this step. When
the compiler runs somewhere else, the processor happily writes a full set of guardrail files into
a directory you never look at, and your project looks untouched:

| How you build | Working directory | Set `-Avibetags.root`? |
|---|---|---|
| Maven, from the project root | the project root | No |
| Maven reactor, `mvn` at the reactor root | the reactor root | No; that is the merge root already |
| A single module on its own (`mvn -pl`, IDE "build module") | varies | Yes; point it at the reactor root |
| Gradle, plain `JavaCompile` | usually the root project dir | Usually no |
| Gradle worker, kapt, Groovy joint compilation | a worker scratch dir | **Yes** |
| IDE-driven compilation (IntelliJ, Eclipse) | varies | Usually yes |

```xml
<!-- Maven: inside the same maven-compiler-plugin <configuration> as step 1 -->
<compilerArgs><arg>-Avibetags.root=${maven.multiModuleProjectDirectory}</arg></compilerArgs>
```

```groovy
// Gradle
options.compilerArgs += "-Avibetags.root=${rootDir}".toString()
```

```kotlin
// kapt. Its working directory is never the project directory.
kapt { arguments { arg("vibetags.root", rootProject.projectDir.absolutePath) } }
```

You do not have to guess: the processor prints the path it resolved on every compile.

```
VibeTags: Root resolved: /home/me/myproject
VibeTags: user.dir:      /home/me/myproject
```

If that first line is not your project root, that is the whole bug.

### 3. Opt in to AI platforms (file-presence model)

VibeTags **never creates files** — it only updates files that already exist. Create empty placeholder files for each platform you want to support:

```bash
touch CLAUDE.md .claudeignore              # Claude / Claude Code
touch CLAUDE.local.md                      # Claude Code (local override)
mkdir -p .claude/rules                     # Claude Code (granular per-class rules)
mkdir -p .claude/skills/vibetags-guardrails && touch .claude/skills/vibetags-guardrails/SKILL.md  # Claude Code (Skill)
touch .cursorrules .cursorignore           # Cursor (traditional)
mkdir -p .cursor/rules                     # Cursor (granular per-class rules)
mkdir -p .trae/rules                       # Trae (granular per-class rules)
mkdir -p .roo/rules                        # Roo Code (per-class rules)
touch CONVENTIONS.md .aiderignore          # Aider
touch QWEN.md .qwenignore                  # Qwen
touch .aiexclude gemini_instructions.md GEMINI.md  # Gemini
touch AGENTS.md                            # Codex CLI (see note below — only generated when sole)
mkdir -p .github && touch .github/copilot-instructions.md .copilotignore  # Copilot
mkdir -p .github/instructions               # GitHub Copilot (granular per-class rules)
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
touch GEMINI.md                            # Gemini (official markdown)
touch .antigravityignore                   # Antigravity AI
touch .clinerules                          # Cline AI assistant
mkdir -p .junie && touch .junie/guidelines.md  # JetBrains Junie
mkdir -p .kiro/steering                    # Amazon Kiro (granular per-class rules)
touch DESIGN.md                            # AI design agents (Cursor, Claude, Copilot, etc.)
touch .coderabbit.yaml .pr_agent.toml ellipsis.yaml  # AI PR reviewers (CodeRabbit, PR-Agent, Ellipsis)
touch .repomixignore .gitingestignore .gptignore .ghostcoderignore .piecesignore  # Context packers
mkdir -p .void && touch .void/rules.md     # Void Editor
touch .roomodes                            # Roo Code ("VibeTags Architect" custom mode)
```

To remove a platform: delete its file — VibeTags will never recreate it.

> **`AGENTS.md` is special, and if you see this on every single compile, this is why:**
>
> ```
> VibeTags: AGENTS.md left untouched because other AI config files are present;
> it is treated as a pointer rather than a generated file.
> ```
>
> `AGENTS.md` is a near-universal agent file that projects often keep as a thin pointer to another
> tool's file (`CLAUDE.md`, say), so VibeTags refuses to touch it whenever any *other* AI config
> file exists. That also disables the `.codex/` sidecar. It is a javac `NOTE`, not a warning, and
> nothing is wrong with your build; it simply repeats until you pick one of three answers:
>
> - **Have VibeTags manage it**, the usual answer for a Claude + Codex project. Paste a marker
>   pair into `AGENTS.md`:
>
>   ```markdown
>   <!-- VIBETAGS-START -->
>   <!-- VIBETAGS-END -->
>   ```
>
>   A file carrying the markers was written by VibeTags in the first place, and only the region
>   between them is ever replaced, so refreshing it cannot clobber your prose. Marked files stay
>   managed no matter how many other AI config files are present.
>
> - **Keep it hand-written.** Change nothing and read the note as the confirmation it is.
> - **Make it the sole AI config file.** Delete `CLAUDE.md`, `GEMINI.md` and the rest, and
>   `AGENTS.md` becomes a managed file with no markers needed.
>
> There is no flag that silences the note while leaving `AGENTS.md` unmanaged: javac notes are not
> suppressible per processor. Adding the marker pair is the way to stop seeing it.

### 4. Annotate your Java code

```java
import se.deversity.vibetags.annotations.*;
```

Most `@AI*` annotations have **no `value()` element**, so the positional shorthand does not compile.
`@AILocked("Legacy code")` is an error; `@AILocked(reason = "Legacy code")` is what you want. The
[Element cheat sheet](#element-cheat-sheet--read-this-before-your-first-annotation) below lists the
elements of all 44, including the seven that do take the positional form and the ten that will not
compile without arguments.

### 5. Compile — guardrails are generated automatically

```bash
mvn clean compile   # or: gradle clean build
```

`clean` matters more than it looks: VibeTags runs inside the compiler, so an incremental build with
no changed sources never starts `javac` and a platform file you just created stays empty even
though the build is green.

### 6. Verify it actually ran

Every way this setup fails is silent, so check rather than assume:

```bash
jbang se.deversity.vibetags:vibetags-cli:1.2.4 doctor
```

Or by hand, in the order things go wrong:

1. **Was the processor on the path?** The compile log carries `VibeTags: Root resolved: …`. No such
   line at all means only `vibetags-annotations` was wired up, or JDK 23+ skipped a class-path
   processor (step 1).
2. **Did it write where you are looking?** That same line is the output directory (step 2).
3. **Did anything opt in?** `VibeTags: No AI config files found` means no platform file exists
   (step 3).
4. **Still empty?** Read `vibetags.log` at the resolved root. Every skipped write is a `write.skip`
   event carrying a `reason=`, and `-Avibetags.log.level=DEBUG` records the full decision path.

---

## Project Structure — where guardrails live

Guardrails are generated into **three tiers**, and which files exist decides which tiers you get.
Getting the layout right matters more than getting the annotations right: the same annotation in the
wrong layout ends up in a file the agent never loads.

| Tier | Scope | File | When the agent reads it |
|---|---|---|---|
| **1 — Project** | Whole repo/reactor | `CLAUDE.md`, `.cursorrules`, `GEMINI.md`, … at the root | Always in context |
| **2 — Module** | One module | `module-a/CLAUDE.md` | While working in that module |
| **3 — Element/topic** | One class, or one role | `.claude/rules/*.md`, `.cursor/rules/*.mdc`, … | When it opens a matching source file |

The tiers never duplicate each other. Opt into **Tier 1 + Tier 3 together** and the aggregate stops
repeating what the scoped files already say: it keeps the always-on **safety tier** inline
(`@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure`) and replaces the rest
with a one-line index. That split is the whole point — a locked file has to be known *before* the
agent opens it, while a performance constraint only matters once it is editing that method.

### Single module

```
my-project/
├── pom.xml
├── CLAUDE.md                  ← Tier 1: always loaded
├── .claude/rules/             ← Tier 3: loaded per file (collapses Tier 1 to an index)
│   └── com-example-OrderService.md
└── src/main/java/…
```

### Reactor — merged root (start here)

Every module's guardrails are merged into one root file, each in its own `VIBETAGS-MODULE` region.
**Every module must point at the reactor root**, or its guardrails silently never arrive:

```xml
<compilerArgs><arg>-Avibetags.root=${maven.multiModuleProjectDirectory}</arg></compilerArgs>
```

```
reactor/
├── pom.xml
├── CLAUDE.md                  ← every module's guardrails, merged
├── .vibetags-mod-core         ← generated; gitignore these
├── core/pom.xml
└── app/pom.xml
```

A module that overrides `compilerArgs` or `annotationProcessorPaths` will not inherit that option
and will generate into its own directory instead. VibeTags warns when it can tell that is what
happened; heed it rather than wondering where the guardrails went.

### Reactor — lean indexed root (recommended once it grows)

Give each module its own scoped rules and add `.vibetags-root-index` at the reactor root. The root
then keeps each module's **safety tier** inline and points at that module's own rules for the rest —
in one real 5-module project, 537 lines of always-on context became 141.

```
reactor/
├── .vibetags-root-index       ← the opt-in (empty file)
├── CLAUDE.md                  ← per module: safety tier + a pointer
├── core/.claude/rules/        ← that module's full detail
└── app/.claude/rules/
```

Files can live at either level: `.github/instructions/` at the **root** collects every module's
Copilot rules into one shared directory, while `.claude/rules/` inside each **module** keeps them
per module. Both work; pick per platform.

### Optional layout files

| File | Where | Effect |
|---|---|---|
| `.vibetags-root-index` | reactor root | Lean indexed root (above) |
| `.vibetags-roles` | root or module | Group scoped rules into human-named topic files instead of one per class |
| `.vibetags-mirror` | consuming module | Copy sibling modules' scoped rules in, for a module that centralises tests |
| `.vibetags-locks` | root | Machine-readable `@AILocked` report with source line numbers |
| `.vibetags-baseline` | root | Committed approval record for the enforcing mode — commit it |

`.vibetags-mod-*`, `.vibetags-cache` and `vibetags.log` are generated build state. Gitignore them.

---

## Annotations Reference

### Element cheat sheet — read this before your first annotation

Java's positional shorthand `@Foo(x)` only works when an annotation has an element literally named
`value()`. **Thirty-seven of the forty-four do not**, so `@AILocked("Legacy code")` fails with a
compiler error that does not name the element you should have used:

```
error: cannot find symbol
@AILocked("Legacy code")
          ^
  symbol:   method value()
```

`@AILocked(reason = "Legacy code")` is the form that works. Nothing at the call site tells you
which kind of annotation you are holding, hence this table.

**The seven that take the positional form:**

| Annotation | Positional form | Use on | `value()` type |
|---|---|---|---|
| `@AICallersOnly` | `@AICallersOnly({"com.acme.Api", "com.acme.Facade"})` | class, method | `String[]`, **required** |
| `@AIExplain` | `@AIExplain(AIExplain.ComplexityLevel.HIGH)` | class, method | `ComplexityLevel`, default `HIGH` |
| `@AIExtensible` | `@AIExtensible(AIExtensible.Strategy.VISITOR_PATTERN)` | class | `Strategy`, default `STRATEGY_PATTERN` |
| `@AIInputSanitized` | `@AIInputSanitized(AIInputSanitized.SanitizerType.SQL_INJECTION)` | **parameter, field** | `SanitizerType[]`, **required** |
| `@AIMemoryBudget` | `@AIMemoryBudget(AIMemoryBudget.AllocationPolicy.NO_AUTOBOXING)` | class, method | `AllocationPolicy`, default `ZERO_ALLOCATION` |
| `@AISecureLogging` | `@AISecureLogging(AISecureLogging.MaskingPolicy.HASH)` | **field, parameter** | `MaskingPolicy`, default `OMIT` |
| `@AIThreadAffinity` | `@AIThreadAffinity(AIThreadAffinity.Affinity.MAIN_ONLY)` | class, method | `Affinity`, **required** |

Every row above was compiled to check it. `@AIInputSanitized` and `@AISecureLogging` are the two
that do not go on a class. Putting one there fails with *annotation interface not applicable to
this kind of declaration*, not with anything that names the target you wanted.

**The ten that will not compile bare.** Each has at least one element with no default:

`@AIBannedApi(forbidden)`, `@AICallersOnly(value)`, `@AIGenerated(from)`,
`@AIInputSanitized(value)`, `@AIKeepInSync(mirrors)`, `@AILoadBearing(invariant)`,
`@AIRegulation(standard)`, `@AISunset(jira)`, `@AITemporary(expiresOn, reason)`,
`@AIThreadAffinity(value)`.

The other thirty-four are usable bare: `@AILocked`, `@AIPrivacy`, `@AIPure` and so on all carry a
sensible default reason. Naming a reason is still worth it, because it is what the agent reads.

**Every element, in full.** Bold marks an element with no default (omit it and the build fails).

| Annotation | Elements |
|---|---|
| `@AIArchitecture` | `belongsTo` String `""`, `cannotReference` String[] `{}` |
| `@AIAudit` | `checkFor` String[] `{}` |
| `@AIBannedApi` | **`forbidden`** String[], `useInstead` String `""`, `reason` String `""` |
| `@AICallersOnly` | **`value`** String[] |
| `@AIContext` | `focus` String `""`, `avoids` String `""` |
| `@AIContract` | `reason` String (long default) |
| `@AICore` | `sensitivity` String `"High"`, `note` String (default note) |
| `@AIDeprecated` | `replacedBy` String `""`, `migrationGuide` String (default), `deadline` String `""` |
| `@AIDomainModel` | `allow` String[] `{}` |
| `@AIDraft` | `instructions` String (default) |
| `@AIExplain` | `value` ComplexityLevel `HIGH`; one of `HIGH`, `MEDIUM`, `LOW` |
| `@AIExtensible` | `value` Strategy `STRATEGY_PATTERN`; one of `STRATEGY_PATTERN`, `VISITOR_PATTERN`, `FACTORY` |
| `@AIFeatureFlag` | `flag` String `""`, `defaultValue` boolean `false` |
| `@AIGenerated` | **`from`** String, `regenerateWith` String `""`, `editInstead` String `""` |
| `@AIIdempotent` | `reason` String `""` |
| `@AIIgnore` | `reason` String (default) |
| `@AIImmutable` | `note` String `""` |
| `@AIInputSanitized` | **`value`** SanitizerType[]; any of `SQL_INJECTION`, `XSS`, `PATH_TRAVERSAL`, `LDAP` |
| `@AIInternationalized` | `reason` String `""` |
| `@AIKeepInSync` | **`mirrors`** String[], `reason` String `""`, `enforcedBy` String `""` |
| `@AILegacyBridge` | `reason` String `""` |
| `@AILoadBearing` | **`invariant`** String, `breaksIf` String `""`, `suppressAudit` boolean `false` |
| `@AILocked` | `reason` String (default) |
| `@AIMemoryBudget` | `value` AllocationPolicy `ZERO_ALLOCATION`; one of `ZERO_ALLOCATION`, `NO_AUTOBOXING`, `NO_NEW_OBJECTS` |
| `@AIObservability` | `metrics` String[] `{}`, `traces` String[] `{}`, `logs` String[] `{}`, `note` String `""` |
| `@AIParallelTests` | `reason` String `""` |
| `@AIPerformance` | `constraint` String (default) |
| `@AIPrivacy` | `reason` String (default) |
| `@AIPrototype` | `reason` String `""` |
| `@AIPublicAPI` | `reason` String `""` |
| `@AIPure` | `reason` String `""` |
| `@AIRegulation` | **`standard`** String, `clause` String `""`, `description` String (default) |
| `@AISandboxOnly` | `reason` String `""` |
| `@AISchemaSafe` | `reason` String `""` |
| `@AISecure` | `aspect` String `""` |
| `@AISecureLogging` | `value` MaskingPolicy `OMIT`; one of `OMIT`, `HASH`, `MASK_CREDIT_CARD`, `MASK_EMAIL` |
| `@AIStrictClasspath` | `reason` String `""` |
| `@AIStrictExceptions` | `reason` String `""` |
| `@AIStrictTypes` | `reason` String `""` |
| `@AISunset` | **`jira`** String |
| `@AITemporary` | **`expiresOn`** String (`YYYY-MM-DD`), **`reason`** String |
| `@AITestDriven` | `testLocation` String `""`, `coverageGoal` int `100`, `framework` Framework[] `{JUNIT_5}`; any of `JUNIT_5`, `JUNIT_4`, `TESTNG`, `MOCKITO`, `ASSERTJ`, `SPOCK`, `NONE`; `mockPolicy` String `""` |
| `@AIThreadAffinity` | **`value`** Affinity; one of `MAIN_ONLY`, `NEVER_MAIN`, `BACKGROUND_ONLY`, `NAMED`; `thread` String `""`, `marshalVia` String `""`, `symptomIfViolated` String `""` |
| `@AIThreadSafe` | `strategy` Strategy `SYNCHRONIZED`; one of `SYNCHRONIZED`, `LOCK_FREE`, `IMMUTABLE`, `THREAD_LOCAL`, `OTHER`; `note` String `""` |

Enum constants are nested types, so they are written `AIExplain.ComplexityLevel.HIGH` unless you
static-import them.

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

### `@AIThreadSafe` — Preserve a thread-safety strategy

Use on: **class, method**

```java
@AIThreadSafe(
    strategy = AIThreadSafe.Strategy.LOCK_FREE,
    note = "All mutations go through ConcurrentHashMap; never introduce a synchronized block on the cache map."
)
public class SessionCache { ... }
```

Declares an *existing* thread-safety design that AI must not silently break. Different from `@AIAudit(checkFor = "Thread Safety")` (which asks the AI to look for new bugs).

**Strategies:** `SYNCHRONIZED`, `LOCK_FREE`, `IMMUTABLE`, `THREAD_LOCAL`, `OTHER`. Default `SYNCHRONIZED`.

When to use: caches and registries shared across threads, atomics-backed counters, singletons guarded by a named lock, per-thread context held in `ThreadLocal`.

---

### `@AIImmutable` — Declare a class immutable

Use on: **class**

```java
@AIImmutable(note = "Used by every test runner; safe to share across threads without copies.")
public final class AsyncTestConfig {
    private final int timeoutMs;
    public AsyncTestConfig(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
```

Declares the type immutable so AI assistants will not introduce setters, mutating methods, or non-final fields. The processor warns at compile time when an `@AIImmutable` class declares a non-final, non-static instance field.

When to use: value objects, config holders, snapshots passed across thread boundaries, cache keys.

**Compile-time warnings:**

- `@AIImmutable` on a type with a non-final, non-static instance field — violates the immutability declaration
- `@AIThreadSafe(IMMUTABLE)` + `@AIImmutable` on the same type — redundant (`@AIImmutable` already implies thread-safety)

---

### `@AIDeprecated` — Route callers toward a replacement

Use on: **class, method, field**

```java
@AIDeprecated(
    replacedBy = "com.example.payment.PaymentProcessor",
    migrationGuide = "Switch callers to PaymentProcessor.charge(). The new API uses Money instead of double.",
    deadline = "v2.0 (2026-Q4)"
)
public class OldPaymentApi { ... }
```

Richer than Java's `@Deprecated`. Where `@AILocked` *preserves* an element, `@AIDeprecated` actively *routes AI toward killing it* — the AI is told to suggest migrating callers rather than extending the deprecated element.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `replacedBy` | `String` | `""` | Fully-qualified name of the replacement |
| `migrationGuide` | `String` | `"Migrate any caller to the replacement."` | How callers should migrate |
| `deadline` | `String` | `""` | Removal deadline (release version, ISO date, etc.) |

When to use: legacy APIs being phased out, modules behind a sunset flag, methods kept only for backwards compatibility while callers migrate.

**Compile-time warnings:**

- `@AIDeprecated` + `@AILocked` on the same element — contradictory (locked preserves; deprecated routes callers away)

---

### `@AIObservability` — Protect instrumentation

Use on: **class, method**

```java
@AIObservability(
    metrics = {"orders.placed.total", "orders.placed.failed"},
    traces  = {"order.place"},
    logs    = {"OrderPlaced", "OrderPlacementFailed"},
    note    = "Watched by the Orders SLO dashboard."
)
public void recordOrderPlaced(String orderId, boolean success) { ... }
```

Marks code whose metrics, trace spans, or log statements downstream dashboards/alerts depend on. AI assistants must not silently remove or rename the listed instrumentation.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `metrics` | `String[]` | `{}` | Metric counter/gauge names this element publishes |
| `traces`  | `String[]` | `{}` | Trace span names this element opens |
| `logs`    | `String[]` | `{}` | Log statement identifiers this element emits |
| `note`    | `String`   | `""` | Free-form note (e.g., "watched by SLO dashboard X") |

When to use: SLO emitters, audit-log writers, request handlers whose latency histograms feed an SLA, background workers whose failure metrics page on-call.

**Compile-time warnings:**

- `@AIObservability` with no `metrics`, `traces`, or `logs` — no-op (nothing to preserve)

---

### `@AIRegulation` — Tie code to a compliance clause

Use on: **class, method, field**

```java
@AIRegulation(
    standard = "GDPR",
    clause = "Art. 17",
    description = "Right to erasure — when invoked, deletes ALL PII for the given user across every connected store."
)
public class GdprService { ... }
```

Ties code to a specific regulatory clause (GDPR, PCI-DSS, HIPAA, SOX, …). Stronger than `@AIAudit` because it names the exact article — AI assistants must document compliance impact for every change and must not weaken the requirement.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `standard`    | `String` | *(required)* | Compliance standard name (e.g., `"GDPR"`, `"PCI-DSS"`, `"HIPAA"`, `"SOX"`) |
| `clause`      | `String` | `""`        | Specific clause/article/section |
| `description` | `String` | non-blank   | What this element does to satisfy the requirement |

When to use: GDPR Art. 17 / Art. 20 implementations, PCI-DSS card-handling code, HIPAA-protected PHI read/write paths, SOX-relevant financial reporting and audit-log writers.

**Compile-time warnings:**

- `@AIRegulation` with a blank `standard` — required attribute missing

---

### `@AIArchitecture` — Enforce architectural layer boundaries

Use on: **class**

```java
@AIArchitecture(
    belongsTo = "domain",
    cannotReference = {"infrastructure", "web"}
)
public class OrderService { ... }
```

Declares which architectural layer this class belongs to and which layers it must never import from. AI must not introduce references to forbidden layers — e.g., a domain class importing a JPA repository or an HTTP controller.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `belongsTo` | `String` | `""` | The layer or component this class belongs to (e.g., `"domain"`, `"application"`, `"web"`) |
| `cannotReference` | `String[]` | `{}` | Layers or components this class must not import from |

---

### `@AILegacyBridge` — Protect compatibility bridges from modernization

Use on: **class, method**

```java
@AILegacyBridge(reason = "Mirrors a v1 payment-SDK quirk; 'cleaning it up' broke the gateway in 2023")
public class LegacyPaymentAdapter {
    // Works around a quirk in the v1 payment provider SDK — must not be "cleaned up"
    public String formatAmount(double amount) { ... }
}
```

Marks code that exists solely to bridge to a legacy or upstream system with known quirks or bugs. AI must not modernize the structure, apply new patterns, or remove the "ugly" parts — they exist for a reason. Internal business logic may still be changed. The optional `reason` records *why* across AI sessions and is surfaced in the generated output.

When to use: SDK adapter shims, workarounds for upstream library bugs, compatibility wrappers kept alive for old API clients.

---

### `@AIStrictClasspath` — Prevent dynamic loading and reflection hacks

Use on: **class, method**

```java
@AIStrictClasspath(reason = "Runs in the locked-down sandbox where the SecurityManager throws on reflection")
public class DataParser {
    // Must only use JDK and existing compile-time classpath — no runtime class loading
}
```

Prohibits AI from introducing dynamic class loading, custom `ClassLoader`s, runtime reflection tricks, or execution of dynamically constructed code. All dependencies must be resolvable at compile time from the existing classpath.

When to use: security-sensitive execution environments, GraalVM native-image targets, OSGi modules, any code that must be fully AOT-analyzable.

---

### `@AIInternationalized` — Prohibit hardcoded user-facing strings

Use on: **class, method**

```java
@AIInternationalized(reason = "Ships in 11 locales; a hardcoded English string failed the l10n audit last quarter")
public class NotificationTemplateRenderer {
    // All user-visible text must come from message bundles — never hardcoded
}
```

Instructs AI that all user-visible text (labels, messages, error strings, button text) must be resolved through the project's i18n framework (e.g., `MessageSource`, `ResourceBundle`, `gettext`). AI must never introduce hardcoded string literals for anything a user would see.

When to use: UI components, REST error responses, email templates, notification services — any code whose output reaches end users.

---

### `@AIPublicAPI` — Preserve backward compatibility

Use on: **class, method**

```java
@AIPublicAPI(reason = "Consumed by three external partner integrations pinned to v1")
public class ProductSearchClient {
    public List<Product> search(String query, int maxResults) { ... }
}
```

Declares that this element is part of a public API surface. All AI changes must be **additive and backward-compatible** — renaming methods, changing parameter types, or altering serialization formats is forbidden. Internal implementation may be improved freely.

Unlike `@AIContract` (which freezes one specific signature), `@AIPublicAPI` applies the backward-compatibility rule to the entire class.

When to use: SDK entry points, REST controller response shapes, message schema classes, library interfaces consumed by third parties.

---

### `@AISchemaSafe` — Prevent destructive schema changes

Use on: **class, field**

```java
@AISchemaSafe(reason = "Replicated to the billing read-model; column changes need a backward-compatible migration")
@Entity
public class UserEntity {
    @Column(name = "email", nullable = false)
    private String email;
}
```

Instructs AI that this class or field maps to persistent storage (database, message schema, serialization format). Destructive changes — dropping columns, renaming fields, changing types — are forbidden without explicit backward-compatible migrations. AI must propose additive-only changes.

When to use: JPA/Hibernate entities, Avro/Protobuf schema classes, JSON serialization DTOs, Flyway-managed tables.

---

### `@AIStrictExceptions` — Enforce precise error handling

Use on: **class, method**

```java
@AIStrictExceptions(reason = "A bare catch(Exception) once swallowed a rollback and double-charged customers")
public class PaymentGatewayClient {
    public Receipt charge(Money amount) throws PaymentDeclinedException { ... }
}
```

Prohibits AI from catching or throwing `Exception`, `Throwable`, or other overly broad types. All exceptions must be specific, well-named, and carry descriptive messages with preserved stack traces. Silent catch blocks (`catch (Exception e) {}`) are also forbidden.

When to use: external integrations, retry boundaries, error-handling layers, code that feeds into structured logging or alerting.

---

### `@AIStrictTypes` — Require precise domain types

Use on: **class, method, field**

```java
@AIStrictTypes(reason = "Currency math broke in INC-4412 when a double leaked into the amount")
public class PricingCalculator {
    // Use BigDecimal for money, Instant/ZonedDateTime for time — never double or String
    public BigDecimal calculateDiscount(Money basePrice, Percentage rate) { ... }
}
```

Instructs AI to avoid loose types (`Object`, raw collections, `Map<String, Object>`, `double` for currency, `String` for dates) and instead use well-defined, type-safe domain models or strongly-typed transfer objects.

When to use: financial calculations, time/date handling, any domain model where type safety prevents silent data corruption.

---

### `@AIParallelTests` — Enforce test isolation for concurrent execution

Use on: **class, method**

```java
@AIParallelTests(reason = "A shared static counter caused flaky CI in build #4471 — keep cases isolated")
public class OrderServiceTest {
    // Tests must not share mutable state or bind to fixed ports
}
```

Instructs AI that any generated or modified tests for this element must be safe for parallel execution. Forbidden: shared mutable static state, fixed port bindings, database rows with hard-coded IDs, execution-order dependencies. Each test must be fully self-contained.

When to use: test classes run under JUnit 5 parallel execution, `@Isolated` test suites, any test module with `forkCount > 1` in Maven Surefire.

---

### `@AIIdempotent` — Declare an operation must be idempotent

Use on: **class, method**

```java
@AIIdempotent(reason = "Called by the retry scheduler — multiple invocations must produce the same result.")
public void processOrder(String orderId) {
    // Must tolerate repeated calls without double-processing
}
```

Declares that the annotated operation is expected to be idempotent. AI must not introduce side effects that cause repeated calls to produce different results (e.g., double-inserts, repeated external API calls without deduplication, counter increments on every call).

| Attribute | Type | Default | Description |
|---|---|---|---|
| `reason` | `String` | `""` | Free-form note explaining why idempotency is required |

When to use: retry handlers, message consumers, webhook processors, payment captures, any operation exposed to at-least-once delivery.

**Compile-time warnings:**

- `@AIIdempotent` + `@AIDraft` on the same element — contradictory (idempotent declares a stable contract while draft marks the element as unfinished)

---

### `@AIFeatureFlag` — Mark code gated behind a feature flag

Use on: **class, method, field**

```java
@AIFeatureFlag(flag = "checkout.new-flow", defaultValue = false)
public void processNewCheckout(Cart cart) {
    // Only active when the 'checkout.new-flow' flag is enabled
}
```

Tells AI that the annotated element is gated behind a runtime feature flag. AI must preserve the flag check and must never assume the flag is always active (or always inactive). Removing the conditional guard, inlining the `true` branch, or hardcoding the default is forbidden.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `flag` | `String` | `""` | The feature flag key (e.g., `"checkout.new-flow"`) |
| `defaultValue` | `boolean` | `false` | The flag's default value when not explicitly set |

When to use: A/B experiments, gradual rollouts, kill switches, beta features, dark launches.

**Compile-time warnings:**

- `@AIFeatureFlag` + `@AILocked` on the same element — contradictory (locked freezes code while feature flag implies conditional execution)
- `@AIFeatureFlag` with blank `flag` — no-op; the flag key is unspecified

---

### `@AISecure` — Mark security-critical code

Use on: **class, method**

```java
@AISecure(aspect = "authentication")
public class JwtTokenValidator {
    // Any change here must be reviewed for security implications
    public boolean validate(String token) { ... }
}
```

Declares that the annotated element implements a security-critical concern (e.g., authentication, encryption, authorization, session management, input sanitization). AI must not weaken security properties and must explicitly flag any proposed change for security review.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `aspect` | `String` | `""` | The security concern (e.g., `"authentication"`, `"encryption"`, `"authorization"`) |

When to use: JWT/OAuth token handling, cryptographic operations, authorization checks, session management, input validation against injection attacks, any code whose weakening would create a security vulnerability.

**Compile-time warnings:**

- `@AISecure` with blank `aspect` — advisory; consider specifying the security concern (e.g. `"authentication"`, `"encryption"`)
- `@AISecure` + `@AIIgnore` on the same element — contradictory; `@AIIgnore` hides the element but `@AISecure` requires AI visibility for security review

### `@AICallersOnly` — Restrict allowed invoking callers

Use on: **class, method**

```java
@AICallersOnly({"com.example.service.PricingService", "com.example.payment.PaymentProcessor"})
public static void executeSecureDatabaseWipe() { ... }
```

Restricts which packages or classes are permitted to invoke this method or class. Enforced by the compiler/processor to prevent AI from introducing illegal architectural bypasses.

---

### `@AISandboxOnly` — Restrict to mock or sandbox environments

Use on: **class, method**

```java
@AISandboxOnly(reason = "Seeds fake credentials; a prod hotfix once imported it and leaked test data to staging")
public class SandboxTestHelper { ... }
```

Restricts the target element strictly to sandbox, dev, or mock/test environments. Prevents the AI from importing or referencing sandbox utilities in production pathways.

**Compile-time warnings:**

- `@AISandboxOnly` + `@AIDomainModel` on the same element — contradictory (sandbox mocks should not be subjected to framework-free domain model constraints)

---

### `@AIMemoryBudget` — Enforce strict allocation policies

Use on: **class, method**

```java
@AIMemoryBudget(AIMemoryBudget.AllocationPolicy.ZERO_ALLOCATION)
public static int calculateFastFibonacci(int n) { ... }
```

Restricts heap allocations, autoboxing, or object instantiation inside high-performance critical sections.

**Allocation Policies:** `ZERO_ALLOCATION`, `NO_AUTOBOXING`, `NO_NEW_OBJECTS`.

---

### `@AIPure` — Mark side-effect-free pure mathematical functions

Use on: **method**

```java
@AIPure(reason = "Memoized by callers that assume referential transparency — no logging or caching side effects")
public static int add(int a, int b) { return a + b; }
```

Declares that a method is a pure mathematical function. Must be deterministic (same input leads to same output) and have zero side effects.

---

### `@AIDomainModel` — Enforce Domain-Driven Design boundaries

Use on: **class**

```java
@AIDomainModel(allow = {"java.math.BigDecimal"})
public class ImmutableProductPrice { ... }
```

Enforces DDD boundaries by preventing external/framework imports. The compiler will scan and block any imports from Spring, JPA/Hibernate, Jackson, etc. unless explicitly whitelisted.

---

### `@AIExtensible` — Mark open-closed polymorphic extension hooks

Use on: **class**

```java
@AIExtensible(AIExtensible.Strategy.STRATEGY_PATTERN)
public interface TaxCalculatorStrategy { ... }
```

Signals that a class or interface must be extended using polymorphic designs (Open-Closed Principle). Prompts the AI to introduce strategy or visitor patterns rather than accumulating massive conditional/switch statements.

**Strategies:** `STRATEGY_PATTERN`, `VISITOR_PATTERN`, `FACTORY`.

---

### `@AIInputSanitized` — Enforce input parameter sanitization

Use on: **parameter, field**

```java
public static void executeDatabaseQuery(
        @AIInputSanitized({AIInputSanitized.SanitizerType.SQL_INJECTION}) String sqlRawInput) { ... }
```

Enforces sanitization pipelines on input parameters or fields before they reach queries, HTML renderers, or files.

**Sanitizer Types:** `SQL_INJECTION`, `XSS`, `PATH_TRAVERSAL`, `LDAP`.

---

### `@AISecureLogging` — Mask sensitive variables in log statements

Use on: **field, parameter**

```java
public static void registerUserSession(
        String username,
        @AISecureLogging(AISecureLogging.MaskingPolicy.HASH) String passwordRaw) { ... }
```

Protects sensitive variables from being logged directly or leaked in console outputs.

**Masking Policies:** `OMIT`, `HASH`, `MASK_CREDIT_CARD`, `MASK_EMAIL`.

**Compile-time warnings:**

- `@AISecureLogging` + `@AIIgnore` on the same element — redundant (`@AIIgnore` already completely excludes the element)

---

### `@AIExplain` — Require Chain-of-Thought mathematical/architectural explanations

Use on: **class, method**

```java
@AIExplain(AIExplain.ComplexityLevel.HIGH)
public static double runComplexMatrixMath(double[][] a, double[][] b) { ... }
```

Enforces step-by-step mathematical/architectural Chain-of-Thought (CoT) explanations of any modifications.

**Complexity Levels:** `HIGH`, `MEDIUM`, `LOW`.

---

### `@AIPrototype` — Declare rapid disposable spikes

Use on: **class**

```java
@AIPrototype(reason = "Throwaway Q3 Kafka spike — no error handling on purpose; production must not depend on it")
public class DraftKafkaIntegrationSpike { ... }
```

Declares a rapid framework prototype. Relaxes standard strict quality rules (e.g. required i18n, coverage) within the class, but prevents it from leaking into stable production code.

---

### `@AISunset` — Ultra-strict api sunset deprecation guardrail

Use on: **class, method, field**

```java
@AISunset(replacement = PricingService.class, jira = "DEBT-742")
public static double deprecatedLegacyCalculatePrice(double basePrice) { ... }
```

AI models are strictly prohibited from adding any new references/calls to elements annotated with `@AISunset`.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `replacement` | `Class<?>` | `Object.class` | Fully qualified class replacement for the sunset API element |
| `jira` | `String` | *(required)* | JIRA or issue tracking ticket for deprecation/sunset progress (e.g. "DEBT-123") |

**Compile-time warnings:**

- `@AISunset` + `@AIDraft` on the same element — contradictory (sunset elements must not be actively drafted or expanded)
- `@AISunset` with blank `jira` — missing required JIRA issue key warning

---

### `@AITemporary` — Warn or block expired temporary logic and hotfixes

Use on: **class, method**

```java
@AITemporary(expiresOn = "2028-12-31", reason = "Hotfix workaround until upstream updates their API.")
public static void temporaryUpstreamBypass() { ... }
```

Hard stop for hotfixes, temporary stubs, or quick hacks. Warns or fails compilation once the local clock date exceeds the expiration date.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `expiresOn` | `String` | *(required)* | Expiration date in ISO format YYYY-MM-DD (e.g. "2026-06-30") |
| `reason` | `String` | *(required)* | Rationale behind this temporary workaround |

**Compile-time warnings:**

- `@AITemporary` with a blank `expiresOn` — missing required expiration date
- `@AITemporary` with an invalid `expiresOn` format (not YYYY-MM-DD)
- `@AITemporary` where local date is after `expiresOn` — expired logic warning

---

### `@AIGenerated` — Redirect edits to the true source

Use on: **class, method, field**

```java
@AIGenerated(from = "src/main/resources/openapi/orders.yaml",
             regenerateWith = "mvn generate-sources",
             editInstead = "src/main/resources/openapi/orders.yaml")
public class OrdersApiStub { ... }
```

A **redirect**, not a wall. `@AILocked` can only say "stop", which makes an agent give up or route around the obstacle; this names where the change belongs. `@AIIgnore` is wrong in the opposite direction — an agent must still *read* generated types to understand behavior, it must only never *write* them.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `from` | `String` | *(required)* | The schema, template, IDL, or upstream repo this is generated from |
| `regenerateWith` | `String` | `""` | Command that regenerates it (e.g. `"mvn generate-sources"`) |
| `editInstead` | `String` | `""` | The file a human should actually change, when it differs from `from` |

**Compile-time warnings:**

- `@AIGenerated` + `@AIIgnore` — contradictory; generated code must stay readable
- `@AIGenerated` + `@AIDraft` — contradictory; drafting output that gets overwritten is pointless
- `@AIGenerated` with neither `regenerateWith` nor `editInstead` — a dead end rather than a redirect

---

### `@AILoadBearing` — "This looks wrong and is deliberate"

Use on: **class, method, field, parameter**

```java
@AILoadBearing(invariant = "Sessions are never deallocated while the dispatch source is live",
               breaksIf = "Freeing here reintroduces a use-after-free crash under load (#412)",
               suppressAudit = true)
private final List<Session> retained = new ArrayList<>();
```

Unlike `@AILocked`, edits are welcome — as long as the invariant survives. Also covers the **intentional omission** case (a decorator deliberately not applied), which nothing else can express because there is no element to annotate for something that is not there.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `invariant` | `String` | *(required)* | What must remain true after any change |
| `breaksIf` | `String` | `""` | The concrete failure — crash, leak, silent desync |
| `suppressAudit` | `boolean` | `false` | Tells reviewers and scanners the oddity is not a defect |

**Compile-time warnings:**

- `@AILoadBearing` with a blank `breaksIf` — advisory; the failure mode is what makes the rule stick
- `@AILoadBearing(suppressAudit = true)` + `@AIAudit` — contradictory instructions to the same reviewer

---

### `@AIBannedApi` — Forbid symbols you cannot annotate

Use on: **class, method**

```java
@AIBannedApi(forbidden = {"java.lang.System.out", "java.lang.System.err"},
             useInstead = "the injected org.slf4j.Logger",
             reason = "Console output bypasses structured logging")
public class OrderService { ... }
```

Hosted on the **consumer** and pointing outward, because the symbols teams actually ban — `java.util.Date`, `System.out`, a framework's `@Scheduled` — are stdlib or third-party and cannot be annotated at all. `@AIArchitecture(cannotReference)` bans a *layer*, not a *symbol*, and carries no replacement.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `forbidden` | `String[]` | *(required)* | The forbidden symbols, types, or packages |
| `useInstead` | `String` | `""` | The sanctioned replacement |
| `reason` | `String` | `""` | Why the API is banned here |

**Compile-time warnings:**

- `@AIBannedApi` with an empty `forbidden[]` — no-op; nothing is banned
- `@AIBannedApi` with a blank `useInstead` — advisory; a ban with no route invites a worse substitute

---

### `@AIThreadAffinity` — Safe on exactly one thread

Use on: **class, method**

```java
@AIThreadAffinity(value = AIThreadAffinity.Affinity.NAMED,
                  thread = "Swing EDT",
                  marshalVia = "SwingUtilities.invokeLater",
                  symptomIfViolated = "Silent repaint corruption; no exception on most JDKs")
public void refreshTable() { ... }
```

The inverse of `@AIThreadSafe`, which promises safety from *any* thread. These are opposite claims: tagging an EDT-pinned method `@AIThreadSafe` states something false, and leaving it untagged invites "let's move this off the main thread". An AI asked to make it thread-safe adds a lock — precisely the wrong fix, because the requirement is not mutual exclusion but *which* thread runs the call.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `value` | `Affinity` | *(required)* | `MAIN_ONLY`, `NEVER_MAIN`, `BACKGROUND_ONLY`, or `NAMED` |
| `thread` | `String` | `""` | The thread's name when `value` is `NAMED` |
| `marshalVia` | `String` | `""` | How a caller on the wrong thread hands work across |
| `symptomIfViolated` | `String` | `""` | What going wrong looks like — usually only under load |

**Compile-time warnings:**

- `@AIThreadAffinity` + `@AIThreadSafe` — contradictory; opposite claims, so one of them is false
- `@AIThreadAffinity(NAMED)` with a blank `thread` — the required thread is unidentifiable
- `@AIThreadAffinity` with a blank `marshalVia` — advisory; the caller is told "no" with no way to comply

---

### `@AIKeepInSync` — Duplicated at sites that must move together

Use on: **class, method, field**

```java
@AIKeepInSync(mirrors = {"pom.xml:<version>", "README.md badge", "docs/CHANGELOG.md"},
              reason = "The release version is asserted in three places and drifts silently",
              enforcedBy = "ProjectFactsConsistencyTest")
public static final String VERSION = "1.2.4";
```

The element is free to change — the failure mode is a *partial* change that desyncs a mirror no compiler checks. `@AIContract` freezes one signature so it cannot change at all; neither it nor `@AISchemaSafe` expresses "edit A ⇒ you must also edit B". Mirrors routinely point outside the compilation unit, so VibeTags can only *name* them, not verify them.

| Attribute | Type | Default | Description |
|---|---|---|---|
| `mirrors` | `String[]` | *(required)* | The sites that must move together with this element |
| `reason` | `String` | `""` | Why the duplication exists and what desync would break |
| `enforcedBy` | `String` | `""` | The parity test or CI check; its absence means drift is not caught |

**Compile-time warnings:**

- `@AIKeepInSync` with an empty `mirrors[]` — no-op; nothing is kept in sync
- `@AIKeepInSync` + `@AIContract` — NOTE; verify the mirrors track something other than the frozen signature

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
| `@AIGenerated` + `@AILocked` | Belt-and-braces on generated output; `@AIGenerated` alone is usually better, since it redirects instead of dead-ending |
| `@AILoadBearing` + `@AIExplain` | Supply the rationale AND require one back for any change |
| `@AIBannedApi` + `@AIArchitecture` | Ban specific symbols AND the layers they live in |
| `@AIThreadAffinity` + `@AIThreadSafe` | **Warning**: contradictory — opposite claims, one is false |
| `@AIGenerated` + `@AIIgnore` | **Warning**: contradictory — generated code must stay readable |
| `@AILoadBearing(suppressAudit)` + `@AIAudit` | **Warning**: contradictory — one suppresses findings, the other mandates them |
| `@AIContract` + `@AILocked` | **Warning**: overlapping intent — consider using only `@AILocked` |
| `@AITestDriven` + `@AIContext` | Enforce TDD workflow AND guide implementation style |
| `@AITestDriven` + `@AIPerformance` | Any change must include tests AND meet complexity constraints |
| `@AITestDriven` + `@AIIgnore` | **Warning**: contradictory — `@AIIgnore` excludes element from AI context |
| `@AITestDriven` + `@AILocked` | **Warning**: contradictory — `@AILocked` prohibits all changes |
| `@AIThreadSafe` + `@AIPerformance` | Concurrent code with strict complexity budget |
| `@AIThreadSafe` + `@AIAudit` | Preserve sync invariant AND audit each change for new bugs |
| `@AIImmutable` + `@AIThreadSafe(IMMUTABLE)` | **Warning**: redundant — `@AIImmutable` already implies thread-safety |
| `@AIDeprecated` + `@AIContext` | Mark for removal AND guide migration approach |
| `@AIDeprecated` + `@AILocked` | **Warning**: contradictory — locked preserves; deprecated routes callers away |
| `@AIObservability` + `@AIPerformance` | Instrumented hot-path code with budget AND dashboard dependencies |
| `@AIObservability` + `@AICore` | Core logic whose metrics feed dashboards — change with extreme caution |
| `@AIRegulation` + `@AIAudit` | Compliance clause AND mandatory security audit |
| `@AIRegulation` + `@AIPrivacy` | PII handler tied to a specific GDPR/HIPAA/PCI-DSS clause |
| `@AIRegulation` + `@AILocked` | Compliance code that must not be modified at all |
| `@AIArchitecture` + `@AIAudit` | Enforce layer boundaries AND audit each change for illegal imports |
| `@AIArchitecture` + `@AIContext` | Layer constraints with guidance on permitted patterns within that layer |
| `@AILegacyBridge` + `@AILocked` | Compatibility shim that must not be touched at all |
| `@AILegacyBridge` + `@AIContext` | Bridge code with guidance on what internal logic *can* be changed |
| `@AIPublicAPI` + `@AIContract` | Whole-class backward-compat rule AND per-method frozen signature (belt-and-suspenders) |
| `@AIPublicAPI` + `@AITestDriven` | Public API change must include tests proving backward compatibility |
| `@AISchemaSafe` + `@AIPrivacy` | Persistent entity with PII fields that must not appear in logs or fixtures |
| `@AISchemaSafe` + `@AIRegulation` | Schema tied to a compliance clause (GDPR erasure table, PCI card-data store) |
| `@AIStrictTypes` + `@AIPerformance` | Typed domain model AND strict complexity budget |
| `@AIStrictTypes` + `@AIRegulation` | Type-safe financial or PII handler tied to a regulatory clause |
| `@AIStrictExceptions` + `@AIAudit` | Precise error handling AND audit every change for swallowed exceptions |
| `@AIStrictExceptions` + `@AIObservability` | Error handler whose log statements feed dashboards — must not be silenced |
| `@AIInternationalized` + `@AIContext` | i18n enforcement with guidance on which bundle/framework to use |
| `@AIStrictClasspath` + `@AIPerformance` | Compile-time-only deps AND strict complexity budget |
| `@AIParallelTests` + `@AITestDriven` | Tests must be parallel-safe AND include coverage for every change |
| `@AIIdempotent` + `@AIDraft` | **Warning**: contradictory — idempotent declares a stable contract; draft marks it as unfinished |
| `@AIIdempotent` + `@AIContext` | Idempotent operation with guidance on which deduplication approach to use |
| `@AIFeatureFlag` + `@AILocked` | **Warning**: contradictory — locked freezes code; feature flag implies conditional execution |
| `@AIFeatureFlag` + `@AIContext` | Flag-gated code with guidance on how to manage the flag lifecycle |
| `@AISecure` + `@AIIgnore` | **Warning**: contradictory — `@AIIgnore` hides the element; `@AISecure` requires AI visibility for security review |
| `@AISecure` + `@AIAudit` | Security-critical code that must also be audited on every change |
| `@AISecure` + `@AIPrivacy` | Security-critical PII handler — must not be weakened AND values must never leak |
| `@AISecure` + `@AICore` | Core security logic — treat all changes with extreme caution AND flag for security review |
| `@AISandboxOnly` + `@AIDomainModel` | **Warning**: contradictory — sandbox mocks should not be subjected to framework-free domain model constraints |
| `@AISunset` + `@AIDraft` | **Warning**: contradictory — sunset elements must not be actively drafted or expanded |
| `@AISecureLogging` + `@AIIgnore` | **Warning**: redundant — `@AIIgnore` already completely excludes this element |
| `@AIMemoryBudget` + `@AIPerformance` | Enforce zero-allocation along with O(1) latency constraints on hot-path logic |
| `@AIPure` + `@AIMemoryBudget` | Enforce deterministic pure functions that have a zero allocation footprint |
| `@AIExplain` + `@AICore` | Core sensitive logic requiring high-fidelity Sequence/Class diagrams for any modification |

---

## Granular Rules

When the granular rule directories exist, VibeTags generates **one rule file per annotated class** instead of a single monolithic config file. Each rule file is automatically scoped to its class (e.g., `**/OrderService.java`). Orphaned files for classes that lose their annotations are cleaned up automatically.

| Directory | Platform | Format |
|---|---|---|
| `.claude/rules/*.md` | Claude Code | YAML front-matter (`paths:`) + Markdown |
| `.github/instructions/*.instructions.md` | GitHub Copilot | YAML front-matter (`applyTo:`) + Markdown |
| `.cursor/rules/*.mdc` | Cursor | YAML front-matter + Markdown |
| `.windsurf/rules/*.md` | Windsurf IDE | YAML front-matter + Markdown |
| `.trae/rules/*.md` | Trae IDE | YAML front-matter + Markdown |
| `.roo/rules/*.md` | Roo Code | Markdown |
| `.continue/rules/*.md` | Continue | YAML front-matter + Markdown |
| `.tabnine/guidelines/*.md` | Tabnine | Markdown |
| `.amazonq/rules/*.md` | Amazon Q | Markdown |
| `.ai/rules/*.md` | Universal AI standard | Markdown |
| `.pearai/rules/*.md` | PearAI | YAML front-matter + Markdown |
| `.kiro/steering/*.md` | Amazon Kiro | Markdown |

Enable by creating the directories:
```bash
mkdir -p .cursor/rules .windsurf/rules .trae/rules .roo/rules
mkdir -p .continue/rules .tabnine/guidelines .amazonq/rules .ai/rules .pearai/rules
mkdir -p .kiro/steering
mkdir -p .claude/rules .github/instructions
```

---

## Transitive Guardrails — rules that arrive from a dependency

An agent working in an application reads *that* application's `CLAUDE.md`, never the one belonging
to a library it depends on. A library can publish its package-level guardrails, and any project
that opts in renders them into its own AI configuration.

Both halves are file-presence opt-ins, like everything else here. Neither file exists by default.

**Publishing (the library).** Annotate `package-info.java`, then add `.vibetags-manifest`:

```java
// src/main/java/com/acme/crypto/api/package-info.java
@AISecure(aspect = "Never construct a raw Cipher; go through CryptoManagerFactory.")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.IMMUTABLE,
    note = "Every product of the factory is safe to share between threads.")
package com.acme.crypto.api;

import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AIThreadSafe;
```

```bash
# first non-comment line is the coordinate consumers will see
echo "com.acme:crypto-core:2.4.0" > .vibetags-manifest
```

The build writes `vibetags/manifests/com.acme.crypto.api.json` into the class output and the normal
`jar` task packages it. (Not `META-INF/` — javac's `CLASS_PATH` location skips archive directories
whose names are not valid package identifiers, so a manifest there is unreadable from a processor.)

**Consuming (the application).**

```bash
touch .vibetags-transitive
```

```markdown
<!-- appended to CLAUDE.md, after everything your own code declares -->
## Inherited Guardrails (dependencies)

- `com.acme.crypto.api` (from com.acme:crypto-core:2.4.0)
  - @AISecure: aspect=Never construct a raw Cipher; go through CryptoManagerFactory.

## Inherited Context (dependencies)

- `com.acme.crypto.api` (from com.acme:crypto-core:2.4.0)
  - @AIThreadSafe: strategy=IMMUTABLE; note=Every product of the factory is safe to share between threads.
```

Worth knowing:

- **The project's own rules come first, always.** The inherited block is appended last. That
  ordering *is* the precedence model — the output is prose an agent reads, not a ruleset a compiler
  applies, so a library cannot outrank the project consuming it.
- **Every inherited rule names its artifact**, because a dependency is contributing text an agent
  will act on and the reader has to see whose text it is.
- **Only packages the compilation imports are looked up**, so a hundred instrumented dependencies
  do not become a hundred pages of prompt. `-Avibetags.manifest.max=<n>` caps the advisory tier
  further; the six safety buckets are never dropped, and a cap that drops anything says so.
- **Only package-level annotations travel.** Class- and method-level guardrails stay local — a
  consumer cannot act on a rule about a class it never sees. Thirteen annotations accept
  `ElementType.PACKAGE`: `@AISecure`, `@AIPrivacy`, `@AICore`, `@AIAudit`, `@AIRegulation`,
  `@AIArchitecture`, `@AIPublicAPI`, `@AIBannedApi`, `@AIThreadSafe`, `@AIImmutable`,
  `@AIDeprecated`, `@AIContext`, `@AIStrictClasspath`.
- **kapt, ECJ and JPMS need a hand.** Discovery needs the compiler's Tree API and the classpath;
  where either is missing VibeTags reports a `NOTE` rather than pretending it found nothing, and
  the manifests are supplied with `-Avibetags.manifest.dir=<dir>` or
  `-Avibetags.manifest.packages=a.b,c.d`. Plain Gradle needs none of that.
- **Markers are resolved against `-Avibetags.root`.** A build that pins the root at a module
  directory needs `.vibetags-transitive` in that directory, not only at the reactor root.

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
            <!-- Override output root directory (every module of a reactor needs this) -->
            <arg>-Avibetags.root=${maven.multiModuleProjectDirectory}</arg>
            <!-- Name this module explicitly, if it cannot be read off the compiled sources -->
            <arg>-Avibetags.module=payments-core</arg>
            <!-- CI: verify the committed files match the annotations instead of writing them -->
            <arg>-Avibetags.check=true</arg>
            <!-- Opt-in enforcement: fail the build on a guarded signature change -->
            <arg>-Avibetags.enforce=locked,contract,publicapi</arg>
            <!-- Transitive: coordinate published in this library's manifests -->
            <arg>-Avibetags.manifest.origin=com.acme:crypto-core:2.4.0</arg>
            <!-- Transitive: read manifests from a directory (kapt/ECJ/JPMS fallback) -->
            <arg>-Avibetags.manifest.dir=build/vibetags-manifests</arg>
            <!-- Transitive: look up these packages explicitly, when imports cannot be read -->
            <arg>-Avibetags.manifest.packages=com.acme.crypto.api,com.acme.audit</arg>
            <!-- Transitive: cap inherited advisory rules (safety buckets are never dropped) -->
            <arg>-Avibetags.manifest.max=50</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### Enforcing mode (opt-in)

Guardrails are advisory by default: they go into the agent's context so a mistake is less likely.
For the families whose promise can be *proved* from the compiler's model, `-Avibetags.enforce` turns
that into a hard stop.

```bash
mvn compile -Avibetags.baseline.update=true   # record and commit .vibetags-baseline
mvn compile -Avibetags.enforce=contract       # thereafter, a signature change fails the build
```

| Family | What it checks |
|---|---|
| `locked` | An `@AILocked` element's visible shape is unchanged |
| `contract` | An `@AIContract` signature is unchanged — name, parameters, return type, checked exceptions |
| `publicapi` | Ditto for `@AIPublicAPI` |
| `all` | All of the above |

Method bodies, comments and formatting are invisible to it, so reformatting a locked file is not a
violation. `@AICallersOnly`, `@AIStrictClasspath`, `@AIThreadSafe` and `@AITestDriven` are **not**
enforceable — proving them needs call-graph or body analysis a processor cannot do portably — and
naming one is reported rather than silently ignored. An intended change is approved by re-running
with `-Avibetags.baseline.update=true` and committing the diff, so it gets reviewed.

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
| Green build, no `VibeTags:` line in the compile log at all | The processor never ran: only `vibetags-annotations` is wired up, or JDK 23+ ignored a class-path processor | Put `vibetags-processor` on `annotationProcessorPaths` / the `annotationProcessor` configuration (step 1) |
| Green build, files generated, but not in your project | `VibeTags: Root resolved:` points somewhere else: a Gradle worker, kapt, or an IDE compile | Set `-Avibetags.root` (step 2) |
| `cannot find symbol: class AILocked` | `vibetags-annotations` is missing from the compile classpath | Add it as an ordinary dependency (step 1) |
| Nothing changed after creating a platform file | An incremental build with no changed sources never starts `javac` | `mvn clean compile`, or touch a source file |
| `[NOTE] AGENTS.md left untouched because other AI config files are present` | Working as designed: `AGENTS.md` is managed only when it is the sole AI config file | Paste a `VIBETAGS-START`/`VIBETAGS-END` pair into it to have it managed; otherwise ignore (see step 3) |
| `error: cannot find symbol` … `symbol: method value()` on an `@AI*` annotation | Positional shorthand used on an annotation that has no `value()` element | Use named elements: `@AILocked(reason = "…")`; see the [Element cheat sheet](#element-cheat-sheet--read-this-before-your-first-annotation) |
| `[WARNING] VibeTags: unrecognized option 'vibetags.…'` | Typo in a `-A` option name | The message lists every supported option; fix the spelling |
| No files updated after compile | Target files don't exist | `touch CLAUDE.md` (or whichever platform file) then recompile |
| `[NOTE] No AI config files found` | No opt-in files present | Create one or more platform files (see step 2) |
| A module's guardrails are missing from the reactor root | That module never reached the root | Give it `-Avibetags.root=<reactor>`; VibeTags warns with *"generated its guardrails as its own root"* when it can tell |
| `[WARNING] … rewritten with a completely different set of elements` | A compilation replaced a module's guardrails with an unrelated set | Almost always a round that could not see the sources it should have — check this module's annotation processing before committing the regenerated files |
| `[WARNING] removed N scoped rule file(s) … while writing only M` | The build deleted more guardrails than it produced | Same cause; do not accept the deletion until you know why |
| `[WARNING] could not identify the compiling module` | Sources are not under `-Avibetags.root` | Set `-Avibetags.root`, or name it with `-Avibetags.module=<name>` |
| Guardrails differ between `mvn compile` and `mvn test` | Pre-1.0.1-RC8 processor | Upgrade — `compile` and `test-compile` now own separate sidecars |
| Gradle appends a second set of `VIBETAGS-MODULE` regions | Pre-1.0.1-RC8 processor | Upgrade, then delete the stray `.vibetags-mod-<hash>` file once |
| `[WARNING] @AIIgnore used but .cursorignore is missing` | Orphaned annotation | Create the missing file to fully support that platform |
| `[WARNING] contradictory @AIDraft and @AILocked` | Both annotations on same element | Remove one of them |
| `[WARNING] @AIAudit has no checkFor items` | Empty `checkFor` array | Add at least one vulnerability string |
| `[WARNING] contradictory @AIContract and @AIDraft` | Both annotations on same element | Remove one — a frozen signature can't also need drafting |
| `[WARNING] overlapping @AIContract and @AILocked` | Both annotations on same element | Use only `@AILocked` if no changes at all are intended |
| `[WARNING] contradictory @AITestDriven and @AIIgnore` | Both annotations on same element | Remove one — `@AIIgnore` excludes the element entirely |
| `[WARNING] contradictory @AITestDriven and @AILocked` | Both annotations on same element | Remove one — `@AILocked` prohibits all changes |
| `[WARNING] @AITestDriven has invalid coverageGoal` | `coverageGoal` outside 0–100 | Set a value between 0 and 100 (inclusive) |
| `[WARNING] @AIImmutable on … but field … is not final` | Non-final, non-static field on `@AIImmutable` class | Make the field `final`, or drop `@AIImmutable` |
| `[WARNING] contradictory @AIDeprecated and @AILocked` | Both annotations on same element | Pick one — locked preserves, deprecated routes callers away |
| `[WARNING] @AIThreadSafe(IMMUTABLE) and @AIImmutable` | Both annotations on same type | Use `@AIImmutable` alone — immutability already implies thread-safety |
| `[WARNING] @AIObservability declares no metrics, traces, or logs` | Empty annotation | Add at least one `metrics`/`traces`/`logs` entry |
| `[WARNING] @AIRegulation has a blank 'standard'` | Required `standard` is empty/whitespace | Name the standard (e.g., `"GDPR"`, `"PCI-DSS"`) |
| `[WARNING] contradictory @AIIdempotent and @AIDraft` | Both annotations on same element | Remove one — idempotent declares a stable contract; draft implies it's unfinished |
| `[WARNING] contradictory @AIFeatureFlag and @AILocked` | Both annotations on same element | Remove one — locked freezes; feature flag implies conditional execution |
| `[WARNING] @AIFeatureFlag has no flag key` | Blank `flag` attribute | Set the flag key (e.g., `flag = "checkout.new-flow"`) |
| `[WARNING] @AISecure has no aspect` | Blank `aspect` attribute | Specify the security concern (e.g., `aspect = "authentication"`) |
| `[WARNING] contradictory @AISecure and @AIIgnore` | Both annotations on same element | Remove `@AIIgnore` — security-critical code must remain visible to AI for review |
| `[WARNING] contradictory @AISandboxOnly and @AIDomainModel` | Both annotations on same element | Sandbox mocks should not be subjected to framework-free domain model constraints |
| `[WARNING] contradictory @AISunset and @AIDraft` | Both annotations on same element | Sunset elements must not be actively drafted or expanded |
| `[WARNING] redundant @AISecureLogging and @AIIgnore` | Both annotations on same element | `@AIIgnore` already completely excludes this element; `@AISecureLogging` is redundant |
| `[WARNING] @AISunset has a blank 'jira'` | Blank `jira` attribute | Specify the JIRA issue ticket key (e.g., `jira = "DEBT-123"`) |
| `[WARNING] @AITemporary has a blank 'expiresOn'` | Blank `expiresOn` attribute | Specify an ISO date (`expiresOn = "YYYY-MM-DD"`) |
| `[WARNING] @AITemporary has an invalid 'expiresOn' date format` | Format not YYYY-MM-DD | Use strict `YYYY-MM-DD` syntax (e.g., `"2026-06-30"`) |
| `[WARNING] Temporary logic in … has expired` | Current date is past `expiresOn` | The temporary hotfix/hack has expired; clean it up immediately |
| `[WARNING] @AIArchitecture has a blank 'belongsTo'` | Blank `belongsTo` layer | Specify the layer name (e.g., `belongsTo = "domain"`) |

---

## Supported Output Files

| File(s) | Platform |
|---|---|
| `CLAUDE.md`, `.claudeignore` | Claude / Claude Code |
| `CLAUDE.local.md` | Claude Code (local override) |
| `.claude/rules/*.md` | Claude Code (granular per-class rules) |
| `.claude/skills/vibetags-guardrails/SKILL.md` | Claude Code (Skill) |
| `.cursorrules`, `.cursorignore` | Cursor (traditional) |
| `.cursor/rules/*.mdc` | Cursor (granular per-class rules) |
| `.windsurfrules` | Windsurf IDE (traditional) |
| `.windsurf/rules/*.md` | Windsurf IDE (granular per-class rules) |
| `.trae/rules/*.md` | Trae IDE (granular per-class rules) |
| `.roo/rules/*.md` | Roo Code |
| `CONVENTIONS.md`, `.aiderignore` | Aider |
| `QWEN.md`, `.qwen/settings.json`, `.qwen/commands/refactor.md`, `.qwenignore` | Qwen |
| `gemini_instructions.md`, `GEMINI.md`, `.aiexclude` | Gemini |
| `.antigravityignore` | Antigravity AI |
| `AGENTS.md`, `.codex/config.toml`, `.codex/rules/` | Codex CLI |
| `.github/copilot-instructions.md`, `.copilotignore` | GitHub Copilot |
| `.github/instructions/*.instructions.md` | GitHub Copilot (granular per-class rules) |
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
| `.clinerules` | Cline AI assistant |
| `.junie/guidelines.md` | JetBrains Junie |
| `.kiro/steering/*.md` | Amazon Kiro (granular per-class rules) |
| `DESIGN.md` | AI design agents (Cursor, Claude, Copilot, etc.) |
| `.void/rules.md` | Void Editor |
| `.coderabbit.yaml` | CodeRabbit (AI PR reviewer) |
| `.pr_agent.toml` | Qodo/Codium PR-Agent (AI PR reviewer) |
| `ellipsis.yaml` | Ellipsis (AI PR reviewer) |
| `.roomodes` | Roo Code ("VibeTags Architect" custom mode) |
| `.repomixignore` | Repomix (context packer) |
| `.gitingestignore` | Gitingest (context packer) |
| `.gptignore` | GPT context packer |
| `.ghostcoderignore` | Ghostcoder |
| `.piecesignore` | Pieces for Developers |
