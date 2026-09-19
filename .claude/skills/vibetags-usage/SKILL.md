---
name: vibetags-usage
description: This skill should be used when the user asks how to use VibeTags, add or choose an `@AI*` guardrail annotation (`@AILocked`, `@AIContext`, `@AIPrivacy`, `@AIAudit` or any other from `se.deversity.vibetags`), opt a project into AI platform files, protect code from AI edits, or find out why a guardrail file was not generated.
version: 1.3.5
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
        <version>1.3.5</version>
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
                        <version>1.3.5</version>
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
    compileOnly         'se.deversity.vibetags:vibetags-annotations:1.3.5'
    annotationProcessor 'se.deversity.vibetags:vibetags-processor:1.3.5'
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
touch CLAUDE.md                            # Claude / Claude Code (.claudeignore is deprecated, #645)
touch CLAUDE.local.md                      # Claude Code (local override)
mkdir -p .claude/rules                     # Claude Code (granular per-class rules)
mkdir -p .claude/skills/vibetags-guardrails && touch .claude/skills/vibetags-guardrails/SKILL.md  # Claude Code (Skill)
touch .cursorrules .cursorignore           # Cursor (traditional)
mkdir -p .cursor/rules                     # Cursor (granular per-class rules)
mkdir -p .trae/rules                       # Trae (granular per-class rules)
mkdir -p .roo/rules                        # Zoo Code (fork of the retired Roo Code; reads the same paths), per-class rules
touch CONVENTIONS.md .aider.conf.yml .aiderignore  # Aider (.aider.conf.yml is what loads CONVENTIONS.md)
touch .rooignore .continueignore .augmentignore  # Zoo Code / Continue / Augment exclusion lists
mkdir -p .zencoder/rules                   # Zencoder (per-class rules)
touch replit.md                            # Replit Agent
touch CONVENTIONS.md .aiderignore          # Aider
touch QWEN.md .qwenignore                  # Qwen
mkdir -p .qwen/commands && touch .qwen/commands/refactor.md  # Qwen /refactor command (own opt-in)
touch .aiexclude GEMINI.md                 # Gemini
mkdir -p .gemini && touch .gemini/styleguide.md    # Gemini Code Assist (GitHub PR reviewer)
mkdir -p .greptile && touch .greptile/rules.md     # Greptile (AI PR reviewer, recommended form)
touch .greptile/config.json                        # Greptile (@AIIgnore paths; only a span inside ignorePatterns is VibeTags')
touch greptile.json                                # Greptile (legacy form; only a span inside two values is VibeTags')
touch AGENTS.md                            # Codex CLI (see note below — only generated when sole)
mkdir -p .github && touch .github/copilot-instructions.md  # Copilot (.copilotignore is deprecated, #645)
mkdir -p .github/instructions               # GitHub Copilot (granular per-class rules)
touch llms.txt llms-full.txt               # Windsurf Cascade / llms.txt standard
touch .windsurfrules                       # Devin Desktop, formerly Windsurf (traditional, legacy)
mkdir -p .devin/rules                      # Devin Desktop (granular per-class rules, preferred directory)
# mkdir -p .windsurf/rules                 # Devin Desktop (granular, fallback directory; both load, pick one)
touch .devinignore                         # Devin Desktop exclusion list
touch .rules                               # Zed Editor
mkdir -p .cody && touch .cody/config.json .codyignore  # Sourcegraph Cody (deprecated, #645)
touch .supermavenignore                    # Supermaven (deprecated, #645)
mkdir -p .continue/rules                   # Continue (granular per-class rules)
mkdir -p .tabnine/guidelines               # Tabnine (granular per-class rules)
mkdir -p .amazonq/rules                    # Amazon Q (granular per-class rules; deprecated, #645)
mkdir -p .ai/rules                         # Universal AI standard (granular; deprecated, #645)
mkdir -p .pearai/rules                     # PearAI (granular per-class rules; deprecated, #645)
touch .mentatconfig.json                   # Mentat (deprecated, #645)
touch sweep.yaml                           # Sweep (GitHub App; deprecated, #645)
touch .plandex.yaml                        # Plandex (deprecated, #645)
touch .doubleignore                        # Double.bot (deprecated, #645)
mkdir -p .interpreter/profiles && touch .interpreter/profiles/vibetags.yaml  # Open Interpreter (deprecated, #645)
touch .codeiumignore                       # Codeium
touch GEMINI.md                            # Gemini (official markdown)
touch .antigravityignore                   # Antigravity AI (deprecated, #645)
touch .clinerules                          # Cline AI assistant (single file, deprecated #645), OR:
# mkdir -p .clinerules                     # Cline granular rules (same path: pick one)
mkdir -p .junie && touch .junie/AGENTS.md  # JetBrains Junie (current; legacy .junie/guidelines.md also written)
mkdir -p .kiro/steering                    # Amazon Kiro (granular per-class rules)
mkdir -p .grok/rules                       # Grok Build (granular per-class rules)
mkdir -p .agents/rules                     # Antigravity (granular per-class rules)
mkdir -p .aiassistant/rules                # JetBrains AI Assistant (granular per-class rules)
mkdir -p .augment/rules                    # Augment Code (granular per-class rules)
touch .goosehints                          # goose (Block)
touch DESIGN.md                            # AI design agents (Cursor, Claude, Copilot, etc.)
touch .coderabbit.yaml .pr_agent.toml ellipsis.yaml  # AI PR reviewers (CodeRabbit, PR-Agent, Ellipsis; ellipsis.yaml deprecated, #645)
touch .repomixignore .gitingestignore .gptignore  # Context packers (.ghostcoderignore and .piecesignore are deprecated, #645)
mkdir -p .void && touch .void/rules.md     # Void Editor (deprecated, #645)
touch .roomodes                            # Zoo Code (fork of the retired Roo Code; reads the same paths), "VibeTags Architect" custom mode
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
jbang se.deversity.vibetags:vibetags-cli:1.3.5 doctor
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

### Per-annotation reference, and everything else

The rest of this skill lives in `references/`. Read only the file the task needs:

| File | Read it when |
|---|---|
| [references/annotations.md](references/annotations.md) | You need one annotation's use, example, generated output or validation warnings. Search it for the annotation name rather than reading it whole (about 890 lines). |
| [references/combinations.md](references/combinations.md) | You are putting two annotations on one element and want to know whether they conflict. |
| [references/granular-and-transitive.md](references/granular-and-transitive.md) | Setting up per-class rule directories, or publishing/consuming guardrails that travel with a dependency. |
| [references/configuration.md](references/configuration.md) | Processor options for Maven or Gradle, and the opt-in enforcing mode. |
| [references/diagnosing.md](references/diagnosing.md) | A build error, a missing generated file, or a warning you do not understand. |
| [references/output-files.md](references/output-files.md) | Which file each AI tool reads. |
