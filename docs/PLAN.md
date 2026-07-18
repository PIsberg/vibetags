> **Historical roadmap — superseded.** This was the step-by-step execution plan for the "parallel tests + 9 new AI guardrails" initiative (see [SPEC.md](../SPEC.md)), written when the project was pre-1.0. All of it shipped. For what actually shipped release-by-release, see [docs/CHANGELOG.md](CHANGELOG.md); for current project status, see the root [README.md](../README.md).

# VibeTags Implementation Plan: Parallel Tests & New AI Guardrails

This document outlines the step-by-step execution plan for implementing the 9 new AI guardrail annotations and configuring VibeTags' test suite to run in parallel with absolute thread-safety.

---

## Phase 1: Dynamic Logger Isolation & Parallel Test Suite Setup

### Step 1.1: Thread-Isolated Logging in `VibeTagsLogger`
* **File to modify**: `vibetags/src/main/java/se/deversity/vibetags/processor/VibeTagsLogger.java`
* **Goal**: Isolate loggers per compilation/test run to prevent parallel threads from detaching each other's appenders.
* **Tasks**:
  1. Add a helper `getLoggerName(Path projectRoot)` that hashes the absolute normalized project root path and appends it to `"se.deversity.vibetags"`.
  2. Update the `forRoot` methods to resolve the context-specific logger name.
  3. Ensure `shutdown()` detaches appenders specifically for the dynamic logger associated with the target root.

### Step 1.2: Enable Parallel JUnit 5 Execution in Surefire
* **File to create**: `vibetags/src/test/resources/junit-platform.properties`
* **Goal**: Configure JUnit Jupiter to execute all test classes and methods concurrently.
* **Tasks**:
  1. Add configuration flags:
     ```properties
     junit.jupiter.execution.parallel.enabled = true
     junit.jupiter.execution.parallel.mode.default = concurrent
     junit.jupiter.execution.parallel.mode.classes.default = concurrent
     ```

### Step 1.3: Verification of Parallel Logging Isolation
* **File to create**: `vibetags/src/test/java/se/deversity/vibetags/processor/VibeTagsLoggerConcurrencyTest.java`
* **Goal**: Programmatically verify that concurrent threads running with different root directories do not interfere with each other's logging configurations.

---

## Phase 2: Add the 9 New SOURCE Annotations

### Step 2.1: Define Annotation Classes
* **Files to create in `vibetags-annotations` module**:
  * `se/deversity/vibetags/annotations/AIParallelTests.java`
  * `se/deversity/vibetags/annotations/AILegacyBridge.java`
  * `se/deversity/vibetags/annotations/AIArchitecture.java`
  * `se/deversity/vibetags/annotations/AIPublicAPI.java`
  * `se/deversity/vibetags/annotations/AIStrictExceptions.java`
  * `se/deversity/vibetags/annotations/AIStrictTypes.java`
  * `se/deversity/vibetags/annotations/AIInternationalized.java`
  * `se/deversity/vibetags/annotations/AIStrictClasspath.java`
  * `se/deversity/vibetags/annotations/AISchemaSafe.java`
* **Goal**: Define annotations with SOURCE retention and correct target constraints. `@AIArchitecture` will have attributes `String belongsTo() default ""` and `String[] cannotReference() default {}`.

### Step 2.2: Add to BOM (Bill of Materials) & Verify Exports
* **Goal**: Ensure the annotations are correctly compiled and exported by `vibetags-annotations`.

---

## Phase 3: Integration into the Annotation Processor

### Step 3.1: Accumulate Annotations in `AnnotationCollector`
* **File to modify**: `vibetags/src/main/java/se/deversity/vibetags/processor/internal/AnnotationCollector.java`
* **Goal**: Expand the collector to search for and accumulate the 9 new annotations in all JSR 269 rounds.
* **Tasks**:
  1. Add internal `LinkedHashSet<Element>` fields for each new annotation.
  2. Update `collect(RoundEnvironment)` to query `roundEnv.getElementsAnnotatedWith(...)` for each new class.
  3. Add getter methods for the new sets.
  4. Update `reset()` to clear all new sets.

### Step 3.2: Implement Validation Warning Rules
* **File to modify**: `vibetags/src/main/java/se/deversity/vibetags/processor/internal/AnnotationValidator.java`
* **Goal**: Emits compile-time warning diagnostics for contradictory, redundant, or incomplete annotations.
* **Tasks**:
  1. Implement `@AILegacyBridge` + `@AIDraft` contradiction checks.
  2. Implement `@AIPublicAPI` + `@AILocked` redundancy checks.
  3. Implement `@AIParallelTests` + `@AILocked` redundancy checks.
  4. Implement `@AISchemaSafe` + `@AIIgnore` redundancy checks.
  5. Implement `@AIStrictClasspath` + `@AILocked` redundancy checks.
  6. Implement `@AIArchitecture` empty attribute checks.

---

## Phase 4: Guardrail Prompt Generation

### Step 4.1: Render Prompt Text in `GuardrailContentBuilder`
* **File to modify**: `vibetags/src/main/java/se/deversity/vibetags/processor/internal/GuardrailContentBuilder.java`
* **Goal**: Formulate the guardrail prompts for each of the new annotations, assembling them into the per-platform target configs.
* **Tasks**:
  1. Pre-allocate section buffers inside `initBuilders()`.
  2. Implement appenders (`appendParallelTests`, `appendLegacyBridge`, `appendArchitecture`, `appendPublicApi`, `appendStrictExceptions`, `appendStrictTypes`, `appendInternationalized`, `appendStrictClasspath`, `appendSchemaSafe`).
  3. Assemble sections into target formats (Markdown, XML-like CLAUDE.md structure, JSON files, ignore formats, and `llms.txt` / `llms-full.txt` discoverability blocks).

---

## Phase 5: Verification & Testing

### Step 5.1: Create Definition and Validation Tests
* **Files to create in `vibetags` test suite**:
  * `se/deversity/vibetags/processor/NewAnnotationsV4DefinitionTest.java`: Verifies annotation properties and bounds.
  * `se/deversity/vibetags/processor/NewAnnotationsV4ValidationTest.java`: Verifies that compiler diagnostics emit the correct warnings.

### Step 5.2: Create End-to-End Generated Output Tests
* **File to create**: `se/deversity/vibetags/processor/NewAnnotationsV4EndToEndTest.java`
* **Goal**: Verify the output for each platform is perfectly generated according to the specification.

### Step 5.3: Run the Complete Suite in Parallel
* Run `mvn clean install` to compile annotations, processor, and run the parallel test suite.
* Ensure 100% pass rate with zero deadlocks or resource issues.
