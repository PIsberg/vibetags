# VibeTags Architecture - Technical Deep Dive

## Table of Contents

- [Overview](#overview)
- [System Architecture](#system-architecture)
- [Build Sequence](#build-sequence)
- [Data Flow](#data-flow)
- [Platform Output Formats](#platform-output-formats)
- [Core Components](#core-components)
- [Build Flow](#build-flow)
- [Directory Structure](#directory-structure)
- [Design Decisions](#design-decisions)
- [Testing Strategy](#testing-strategy)
- [Limitations](#limitations)
- [Future Architecture](#future-architecture)
- [Dependencies](#dependencies)
- [Build Commands](#build-commands)
- [AI Platform Integration](#ai-platform-integration)

## Overview

VibeTags is a **Java annotation processor** (JSR 269 compliant) that generates AI platform-specific configuration files from Java source code annotations. It operates at **compile-time only**, with zero runtime overhead.

```
Developer Annotations → javac + Annotation Processor → AI Config Files
```

### Key Technical Characteristics

- **Compile-time only**: Uses `@Retention(RetentionPolicy.SOURCE)` - annotations stripped from bytecode
- **Zero runtime dependency**: No VibeTags classes in production artifacts
- **File-existence opt-in**: Only generates files that already exist on disk
- **Write-if-changed**: Only updates files when content actually differs
- **Multi-platform**: Generates configs for all supported AI platforms simultaneously (Cursor, Claude, Gemini, Codex, Copilot, Qwen, Aider, Trae, Roo, Windsurf via llms.txt, AI PR reviewers like CodeRabbit/PR-Agent/Ellipsis, context packers, and more — see the [project facts](../README.md#project-facts))
- **Version stamped**: Every file includes VibeTags version + GitHub URL

### Published Artifacts

As of 0.6.0, VibeTags ships as three coordinates on Maven Central:

| Artifact | Purpose | Goes on | Depends on |
|---|---|---|---|
| `se.deversity.vibetags:vibetags-annotations` | The 39 `@interface` classes (see [project facts](../README.md#project-facts)) | Consumer's compile classpath | nothing |
| `se.deversity.vibetags:vibetags-processor` | `AIGuardrailProcessor` + `VibeTagsLogger` (slf4j/logback) | Annotation-processor path only | `vibetags-annotations` |
| `se.deversity.vibetags:vibetags-bom` (pom-only) | Manages versions of the two jars above | `<dependencyManagement>` import / Gradle `platform(...)` | — |

The split keeps `slf4j` / `logback` (the processor's internal logging deps) off the consumer's `compileClasspath`. Existing 0.5.x setups that pin only `vibetags-processor` continue to work — the processor declares `vibetags-annotations` as a regular compile dependency so the annotation classes are still resolved transitively. New projects should adopt the split layout shown in the README's Installation section.

## Table of Contents

- [System Architecture](#system-architecture)
- [Component Diagram](#component-diagram)
- [Class Diagram](#class-diagram)
- [Build Sequence](#build-sequence)
- [Data Flow](#data-flow)
- [Platform Output Formats](#platform-output-formats)
- [Core Components](#core-components)
  - [Annotations](#annotations)
  - [Annotation Processor](#annotation-processor)
  - [Generated Output Files](#generated-output-files)
- [Build Flow](#build-flow)
- [Directory Structure](#directory-structure)
- [Design Decisions](#design-decisions)
- [Testing Strategy](#testing-strategy)
- [Limitations](#limitations)
- [Future Architecture](#future-architecture)

---

## System Architecture

### Component Diagram

![Component Diagram](diagrams/component-diagram.png)

*Figure 1: High-level system architecture showing component interactions*

**Technical Flow:**
1. Developer annotates Java source code with VibeTags annotations
2. Build system (Maven/Gradle) invokes `javac` compiler
3. `javac` discovers `AIGuardrailProcessor` via SPI (`META-INF/services/`)
4. Processor scans annotations during compilation
5. Processor generates platform-specific config files to project root
6. Compiled bytecode contains zero VibeTags artifacts

### Class Diagram

![Class Diagram](diagrams/class-diagram.png)

*Figure 2: Class architecture showing annotation definitions and processor*

**Key Components:**

**Annotations** — package `se.deversity.vibetags.annotations`, jar `vibetags-annotations` (39 annotations total — see the [project facts](../README.md#project-facts)). Full list, targets, attributes, and semantics: [docs/ANNOTATIONS.md](ANNOTATIONS.md).

**Processor** — package `se.deversity.vibetags.processor`, jar `vibetags-processor`:
- `AIGuardrailProcessor` — extends `AbstractProcessor` (JSR 269); thin orchestrator (~230 lines) that wires the helpers below into the JSR 269 lifecycle
- `VibeTagsLogger` — SLF4J/Logback file logger, configurable via `-Avibetags.log.*`
- `@SupportedAnnotationTypes("*")` — processes all annotations
- Overrides `getSupportedSourceVersion()` to return `SourceVersion.latestSupported()` instead of a fixed `@SupportedSourceVersion` — the library builds/tests against Java 21, but pinning e.g. `RELEASE_17` would make javac emit a "supported source version" warning on every newer JDK a consumer compiles with
- Compile-scope dependency on `vibetags-annotations` so the processor code can reference annotation classes (e.g. `roundEnv.getElementsAnnotatedWith(AILocked.class)`) and so legacy single-coordinate consumers still get the annotations transitively.

**Internal helpers** — package `se.deversity.vibetags.processor.internal` (single-responsibility classes that do the actual work, since 0.6.0):
- `AnnotationCollector` — owns one `LinkedHashSet<Element>` accumulator per annotation type, aggregating annotated elements across all `javac` rounds; also tracks the `anyAnnotationsFound` flag used for the multi-module preservation check
- `AnnotationValidator` — emits compile-time consistency warnings (`@AIDraft`+`@AILocked` contradiction, empty `@AIAudit.checkFor`, redundant `@AIPrivacy`+`@AIIgnore`, `@AIContract`+`@AIDraft` contradiction, `@AIContract`+`@AILocked` overlap, `@AITestDriven`+`@AIIgnore` contradiction, `@AITestDriven`+`@AILocked` contradiction, invalid `@AITestDriven.coverageGoal`)
- `OrphanWarner` — emits warnings when annotations are used but the corresponding ignore-file isn't present (e.g. `@AIIgnore` without `.cursorignore`)
- `ServiceRegistry` — maps logical service keys to file paths and resolves which services are "active" via the file-existence opt-in
- `ElementNaming` — pure helpers for `elementPath`, `elementDisplayName`, `owningElement`
- `GuardrailContentBuilder` — A highly decoupled, thin coordinator (~150 lines) that builds AI guardrail files by delegating file rendering to specific `PlatformRenderer` implementations. Coordinates the build process, lazily allocates platform StringBuilders, and returns the final service-key → content map. No I/O.
- `GuardrailFileWriter` — atomic, marker-aware file writes, YAML front-matter preservation, legacy (pre-marker) block migration, and orphan cleanup for granular rule files. Since 0.7.1 also owns the cache-fast-path entry to `writeFileIfChanged` and a streaming byte-compare for non-marker files.
- `GranularRulesWriter` — writes per-class `.mdc`/`.md` files for Cursor / Trae / Roo / Windsurf / Continue / Tabnine / Amazon Q / Amazon Kiro / `.ai/rules` and orchestrates orphan cleanup via the file writer
- `WriteCache` — per-output-file content cache backed by a `.vibetags-cache` sidecar at the project root; lets `GuardrailFileWriter` skip the read+compare path on no-change rebuilds. **Detailed below in [Design Decision 5](#5-write-cache-since-071).** _(since 0.7.1)_

This split keeps each helper around 50–600 lines, well-tested in isolation, and makes the orchestrator's `generateFiles()` method a 50-line read.

---

## Build Sequence

![Build Sequence](diagrams/build-sequence.png)

*Figure 3: Sequence diagram of annotation processing during compilation*

### Processing Phases

**Phase 1: Element Accumulation (every round)**
```java
lockedElements.addAll(roundEnv.getElementsAnnotatedWith(AILocked.class));
// ... repeat for every annotation type
validateAnnotations(processingEnv.getMessager(), roundEnv);
return false; // do not claim annotations
```
- Accumulates annotated elements into `LinkedHashSet`s across all rounds
- Validates annotations each round for early compiler feedback
- Returns `false` so other processors still see the annotations

**Phase 1b: Generation Trigger**
```java
if (roundEnv.processingOver() && !processed) {
    generateFiles();
    processed = true;
}
```
- `generateFiles()` runs exactly once, on the final round when `processingOver()` is true
- Idempotency guard (`processed` flag) prevents double-generation

**Phase 2: Validation**
```java
validateAnnotations(processingEnv.getMessager(), roundEnv);
```
- Checks for contradictory annotations (@AIDraft + @AILocked on same element)
- Warns about empty @AIAudit (no checkFor items)
- Emits compiler warnings via `Messager`

**Phase 3: Service Resolution**
```java
Map<String, Path> serviceFiles = buildServiceFileMap(root);
Set<String> activeServices = resolveActiveServices(messager, serviceFiles);
```
- Maps 17+ service keys to file paths
- Checks file existence (file presence = opt-in)
- Only active services get generated

**Phase 4: Content Generation**
- Iterates each annotation type
- Accumulates platform-specific content in StringBuilders
- Formats output per platform conventions (Markdown, XML, TOML, JSON)

**Phase 5: File Writing**
```java
boolean changed = fileWriter.writeFileIfChanged(filePath, content, hasNewRules);
```
Three layered fast paths in front of the actual write, in order of cheapness:
1. **Cache fast path** _(0.7.1)_ — if `WriteCache.isUnchanged(file, body)` is true (size + mtime + 32-bit fingerprint match what we recorded last build), return immediately. No file read, no compare, no write.
2. **Streaming byte-compare fast path** _(0.7.1, non-marker files only)_ — when the on-disk byte length matches the new content's byte length exactly, stream-compare with early exit on first byte mismatch. Avoids materialising the entire file as a `String`.
3. **Read-and-compare path** — `Files.readString` + strip-tolerant `.equals()`, the original logic. Used for marker files (`.md`, `.mdc`, `llms*.txt`) and non-marker files where the size already differs by ≤64 bytes (whitespace tolerance).

After a successful write or a streaming-byte-equal hit, `WriteCache.recordWrite(...)` updates the cache entry. After all platform files are processed, `generateFiles()` calls `writeCache.flush()` once to persist the sidecar atomically.

`Messager` emits NOTE: `"updated"` or `"no changes"` for each file.

**Phase 6: Orphaned Annotation Check**
```java
checkOrphanedAnnotations(messager, activeServices, ...);
```
- Warns if annotations used but recommended files missing
- Example: @AIIgnore used but .qwenignore missing

---

## Data Flow

![Data Flow](diagrams/data-flow.png)

*Figure 4: Detailed data flow through the annotation processor*

### Annotation Processing Details

**@AILocked Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AILocked.class)) {
    AILocked locked = element.getAnnotation(AILocked.class);
    String className = element.toString();
    String reason = locked.reason();
    
    // Append to all platforms
    cursorRules.append("* `").append(className).append("` - Reason: ").append(reason).append("\n");
    qwenMd.append("* `").append(className).append("` — ").append(reason).append("\n");
    // ... other platforms
}
```

**@AIContext Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AIContext.class)) {
    AIContext context = element.getAnnotation(AIContext.class);
    String className = element.toString();
    
    // Platform-specific formatting
    cursorRules.append("* `").append(className).append("`\n")
               .append("  * Focus: ").append(context.focus())
               .append("\n  * Avoid: ").append(context.avoids()).append("\n");
}
```

**@AIIgnore Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AIIgnore.class)) {
    String className = element.toString();
    
    // Write to ignore sections
    qwenIgnore.append("* `").append(className).append("`\n");
    
    // Write glob patterns to standalone ignore files
    String globPattern = "**/"+ element.getSimpleName() + ".java\n";
    qwenIgnoreFile.append(globPattern);
}
```

**@AIAudit Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AIAudit.class)) {
    AIAudit audit = element.getAnnotation(AIAudit.class);
    String className = element.toString();
    String[] checkFor = audit.checkFor();
    
    // Platform-specific audit format
    qwenAudit.append("* `").append(className).append("`\n");
    qwenAudit.append("  - Required Checks: ").append(String.join(", ", checkFor)).append("\n");
}
```

**@AIPrivacy Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AIPrivacy.class)) {
    AIPrivacy privacy = element.getAnnotation(AIPrivacy.class);
    String elementPath = element.toString();
    String reason = privacy.reason();

    // Claude: XML pii_guardrails block
    claudePrivacy.append("    <element path=\"").append(elementPath).append("\">\n");
    claudePrivacy.append("      <reason>").append(reason).append("</reason>\n");
    claudePrivacy.append("    </element>\n");

    // Cursor / Codex / Copilot / Gemini / Qwen: Markdown list
    cursorPrivacy.append("* `").append(elementPath).append("` — ").append(reason).append("\n");
}

// After the loop, if hasPrivacyAnnotations == true, finalize PII sections for all platforms
// Claude gets <pii_guardrails> XML + <rule> about never logging values
// Others get a "## 🔐 PII GUARDRAILS" Markdown section
```

---

## Platform Output Formats

![Platform Outputs](diagrams/platform-output.png)

*Figure 5: Same annotation data formatted for different AI platforms*

### Platform-Specific Format Examples

**Qwen (QWEN.md)** - Clean Markdown:
```markdown
# PROJECT CONTEXT
# Generated by VibeTags v1.0.0-SNAPSHOT | https://github.com/PIsberg/vibetags

## LOCKED FILES (DO NOT EDIT)
* `com.example.PaymentProcessor` — Critical payment logic

## CONTEXTUAL RULES
* `com.example.StringParser`
  * Focus: Memory optimization
  * Avoid: java.util.regex

## 🛡️ MANDATORY SECURITY AUDITS
* `com.example.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety

## IGNORED ELEMENTS
* `com.example.GeneratedMetadata`
```

**Claude (CLAUDE.md)** - XML Structure:
```xml
<!-- Generated by VibeTags v1.0.0-SNAPSHOT | https://github.com/PIsberg/vibetags -->
<project_guardrails>
  <locked_files>
    <file path="com.example.PaymentProcessor">
      <reason>Critical payment logic</reason>
    </file>
  </locked_files>
  <audit_requirements>
    <file path="com.example.DatabaseConnector">
      <vulnerability_check>SQL Injection</vulnerability_check>
      <vulnerability_check>Thread Safety</vulnerability_check>
    </file>
  </audit_requirements>
</project_guardrails>
<rule>Never propose edits to locked files.</rule>
```

**Cursor (.cursorrules)** - Markdown with Emoji:
```markdown
# AUTO-GENERATED AI RULES
# Generated by VibeTags v1.0.0-SNAPSHOT | https://github.com/PIsberg/vibetags

## LOCKED FILES (DO NOT EDIT)
* `com.example.PaymentProcessor` - Reason: Critical payment logic

## 🛡️ MANDATORY SECURITY AUDITS
* `com.example.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety
```

**.qwen/settings.json** - JSON Configuration:
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

**.qwenignore** - Glob Patterns:
```
# AUTO-GENERATED BY VIBETAGS
# Generated by VibeTags v1.0.0-SNAPSHOT | https://github.com/PIsberg/vibetags
# Qwen-specific exclusion list.
**/GeneratedMetadata.java
```

---

## Core Components

### Annotations

All annotations use `@Retention(RetentionPolicy.SOURCE)` — they exist only at compile time and are stripped from final bytecode.

The full table of all 39 annotations (targets, attributes, semantics) and every compile-time validation warning the processor emits now live in one place: **[docs/ANNOTATIONS.md](ANNOTATIONS.md)**.

### Annotation Processor

**Class:** `se.deversity.vibetags.processor.AIGuardrailProcessor`

**Key Characteristics:**
- Extends `javax.annotation.processing.AbstractProcessor` (JSR 269)
- Registered via SPI: `META-INF/services/javax.annotation.processing.Processor`
- Supports Java 11+ source versions
- Uses `@SupportedAnnotationTypes("*")` to process all annotations
- **Thin orchestrator** (~230 lines): all the actual work lives in `internal/*` helpers

**Processing Logic:**

```
Accumulation phase (every round, until processingOver() == true):
1. AnnotationCollector.collect(roundEnv) — drains the round into the LinkedHashSet<Element> accumulators (one per annotation type)
2. AnnotationValidator.validate(messager, roundEnv) — compile-time checks:
   - Contradiction checks (e.g. @AIDraft + @AILocked, @AILegacyBridge + @AIDraft)
   - Redundancy checks (e.g. @AIPrivacy + @AIIgnore, @AIPublicAPI + @AILocked, @AIParallelTests + @AILocked)
   - Config validation (e.g. empty @AIAudit, empty @AIArchitecture, invalid @AITestDriven coverageGoal)
3. process() returns false so other processors still see the annotations

Generation phase (once, on the round where processingOver() == true):
4. ServiceRegistry.buildServiceFileMap(root) → service-key → file-path map
5. ServiceRegistry.resolveActiveServices(messager, files) → file-existence opt-in
6. GuardrailContentBuilder.build() lazily allocates the platform StringBuilders via `initBuilders()`, then delegates file rendering to modular, stateless `PlatformRenderer` implementations (under `se.deversity.vibetags.processor.internal.content.platforms.*`). Renderers orchestrate formatters from `FormatterRegistry` to build precise Markdown, XML, TOML, or Starlark files, and return the final service-key → content map. No I/O.
7. GuardrailFileWriter.writeFileIfChanged(...) for each active service — three-layer fast path (WriteCache hit → streaming byte-compare → readString + strip-equals); marker-aware updates, YAML front-matter preservation, atomic tmp+move writes; on success records the new fingerprint in WriteCache
8. GranularRulesWriter.writeAll(...) — per-class .mdc/.md for Cursor / Trae / Roo / Windsurf / Continue / Tabnine / Amazon Q / Amazon Kiro / .ai/rules / Claude Code / GitHub Copilot
9. GranularRulesWriter.cleanupAll(...) — remove orphaned granular files (skipping the names just written, to avoid delete-then-recreate cycles; invalidates the WriteCache entry for any file it deletes or rewrites)
10. OrphanWarner.warnAboutOrphans(...) — warn if annotations used without the corresponding ignore-file (e.g. @AIIgnore without .cursorignore)
11. WriteCache.flush() — atomically persist the .vibetags-cache sidecar (no-op if no entries changed this build)
```

**Output File Generation:** the processor writes one file per active service; the full file ↔ platform ↔ format table (65 output paths as of this writing) is maintained in one place: **[docs/PLATFORMS.md](PLATFORMS.md)**.

### Generated Output Files

#### Example: @AIAudit Output

**Source:**
```java
@AIAudit(checkFor = {"SQL Injection", "Thread Safety issues"})
public class DatabaseConnector { }
```

**Generated in QWEN.md:**
```markdown
## 🛡️ MANDATORY SECURITY AUDITS
When proposing edits or writing code for the following files, you MUST perform a security review. Explicitly state that you have audited the changes for the listed vulnerabilities.

* `com.example.database.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety issues
```

**Generated in CLAUDE.md:**
```xml
<audit_requirements>
  <file path="com.example.database.DatabaseConnector">
    <vulnerability_check>SQL Injection</vulnerability_check>
    <vulnerability_check>Thread Safety issues</vulnerability_check>
  </file>
</audit_requirements>
<rule>If you are asked to modify any file listed in <audit_requirements>, you must first silently analyze your proposed code for the listed vulnerabilities.</rule>
```

**Generated in .cursorrules:**
```markdown
## 🛡️ MANDATORY SECURITY AUDITS
* `com.example.database.DatabaseConnector`
  - Required Checks: SQL Injection, Thread Safety issues
```

---

## Build Flow

### Maven Flow

```
mvn clean compile
    ↓
Resolve vibetags-annotations on compile classpath
   + vibetags-processor on annotationProcessorPaths
   (versions supplied by vibetags-bom import)
    ↓
javac discovers processor via META-INF/services/
    ↓
Compile Java sources
    ↓
AIGuardrailProcessor.process() executes
    ↓
Validate annotations
    ↓
Resolve active services (file-existence opt-in)
    ↓
Generate 15+ AI config files at project root
    ↓
Compilation complete
```

### Gradle Flow

```
gradle clean build
    ↓
Resolve vibetags-annotations (compileOnly)
   + vibetags-processor (annotationProcessor)
   (versions supplied by platform('vibetags-bom'))
    ↓
javac with explicit annotation processor path
    ↓
Compile Java sources
    ↓
AIGuardrailProcessor.process() executes
    ↓
Generate AI config files
    ↓
Build complete
```

---

## Directory Structure

```
vibetags/
├── vibetags-annotations/              # Published as se.deversity.vibetags:vibetags-annotations
│   ├── src/main/java/se/deversity/vibetags/annotations/
│   │   ├── AILocked.java
│   │   ├── AIContext.java
│   │   ├── ...                          # 39 annotation @interface files in total — see ../README.md#project-facts
│   │   └── AITemporary.java
│   ├── pom.xml
│   └── build.gradle
│
├── vibetags/                          # Published as se.deversity.vibetags:vibetags-processor
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/se/deversity/vibetags/processor/
│   │   │   │   ├── AIGuardrailProcessor.java     # JSR 269 orchestrator (~230 lines)
│   │   │   │   ├── VibeTagsLogger.java           # SLF4J/Logback file logger
│   │   │   │   └── internal/                     # Single-responsibility helpers
│   │   │   │       ├── AnnotationCollector.java       # one LinkedHashSet per annotation type, per round
│   │   │   │       ├── AnnotationValidator.java       # Compile-time consistency warnings
│   │   │   │       ├── OrphanWarner.java              # "annotation used but ignore-file missing"
│   │   │   │       ├── ServiceRegistry.java           # Service map + file-existence opt-in
│   │   │   │       ├── ElementNaming.java             # elementPath / displayName helpers
│   │   │   │       ├── GuardrailContentBuilder.java   # Thin coordinator delegating to PlatformRenderers
│   │   │   │       ├── GuardrailFileWriter.java       # Marker-aware atomic writes + cache + streaming compare
│   │   │   │       ├── GranularRulesWriter.java       # Per-class .mdc/.md + orphan cleanup
│   │   │   │       └── WriteCache.java                # 0.7.1: per-file content cache (.vibetags-cache sidecar)
│   │   │   └── resources/META-INF/services/
│   │   │       └── javax.annotation.processing.Processor
│   │   └── test/                      # Unit + integration tests (424 tests total)
│   │       └── processor/
│   │           ├── AnnotationDefinitionsTest.java
│   │           ├── AIGuardrailProcessorTest.java
│   │           ├── AIGuardrailProcessorUnitTest.java
│   │           ├── AIGuardrailProcessorProcessTest.java
│   │           ├── AIIgnoreProcessorUnitTest.java
│   │           ├── AIPrivacyProcessorTest.java
│   │           ├── AIContractProcessorTest.java               # 0.7.0: @AIContract coverage
│   │           ├── CleanupGranularDirectoryTest.java          # 0.6.0: orphan-removal coverage
│   │           ├── WriteFileFrontMatterTest.java              # 0.6.0: YAML front-matter coverage
│   │           ├── StripLegacyVibeTagsBlockEdgeCasesTest.java # 0.6.0: legacy migration edges
│   │           ├── WriteCacheTest.java                        # 0.7.1: cache hit/miss/invalidation
│   │           ├── WriteCacheProcessorIntegrationTest.java    # 0.7.1: cache E2E via processor
│   │           ├── StreamingByteCompareTest.java              # 0.7.1: fileBytesEqual helper
│   │           ├── GuardrailFileWriterCoverageTest.java       # 0.7.1: streaming + noopMessager
│   │           ├── QwenProcessorUnitTest.java
│   │           ├── NewPlatformsEndToEndTest.java
│   │           ├── GranularRulesEndToEndTest.java
│   │           ├── MultiModuleStabilityTest.java
│   │           ├── AnnotationProcessorEndToEndTest.java
│   │           └── QwenEndToEndTest.java
│   ├── pom.xml                        # Maven build config (depends on vibetags-annotations)
│   └── build.gradle                   # Gradle build config
│
├── vibetags-bom/                      # Published as se.deversity.vibetags:vibetags-bom (pom-only)
│   └── pom.xml                        # <dependencyManagement> for vibetags-annotations + vibetags-processor
│
├── example/                           # Demo e-commerce application
│   ├── src/main/java/com/example/
│   │   ├── database/
│   │   │   └── DatabaseConnector.java         # @AIAudit example
│   │   ├── internal/
│   │   │   └── GeneratedMetadata.java         # @AIIgnore example
│   │   ├── payment/
│   │   │   └── PaymentProcessor.java          # @AILocked example
│   │   ├── security/
│   │   │   └── SecurityConfig.java            # @AILocked + @AIContext
│   │   └── ...                                # More examples
│   ├── QWEN.md                        # Generated: Qwen project context
│   ├── .qwen/                         # Generated: Qwen directory
│   │   ├── settings.json              # Generated: Qwen model settings
│   │   └── commands/
│   │       └── refactor.md            # Generated: Qwen custom command
│   ├── .qwenignore                    # Generated: Qwen exclusion list
│   ├── .cursorrules                   # Generated: Cursor rules
│   ├── CLAUDE.md                      # Generated: Claude guardrails
│   ├── llms.txt                       # Generated: llms.txt standard (concise map)
│   ├── llms-full.txt                  # Generated: llms.txt standard (full reference)
│   └── ...                            # Other AI config files
│
├── docs/                              # Documentation
│   ├── ARCHITECTURE.md                # This file
│   └── diagrams/                      # PlantUML source + PNG images
│       ├── class-diagram.puml
│       ├── class-diagram.png
│       ├── build-sequence.puml
│       ├── build-sequence.png
│       ├── component-diagram.puml
│       ├── component-diagram.png
│       ├── data-flow.puml
│       ├── data-flow.png
│       ├── platform-output.puml
│       └── platform-output.png
│
├── .gitignore
├── README.md
└── package.json
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

**Decision:** One processor generates all 17+ output files in a single pass

**Rationale:**
- Single source of truth for annotation data
- Consistent content across all platforms
- No duplication of parsing logic
- Atomic generation (all or nothing)

**Internal split (since 0.6.0):** the single SPI entry point (`AIGuardrailProcessor`) is now a thin orchestrator. The actual work is divided across eight focused helpers in `internal/`: a collector for accumulation, a validator and an orphan warner for compile-time warnings, a registry for service↔file mapping and the file-existence opt-in, a builder for per-platform string assembly, and two writers (one general, one granular) for atomic file I/O. This keeps each class testable in isolation while preserving the "one processor, single pass" property externally.

### 3. File-existence Opt-in Model

**Decision:** The annotation processor uses the presence of specific files on disk to determine which AI services are active.

**Implementation:**
```java
static Set<String> resolveActiveServices(Messager messager, Map<String, Path> allServiceFiles) {
    Set<String> optInKeys = Set.of(
        "cursor", "claude", "aiexclude", "codex", "gemini", "copilot", "qwen",
        "cursor_ignore", "claude_ignore", "copilot_ignore", "qwen_ignore",
        "llms", "llms_full"
    );

    return allServiceFiles.entrySet().stream()
        .filter(e -> optInKeys.contains(e.getKey()))
        .filter(e -> Files.exists(e.getValue()))
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
}
```

**Rationale:**
- **Manual Control**: Developers decide which AI tools they support
- **No Clutter**: VibeTags never creates files for unused AI tools
- **Zero Configuration**: No complex config needed — `touch` or `rm` is sufficient

### 4. Write-if-Changed Logic

**Decision:** Only write files when content actually differs.

**Implementation** (current, after 0.7.1 layered fast paths):
```java
boolean writeFileIfChanged(String path, String content, boolean hasNewRules) {
    Path file = Paths.get(path);

    // Fast path 1: WriteCache hit — size + mtime + 32-bit fingerprint match
    if (writeCache != null && writeCache.isUnchanged(file, content)) {
        return false;
    }

    // Fast path 2 (non-marker files): streaming byte-compare with early exit
    if (!supportsMarkers && fileExists && existingSize == contentByteLen) {
        if (fileBytesEqual(file, contentBytes)) {
            writeCache.recordWrite(file, content);
            return false;
        }
        // sizes match but bytes differ → write directly, no readString needed
    }

    // Slow path: Files.readString + strip-tolerant equals (marker files,
    // or non-marker files where size differs by ≤64 bytes)
    String existing = Files.readString(file, UTF_8);
    if (existing.strip().equals(finalContent.strip())) return false;

    writeContentWithBackup(file, finalContent); // tmp + atomic-move
    writeCache.recordWrite(file, content);
    return true;
}
```

**Rationale:**
- Prevents unnecessary file system writes
- Avoids triggering file watchers
- Preserves file modification timestamps
- Git-friendly (no false-positive changes)
- Three-layer fast path means warm-cache no-change rebuilds skip nearly all I/O

### 5. Write Cache (since 0.7.1)

**Decision:** Maintain a per-output-file content cache in `.vibetags-cache` at the project root, looked up before any read or write inside `writeFileIfChanged`.

**What it stores** — one tab-separated row per generated file:
```
<absolute-path>\t<8-char-fingerprint>\t<size-bytes>\t<mtime-millis>
```

**The fingerprint is `String.hashCode()`**, not SHA-256 or CRC32C. Why:
- 32-bit collision space matches CRC32; for two non-adversarial VibeTags bodies the collision probability is 2⁻³² ≈ 1 in 4 billion. Size and mtime are checked first as independent guards, so a hash collision can only cause us to skip writing identical content — never silently corrupt output.
- Cached internally on the `String` after first computation → O(1) on subsequent lookups for the same reference.
- HotSpot intrinsifies `String.hashCode()` on x86 with vectorised instructions for the first computation.
- Crucially: **no UTF-8 byte array materialisation per call.** An earlier CRC32C-of-bytes design allocated a fresh `byte[s.length()]` per cache lookup — for a 1 MB body that's 1 MB of garbage per hit, defeating the cache's allocation-saving purpose.

**Lookup** (`WriteCache.isUnchanged`) — single `Files.readAttributes(BasicFileAttributes.class)` for size + mtime, then fingerprint compare. ~10 µs per call on warm-cache local SSD; constant time regardless of body size.

**Persistence** — loaded lazily on first lookup, written atomically once at the end of `generateFiles()` via tmp+move. Safe to delete (rebuilt on the next compile); gitignored.

**Invalidation** — `mtime` change (user edited the file), `size` change, file deletion, or fingerprint mismatch all bypass the cache and fall through to the read-and-compare path. The granular-rules orphan cleanup explicitly invalidates the cache for any file it deletes or rewrites outside the marker block.

**Measured impact** (`WriteCacheHitBenchmark` in `load-tests/`, JMH AverageTime + GC profiler, 100-call batches):

| Body | File type | cache hit | no cache | wall-clock | allocation |
|---|---|---:|---:|---:|---:|
| 1 KB | `.md` | 16.4 µs | 208.5 µs | **13×** | **15×** |
| 12 KB | `.md` | 18.1 µs | 262.7 µs | **15×** | **135×** |
| 1 MB | `.md` | 18.6 µs | 3 405 µs | **183×** | **11 159×** |

Cache-hit cost is bounded by the single stat syscall — flat curves regardless of body size. The no-cache path scales linearly with body size because it must `readString` the entire file.

### 6. Streaming Byte-Compare for Non-Marker Files (since 0.7.1)

**Decision:** When a non-marker output file exists at exactly the new content's byte length, stream-compare bytes with early exit on first mismatch instead of materialising the full file as a `String` for `.equals()`.

**Where it applies** — `.cursorignore`, `.aiderignore`, `.aiexclude`, ignore-style files, `.json`/`.toml` configs. Marker files (`.md`, `.mdc`, `llms*.txt`) keep the `readString` path because they need the full string for marker-position parsing and front-matter handling.

**Implementation** — `GuardrailFileWriter.fileBytesEqual(Path, byte[])` reads through an 8 KB buffered `InputStream` and compares byte-by-byte against the expected array. Caller has already verified `Files.size(file) == expected.length` — early return on size mismatch is the existing `nonMarkerSizeMismatch` check.

**Rationale:**
- Avoids a multi-MB `String` allocation when the file matches.
- Finds mismatches in the first kilobyte without reading the rest of the file.
- Strip-tolerant `readString` path is still used for ≤64-byte size differences (handles trailing-whitespace drift).

### 7. Pre-sized Per-Platform StringBuilders (since 0.7.1)

**Decision:** `GuardrailContentBuilder` pre-allocates the nine main per-platform buffers based on the collected element count instead of relying on `StringBuilder`'s default 16-char capacity.

**Implementation** — `mainBuilderHint()` returns `clamp(4096, 1500 × elementCount, 256·1024)`:
- Floor of 4 KB so empty/small projects don't waste cycles on grows.
- ~1500 chars per annotated element across all sections (Locked/Context/Audit/Draft/Privacy/Core/Performance/Contract/Ignore).
- Cap of 256 KB so a hypothetical 10 000-element codebase doesn't pre-allocate megabytes per platform across the ~12 active platforms.

**Affected buffers:** `cursorRules`, `claudeMd`, `codexAgents`, `copilot`, `qwenMd`, `windsurfRules`, `zedRules`, `llmsTxt` (sized larger because it aggregates), `llmsFullTxt` (same).

**Rationale:**
- Eliminates the log₂(N) `char[]` grow-and-copy passes that the eight per-annotation `appendXxx()` loops previously triggered as content accumulated.
- Output is byte-identical to prior versions — verified by all 75 end-to-end snapshot tests on every release commit.

### 6. Wildcard Annotation Matching

**Decision:** `@SupportedAnnotationTypes("*")`

**Rationale:**
- Automatically picks up new annotations without code changes
- Single processor handles all VibeTags annotations
- Easy to extend with new annotation types

### 7. Version Stamping

**Decision:** Every generated file includes version header:
```
# Generated by VibeTags v1.0.0-SNAPSHOT | https://github.com/PIsberg/vibetags
```

**Rationale:**
- **Traceability**: Identifies processor version
- **Debugging**: Simplifies troubleshooting
- **Attribution**: Links back to source repository

### 8. Smart Validation Layer

**Decision:** Processor performs lightweight validation and emits compiler WARNINGs

**Supported Checks:**
- `@AIDraft + @AILocked`: Warns about contradictory annotations
- Empty `@AIAudit`: Warns if no checkFor items
- `@AIPrivacy + @AIIgnore`: Warns that `@AIPrivacy` is redundant — `@AIIgnore` already hides the element from AI
- `@AIContract + @AIDraft`: Warns that the combination is contradictory — a frozen signature cannot also need drafting
- `@AIContract + @AILocked`: Warns that the combination has overlapping intent — `@AILocked` already prohibits all modifications
- Orphaned annotations: Warns if recommended files missing

**Example:**
```
[WARNING] VibeTags: @AIIgnore used but .qwenignore is missing for Qwen support. Consider creating it.
[WARNING] VibeTags: myField is annotated with both @AIPrivacy and @AIIgnore. @AIIgnore already excludes the element from AI context; @AIPrivacy is redundant.
```

---

## Testing Strategy

### Unit Tests (vibetags/)

| Test Class | Tests | Purpose |
|---|---|---|
| `AnnotationDefinitionsTest` | 40 | Verify annotation structure, retention policies, targets, and defaults (the original annotation set; newer annotations are covered by the `NewAnnotations*` definition tests) |
| `AIGuardrailProcessorTest` | 3 | Processor configuration (@SupportedAnnotationTypes, source version) |
| `AIGuardrailProcessorUnitTest` | 40 | Processor logic: resolveActiveServices, writeFileIfChanged, checkOrphanedAnnotations, validateAnnotations, stripLegacyVibeTagsBlock basics |
| `AIGuardrailProcessorProcessTest` | 64 | process() method: annotation accumulation, PII sections, orphaned annotation warnings, write-if-changed, marker-based updates, llms.txt opt-in, aider opt-in |
| `AIIgnoreProcessorUnitTest` | 11 | @AIIgnore annotation definition and opt-in behavior |
| `AIPrivacyProcessorTest` | 15 | @AIPrivacy: generated content for all platforms, @AIPrivacy+@AIIgnore redundancy warning, no-op when no annotations |
| `AIContractProcessorTest` | 15 | @AIContract: annotation definition, @AIContract+@AIDraft and @AIContract+@AILocked validation warnings, per-platform content (Cursor, Claude, Codex, Gemini, Copilot, Qwen, llms.txt, Aider), no-op when absent |
| `CleanupGranularDirectoryTest` | 8 | (0.6.0) Orphan removal: marker stripping, boilerplate-only deletion, human-content preservation, excludeQNames, YAML front-matter |
| `WriteFileFrontMatterTest` | 4 | (0.6.0) Markers placed AFTER YAML front-matter on .mdc files; hash-marker fallback for .aiderignore-style files |
| `StripLegacyVibeTagsBlockEdgeCasesTest` | 7 | (0.6.0) XML-closer detection edge cases: both `</rule>` and `</project_guardrails>`, multi-paragraph human content, bare-header detection |
| `WriteCacheTest` | 15 | (0.7.1) `WriteCache`: hit, miss-on-different-body, mtime/size/delete invalidation, persistence across instances, corrupt-cache fallback, recordWrite-on-missing-file, flush-on-unwritable-parent |
| `WriteCacheProcessorIntegrationTest` | 3 | (0.7.1) Cache E2E via processor: `.vibetags-cache` is created on first compile; second compile against unchanged sources keeps file mtimes stable; external edit invalidates the entry and triggers a rewrite that preserves user content above the marker block |
| `StreamingByteCompareTest` | 8 | (0.7.1) `GuardrailFileWriter.fileBytesEqual`: exact match, first-/last-byte mismatch, empty file, 256 KB random, 64 KB with one bit flipped, multi-byte UTF-8, exact 8 KB buffer-boundary |
| `GuardrailFileWriterCoverageTest` | 4 | (0.7.1) Streaming-cache hit records cache entry; size match + byte mismatch + `!hasNewRules` skips; same with `hasNewRules=true` writes; all four `noopMessager` overloads return silently |
| `QwenProcessorUnitTest` | 15 | Qwen-specific: service file map, active resolution, file generation, settings JSON validation |
| `NewPlatformsEndToEndTest` | 29 | (0.7.0) Windsurf, Zed, Cody, Supermaven, Continue, Tabnine, Amazon Q, `.ai/rules/` E2E |
| `AnnotationProcessorEndToEndTest` | 75 | End-to-end snapshot net: compile example, verify all generated files and content across all 9 annotation types × all platforms (the safety net for `GuardrailContentBuilder` extraction) |
| `GranularRulesEndToEndTest` | 9 | Cursor/Trae/Roo granular rule file generation, orphaned file cleanup |
| `QwenEndToEndTest` | 19 | Qwen end-to-end: QWEN.md structure, settings.json format, .qwenignore patterns, version stamping |
| `MultiModuleStabilityTest` | 3 | Multi-module safety: no-annotation module preserves sibling module content |
| `VibeTagsLoggerUnitTest` | 10 | File logging: log level filtering, file rotation, shutdown |
| `AIGuardrailProcessorIntegrationTest` | 23 | Full workflow with backup/restore (conditional, requires `-Drun.integration.tests=true`) |

**Total: 424 tests** (410 after 0.7.0; 0.7.1 added 9 new tests for the cache + streaming compare + writer coverage gaps, and corrected the existing tally as `WriteCacheTest` grew from 10 → 15 during coverage hardening).

**JMH benchmarks** (under `load-tests/`, not counted above):
- `ProcessorHotPathBenchmark` — 6 benchmarks: `buildServiceFileMap`, `resolveActiveServices_{all,none}Present`, `writeFileIfChanged_{noChange,smallWrite,largeWrite}`. Run on every release-tagged baseline.
- `WriteCacheHitBenchmark` _(0.7.1)_ — 8 benchmarks proving the cache: `(small=1KB, medium=12KB, large=1MB) × (marker .md, non-marker .cursorrules) × (cacheHit, noCache)` minus the four cache-hit cases at the same body size that are constant-time. Plots in `load-tests/results/_plots/cache-hit-{time,alloc}.png`.

### Concurrency & Thread-Isolated Logging

To run all 724+ unit and integration tests concurrently without static resource conflicts, the VibeTags test suite leverages a thread-isolated execution architecture under JUnit 5.

#### 1. JUnit 5 Parallel Test Execution
Tests are run fully concurrently at both the class and method levels. This is configured in [junit-platform.properties](../vibetags/src/test/resources/junit-platform.properties):
```properties
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.mode.default = concurrent
junit.jupiter.execution.parallel.mode.classes.default = concurrent
```

#### 2. Thread-Isolated Logger Contexts
Because tests initialize compiler environments dynamically, multiple threads compile and write logs concurrently. To prevent parallel threads from overwriting each other's Logback appenders or locking file handles, VibeTags partitions logging context using **absolute path hashing**:
- **Logger Name Suffixing**: The logger name is programmatically appended with a hash of the absolute normalized path of the compilation project root:
  ```java
  private static String getLoggerName(Path projectRoot) {
      if (projectRoot == null) return LOGGER_NAME;
      return LOGGER_NAME + "." + Math.abs(projectRoot.toAbsolutePath().normalize().hashCode());
  }
  ```
- **Context Partitioning**: Programmatically isolates logging configurations dynamically (e.g. `se.deversity.vibetags.491083`), detaching and closing previous appenders to prevent double-output during incremental compiles.
- **FS Isolation Verification**: Handled by `VibeTagsLoggerConcurrencyTest`, which spins up concurrent execution loops to verify thread safety and filesystem isolation.

### Test Patterns

**Mockito Mocking:**
```java
Messager messager = mock(Messager.class);
RoundEnvironment roundEnv = mock(RoundEnvironment.class);
Element element = mock(Element.class);
```

**Capturing Messager:**
```java
List<String> warnings = new ArrayList<>();
Messager messager = capturingMessager(Diagnostic.Kind.WARNING, warnings);
// Assert warnings contain expected messages
```

**Temp Directories:**
```java
@Test
void testResolveActiveServices(@TempDir Path tempDir) throws IOException {
    Files.createFile(tempDir.resolve("QWEN.md"));
    // Test with isolated file system
}
```

### CI/CD

GitHub Actions workflow tests:
- **Maven builds:** JDK 21, 25, 26
- **Gradle builds:** JDK 21, 25, 26
- Verifies generated file existence
- Validates content in all outputs
- Code coverage via Codecov

---

## Limitations

### 1. Output Location Defaults to JVM Working Directory

**Default:** Uses `Paths.get("")` which resolves to the JVM working directory

**Impact of default:**
- Can write to wrong directory in IDE builds that don't set cwd to project root
- Breaks if build is invoked from a subdirectory

**Resolution:** Pass `-Avibetags.root=<path>` via `<compilerArg>` in Maven or `annotationProcessorArgs` in Gradle to override the output directory explicitly. Most IDE integrations need this set.

### 2. No Gradle Incremental-Annotation-Processing Registration

**Problem:** Not registered as `META-INF/gradle/incremental.annotation.processors`. Gradle therefore treats VibeTags as a non-incremental processor and recompiles every annotated source on each round.

**What's already mitigated:** The `WriteCache` (since 0.7.1) avoids the file-write side of the cost — when no annotations changed, generated files are byte-stable and no I/O happens. See [Design Decision 5](#5-write-cache-since-071) above. The remaining gap is purely on the `javac`/Gradle side: input-source recompilation isn't yet skipped.

**Why we haven't registered:**
- VibeTags is structurally an aggregating processor (it needs to see the full picture across all rounds to compute orphan cleanup and shared platform files like `llms.txt`). Aggregating processors are supported by Gradle but the registration changes the contract: every modified source triggers a full processor rerun.
- Combined with the cache, the practical wall-clock win over the current behaviour is small.

**Workaround for now:** in Gradle, `gradle compileJava --no-daemon -PskipVibeTags=true` can be approximated by compiling without the annotation-processor path; the cache then preserves the existing files on the next regular build.

### 3. Hardcoded Output Formats

**Problem:** Each platform's format is hardcoded in the processor

**Impact:**
- Cannot customize template structure
- Adding new platforms requires code changes
- No user control over formatting

### 4. Limited Validation Logic

**Problem:** Basic validation only (contradictions, empty arrays)

**Impact:**
- Complex contradictory logic might slip through
- No enforcement of cross-file consistency beyond basic checks

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
- **Enhanced validation** for annotation misuse

---

## Dependencies

### vibetags-annotations

| Dependency | Scope | Purpose |
|---|---|---|
| (none) | — | Pure `@interface` declarations on top of `java.lang.annotation.*` |

### vibetags-processor

| Dependency | Scope | Purpose |
|---|---|---|
| `se.deversity.vibetags:vibetags-annotations` | compile | Annotation classes referenced symbolically by the processor (`AILocked.class`, …) and surfaced transitively to legacy single-coordinate consumers |
| `org.slf4j:slf4j-api` | compile | Processor-internal logging API |
| `ch.qos.logback:logback-classic` | compile | File appender that writes `vibetags.log` |
| `javax.annotation.processing.*` | JDK (compile) | JSR 269 API |
| `javax.lang.model.*` | JDK (compile) | Language model API |
| `org.junit.jupiter` | test | Unit testing |
| `org.mockito` | test | Mocking framework |

### example (recommended layout)

| Dependency | Scope | Purpose |
|---|---|---|
| `se.deversity.vibetags:vibetags-bom` | `<scope>import</scope>` / `platform(...)` | Manages the two versions below |
| `se.deversity.vibetags:vibetags-annotations` | compile / `compileOnly` | Annotation symbols for `javac` |
| `se.deversity.vibetags:vibetags-processor` | `<annotationProcessorPaths>` / `annotationProcessor` | Processor on the AP path only — keeps slf4j/logback off compileClasspath |

**Note:** Annotations have zero runtime footprint — completely stripped during compilation. The split exists purely to reduce the consumer's compile-time dependency surface.

---

## Build Commands

Build order is `vibetags-annotations` → `vibetags` → `vibetags-bom` → `example`, for both Maven and Gradle. The full command sequences (build, test, single-test-class) are maintained in one place: **[../CLAUDE.md#build-commands](../CLAUDE.md#build-commands)** (agent briefing) and the "Building from Source" section of [../README.md](../README.md).

---

## AI Platform Integration

### Qwen

**Files:** `QWEN.md` + `.qwen/settings.json` + `.qwen/commands/refactor.md` + `.qwenignore`

**Behavior:** Qwen reads `QWEN.md` as comprehensive project context, including locked files, contextual rules, security audit requirements, and ignored elements. The `.qwen/settings.json` configures the model (typically `qwen3-coder-plus`) and enables MCP (Model Context Protocol) for enhanced capabilities.

Sample `QWEN.md` / `.qwen/settings.json` output and the `.qwen/commands/refactor.md` / `.qwenignore` roles are documented in one place: [USAGE.md § Qwen Configuration](../USAGE.md#-qwen-configuration).

### Cursor

**Files:** `.cursorrules` + `.cursorignore`

**Behavior:** Cursor reads `.cursorrules` for core instructions and respects the `.cursorignore` glob patterns for excluding entire files from its context window.

### Claude

**Files:** `CLAUDE.md` + `.claudeignore`

**Behavior:** Claude treats `CLAUDE.md` as foundational context. XML tags appeal to Claude's parsing strengths. Enforces `<rule>` elements strictly.

### Gemini

**Files:** `.aiexclude` + `gemini_instructions.md`

**Behavior:** `.aiexclude` is a binary blocklist (hard guardrail). `gemini_instructions.md` provides detailed persona and audit guidance.

### Codex CLI

**Files:** `AGENTS.md` + `.codex/config.toml` + `.codex/rules/vibetags.rules`

**Behavior:** Codex CLI automatically reads `AGENTS.md` from the project root. The `.codex/config.toml` defines tool behavior, and `vibetags.rules` defines security-conscious command permissions using Starlark.

### GitHub Copilot

**Files:** `.github/copilot-instructions.md` + `.copilotignore`

**Behavior:** Copilot uses the instructions file to guide its completions and respects `.copilotignore` (standard glob format) to exclude specific files from being used as context.

### Windsurf Cascade & LLM Agents (llms.txt Standard)

**Files:** `llms.txt` + `llms-full.txt`

**Standard:** [llms.txt](https://llmstxt.org/) — a Markdown-based format analogous to `robots.txt` but for content rather than crawling rules. Instead of parsing HTML, LLM agents read a clean Markdown file that tells them what the project contains and where to look. `llms.txt` is the **map** (concise directory); `llms-full.txt` is the **book** (fully expanded reference).

The format hierarchy, a sample `llms.txt` output, opt-in commands, and the `vibetags.project` naming option are documented in one place: [USAGE.md § llms.txt Standard](../USAGE.md#-llmstxt-standard-windsurf-cascade--llm-agents).

---

*Last updated: 2026-06-28 — refreshed counts to the current 39 annotations / 37 platforms (see [project facts](../README.md#project-facts)) and fixed a broken `file://` link to `junit-platform.properties`.*
