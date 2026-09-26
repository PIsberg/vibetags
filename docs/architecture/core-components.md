# Architecture: Core Components

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## Core Components

### Annotations

All annotations use `@Retention(RetentionPolicy.SOURCE)` — they exist only at compile time and are stripped from final bytecode.

The full table of every annotation (targets, attributes, semantics) and every compile-time validation warning the processor emits now live in one place: **[docs/ANNOTATIONS.md](../ANNOTATIONS.md)**.

### Annotation Processor

**Class:** `se.deversity.vibetags.processor.AIGuardrailProcessor`

**Key Characteristics:**
- Extends `javax.annotation.processing.AbstractProcessor` (JSR 269)
- Registered via SPI: `META-INF/services/javax.annotation.processing.Processor`
- Supports Java 11+ source versions
- Uses `@SupportedAnnotationTypes("*")` to process all annotations
- **Orchestrator only**: every piece of actual work lives in an `internal/*` helper

**Processing Logic:**

```
Accumulation phase (every round, until processingOver() == true):
1. AnnotationCollector.collect(roundEnv) — drains the round into the LinkedHashSet<Element> accumulators (one per annotation type). The Elements stay here; AnnotationCollector.model() snapshots them into the compiler-free GuardrailModel at generate time, because an Element is only valid while its round is live
2. AnnotationValidator.validate(messager, roundEnv) — compile-time checks:
   - Contradiction checks (e.g. @AIDraft + @AILocked, @AILegacyBridge + @AIDraft)
   - Redundancy checks (e.g. @AIPrivacy + @AIIgnore, @AIPublicAPI + @AILocked, @AIParallelTests + @AILocked)
   - Config validation (e.g. empty @AIAudit, empty @AIArchitecture, invalid @AITestDriven coverageGoal)
   - Modern-Java checks against the declaration itself (e.g. @AIExtensible on a sealed type, @AIPure on a void method, an array field under @AIImmutable)
   - One getElementsAnnotatedWith per annotation type, not per check — the rules are indexed by what they scan
3. ModuleRootResolver.fromRound(sources) — on the first non-empty round, resolves the module root and source set by walking up from a root element's source file to the nearest directory containing pom.xml / build.gradle(.kts). This — not the JVM working directory, which is the reactor root for every module of an in-process Maven build and ~/.gradle/workers under Gradle — is the module identity used for multi-module sidecar aggregation (issues #278, #331). The source set splits the sidecar so compile and test-compile do not overwrite each other (issue #330). Falls back to the working directory when no compiler API exposes the source file. The source files come from RoundSources, which maps each root element once per round (Elements.getFileObjectOf, else the javac Tree API) for module identity, the early exit's SourceDigest and the PartialRoundDetector ledger alike (#857).
4. process() returns false so other processors still see the annotations

Generation phase (once, on the round where processingOver() == true):
5. ServiceRegistry.buildServiceFileMap(root) → service-key → file-path map
6. ServiceRegistry.resolveActiveServices(messager, files) → file-existence opt-in
7. GuardrailContentBuilder.build() lazily allocates the platform StringBuilders via `initBuilders()`, then delegates file rendering to modular, stateless `PlatformRenderer` implementations (under `se.deversity.vibetags.processor.internal.content.platforms.*`). Renderers orchestrate formatters from `FormatterRegistry` to build precise Markdown, XML, TOML, or Starlark files, and return the final service-key → content map. No I/O. **Before the render loop** it computes the granular owner set once (the keys of `GranularRenderer.renderGranular`) and passes it on `RenderingContext.granularOwners()`; an aggregate renderer whose granular sibling is active (`GranularIndexSection.indexActive`) then collapses to a **scoped-rules index** — always-loaded safety buckets inline, then an index naming each element whose scoped file carries more than that safety tier (`RenderingContext.indexOwners()`, #839), one line per package with the elements listed by simple name so the package is written once — instead of duplicating every element's full guardrails. The index states the file-naming convention once in its note and repeats an explicit path only for an element whose file deviates from it, which a `.vibetags-roles` config causes by grouping several elements onto one role file. Single-opt-in aggregates render in full, unchanged.
8. GuardrailFileWriter.writeFileIfChanged(...) for each active service — three-layer fast path (WriteCache hit → streaming byte-compare → readString + strip-equals); marker-aware updates, YAML front-matter preservation, atomic tmp+move writes; on success records the new fingerprint in WriteCache
9. GranularRulesWriter.writeAll(...) — per-class .mdc/.md for Cursor / Trae / Roo / Windsurf / Continue / Tabnine / Amazon Q / Amazon Kiro / .ai/rules / Claude Code / GitHub Copilot. When a `RoleConfig` (`.vibetags-roles`) is present, matching owners are grouped into human-named role files (first-match; unmatched owners keep their per-class file) instead.
10. GranularRulesWriter.cleanupAll(...) — remove orphaned granular files (skipping the names just written, to avoid delete-then-recreate cycles; invalidates the WriteCache entry for any file it deletes or rewrites)
    - ModuleOutputWriter.write(...) — per-module (nested) output: re-runs the single-module pipeline against the opted-in module directory (`compilationRoot()`), scoped to this compilation's annotations. No sidecar, no cross-module merge — orthogonal to the root aggregation above. Gated on a resolved `moduleRoot` that differs from the VibeTags root (the module's own opt-in set is also folded into the fingerprint at step 5-ish so a fresh module opt-in isn't short-circuited).
11. OrphanWarner.warnAboutOrphans(...) — warn if annotations used without the corresponding ignore-file (e.g. @AIIgnore without .cursorignore)
12. WriteCache.flush() — atomically persist the .vibetags-cache sidecar (no-op if no entries changed this build)
```

**Output File Generation:** the processor writes one file per active service; the full file ↔ platform ↔ format table (65 output paths as of this writing) is maintained in one place: **[docs/PLATFORMS.md](../PLATFORMS.md)**.

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
