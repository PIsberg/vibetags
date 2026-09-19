# Architecture: Testing Strategy

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

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
| `AnnotationProcessorEndToEndTest` | 76 | End-to-end snapshot net: compiles annotated fixture sources in-memory via `ProcessorTestHarness`, verifies all generated files and content across all 9 annotation types × all platforms (the safety net for `GuardrailContentBuilder` extraction) <!-- not-a-total --> |
| `GranularRulesEndToEndTest` | 9 | Cursor/Trae/Roo granular rule file generation, orphaned file cleanup |
| `QwenEndToEndTest` | 19 | Qwen end-to-end: QWEN.md structure, settings.json format, .qwenignore patterns, version stamping |
| `MultiModuleStabilityTest` | 3 | Multi-module safety: no-annotation module preserves sibling module content |
| `VibeTagsLoggerUnitTest` | 13 | File logging: log level filtering, file rotation, shutdown |
| `AIGuardrailProcessorIntegrationTest` | 23 | Full workflow with backup/restore. Self-contained via `ProcessorTestHarness`; runs with plain `mvn test` |

**Total: 1484 tests** (the surefire summary of `mvn test` in `vibetags/`, measured 2026-08-06). The
per-class tallies above date from the 0.7.x era and the table no longer lists every class; trust the
build's own summary over any total restated here.

**JMH benchmarks** (under `load-tests/`, not counted above):
- `ProcessorHotPathBenchmark` — 6 benchmarks: `buildServiceFileMap`, `resolveActiveServices_{all,none}Present`, `writeFileIfChanged_{noChange,smallWrite,largeWrite}`. Run on every release-tagged baseline.
- `WriteCacheHitBenchmark` _(0.7.1)_ — 8 benchmarks proving the cache: `(small=1KB, medium=12KB, large=1MB) × (marker .md, non-marker .cursorrules) × (cacheHit, noCache)` minus the four cache-hit cases at the same body size that are constant-time. Plots in `load-tests/results/_plots/cache-hit-{time,alloc}.png`.

### Concurrency & Thread-Isolated Logging

To run all 724+ unit and integration tests concurrently without static resource conflicts, the VibeTags test suite leverages a thread-isolated execution architecture under JUnit 5.

#### 1. JUnit 5 Parallel Test Execution
Tests are run fully concurrently at both the class and method levels. This is configured in [junit-platform.properties](../../vibetags/src/test/resources/junit-platform.properties):
```properties
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.mode.default = concurrent
junit.jupiter.execution.parallel.mode.classes.default = concurrent
junit.jupiter.execution.parallel.config.executor-service = worker_thread_pool
```

The last line is load-bearing (#659): under JUnit's default `fork_join_pool` executor, the
processor's blocking `ForkJoinTask.get()` ran other queued tests inside a test's compilation on the
same thread, until javac overflowed the stack. [TESTS.md](../TESTS.md) has the measurement.

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
