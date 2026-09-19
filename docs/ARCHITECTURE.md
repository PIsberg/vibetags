# VibeTags Architecture - Technical Deep Dive

This page is the index. The deep dive is split into parts so a task loads only the part it
needs; each part is self-contained and links back here. If you only need the rules, read
[LOAD-BEARING.md](LOAD-BEARING.md) instead.

| Part | Answers |
|---|---|
| [System Architecture](architecture/components.md) | Component and class diagrams, the parsed code-karta diagrams and how to regenerate them, and a one-line role for every processor, internal and model class. |
| [Build Sequence and Data Flow](architecture/processing.md) | The processing phases in order, the write fast paths, and how each annotation type flows into platform content. |
| [Platform Output Formats](architecture/output-formats.md) | What the same annotation data looks like in each platform's file. |
| [Core Components](architecture/core-components.md) | The processor's accumulation and generation logic step by step, and the generated output files. |
| [Build Flow, Dependencies and Commands](architecture/build.md) | How Maven and Gradle invoke the processor, which artifact goes where, and where the build commands live. |
| [Directory Structure](architecture/directory-structure.md) | The annotated repository tree. |
| [Design Decisions](architecture/design-decisions.md) | SOURCE retention, single processor, file-existence opt-in, write-if-changed, the write cache, version stamping, the validation layer. |
| [Testing Strategy](architecture/testing.md) | Unit and integration test layout, parallel execution and thread-isolated logging, test patterns, CI. |
| [Limitations and Future Architecture](architecture/limitations.md) | Known limits (output location, Gradle incremental, fixed formats, validation) and the shelved plugin design. |
| [AI Platform Integration](architecture/platform-integration.md) | How each AI tool consumes its generated file. |
| [Repository Layout Notes](architecture/repository-layout.md) | Per-subproject facts the directory tree does not say. |
| [Design History](architecture/design-history.md) | Routing to archived specs, plans, proposals and diagram generations. |

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
| `se.deversity.vibetags:vibetags-annotations` | The `@interface` classes (see [project facts](../README.md#project-facts) for the count) | Consumer's compile classpath | nothing |
| `se.deversity.vibetags:vibetags-processor` | `AIGuardrailProcessor` + `VibeTagsLogger` (slf4j/logback) | Annotation-processor path only | `vibetags-annotations` |
| `se.deversity.vibetags:vibetags-bom` (pom-only) | Manages versions of the two jars above | `<dependencyManagement>` import / Gradle `platform(...)` | — |

The split keeps `slf4j` / `logback` (the processor's internal logging deps) off the consumer's `compileClasspath`. Existing 0.5.x setups that pin only `vibetags-processor` continue to work — the processor declares `vibetags-annotations` as a regular compile dependency so the annotation classes are still resolved transitively. New projects should adopt the split layout shown in the README's Installation section.

---

*Last updated: 2026-09-19 - split the 1,216-line deep dive into the parts under
[`architecture/`](architecture/) so an agent following one link loads one topic instead of the
whole file; this page keeps only the overview and the index. Previous update 2026-08-15 - added
the Design History section so the historical spec, plan, proposals, and archived diagrams are
routed from exactly one place (`DocsIndexCompletenessTest` now fails an orphaned doc).*
