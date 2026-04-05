# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VibeTags is a **compile-time Java annotation processor** (`se.deversity.vibetags.processor.AIGuardrailProcessor`) that generates AI platform-specific guardrail configuration files from Java annotations. Zero runtime overhead — all annotations use `RetentionPolicy.SOURCE`.

The repo has two independent Maven/Gradle subprojects:
- `vibetags/` — the annotation processor library (the thing you're developing)
- `example/` — a demo e-commerce app that consumes the library

## Build Commands

All commands must be run from the correct subdirectory. The processor uses `Paths.get("")` (JVM working directory) for output, so always build from the project root of the relevant subproject.

### Build and install the library

```bash
cd vibetags
mvn clean install                          # Maven
gradle clean build publishToMavenLocal     # Gradle
```

### Run tests

```bash
cd vibetags
mvn test                                   # unit tests only
mvn test -Drun.integration.tests=true      # include integration tests
gradle test                                # Gradle
```

### Run a single test class

```bash
cd vibetags
mvn test -Dtest=AnnotationProcessorEndToEndTest
mvn test -Dtest=AIGuardrailProcessorUnitTest#methodName
```

### Build the example (triggers annotation processing)

```bash
cd example
mvn clean compile     # Maven (library must be installed first)
gradle clean build    # Gradle
```

## Architecture

### Core processing flow

`AIGuardrailProcessor.process()` runs during `javac` compilation of the **consumer** project (not the library itself — the library disables annotation processing with `-proc:none`):

1. Collects all `@AILocked`, `@AIContext`, `@AIDraft`, `@AIAudit`, `@AIIgnore`, `@AIPrivacy` elements from the round environment
2. Accumulates content into six `StringBuilder`s (one per output format)
3. Calls `resolveActiveServices()` — only files that already exist on disk are regenerated (file presence = opt-in)
4. Writes to `Paths.get("").toAbsolutePath()` (project root of the consumer)

### File-existence opt-in

The processor never creates new files. To activate a platform, create the file first, then compile:

```bash
touch CLAUDE.md .cursorrules   # in the consumer project root
mvn compile
```

To deactivate, delete the file — it will never come back.

### Output files

| File | Platform | Format |
|---|---|---|
| `.cursorrules` | Cursor IDE | Markdown |
| `CLAUDE.md` | Claude | XML + Markdown |
| `.aiexclude` | Gemini | Glob patterns |
| `AGENTS.md` | Codex CLI | Markdown |
| `.codex/` | Codex CLI | Config + Starlark |
| `gemini_instructions.md` | Gemini | Markdown |
| `.github/copilot-instructions.md` | GitHub Copilot | Markdown |

### Annotations (all `RetentionPolicy.SOURCE`)

| Annotation | Targets | Key Attributes |
|---|---|---|
| `@AILocked` | TYPE, METHOD, FIELD | `reason: String` |
| `@AIContext` | TYPE, METHOD | `focus: String`, `avoids: String` |
| `@AIDraft` | TYPE, METHOD | `instructions: String` |
| `@AIAudit` | TYPE, METHOD | `checkFor: String[]` |
| `@AIIgnore` | TYPE, METHOD, FIELD | `reason: String` |
| `@AIPrivacy` | TYPE, METHOD, FIELD | `reason: String` |

`@AIIgnore` differs from `@AILocked`: locked code is visible but immutable; ignored code is excluded from AI context entirely (treat as non-existent). Common uses: auto-generated files, deprecated scaffolding.

`@AIPrivacy` marks elements that handle PII (Personally Identifiable Information). Unlike `@AIIgnore`, the element remains visible for code assistance — the AI is instructed to never include its runtime values in logs, console output, external API suggestions, test fixtures, or mock data. Use it for fields/methods subject to GDPR, HIPAA, PCI-DSS, or similar data-protection rules. Using `@AIPrivacy` together with `@AIIgnore` on the same element triggers a compile-time warning (redundant).

### SPI registration

The processor is discovered via `META-INF/services/javax.annotation.processing.Processor`. The wildcard `@SupportedAnnotationTypes("se.deversity.vibetags.annotations.*")` means new annotations are picked up automatically.

## Test Structure

| Class | Location | Coverage |
|---|---|---|
| `AnnotationDefinitionsTest` | `vibetags/src/test` | Annotation structure and defaults |
| `AIGuardrailProcessorTest` | `vibetags/src/test` | Processor configuration |
| `AIGuardrailProcessorUnitTest` | `vibetags/src/test` | Processor logic, opt-in, warning emission |
| `AIIgnoreProcessorUnitTest` | `vibetags/src/test` | `@AIIgnore` annotation definition and opt-in behaviour |
| `AIPrivacyProcessorTest` | `vibetags/src/test` | `@AIPrivacy` annotation definition, validation, and per-platform output |
| `AIGuardrailProcessorProcessTest` | `vibetags/src/test` | `process()` method, `checkOrphanedAnnotations()`, `buildServiceFileMap()`, `writeFileIfChanged()` |
| `AnnotationProcessorEndToEndTest` | `vibetags/src/test` | Generated file content |
| `AIGuardrailProcessorIntegrationTest` | `vibetags/src/test` | Full workflow (requires `-Drun.integration.tests=true`) |

## Pre-commit Hooks

The repo uses `pre-commit` with Checkstyle, gitleaks (secret scanning), end-of-file fixer, and trailing-whitespace fixer. Run `pre-commit run --all-files` to check before committing.
