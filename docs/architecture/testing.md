# Architecture: Testing Strategy

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## Testing Strategy

### Where the tests are

The per-class map is [TESTS.md](../TESTS.md): every test class in `vibetags/src/test`, what it
covers, and which tier it runs in. This page used to carry its own copy of that table, with
per-class tallies from the 0.7.x era and a suite total measured on 2026-08-06; both had drifted
far from the build, so the map now lives in one place.

The tiers, in short: `mvn test` runs the fast tier and skips every class tagged `@Tag("e2e")`;
`mvn test -Pe2e` runs everything, and is what CI runs. A second surefire execution,
`async-tests`, runs the `*AsyncTest` classes in a fork of their own, under the async-test agent
that `vibetags/pom.xml` attaches to that fork only. For the current total, read
the surefire summary rather than any number written here; TESTS.md records the last
measurement with its date.

**JMH benchmarks** (under `load-tests/`, not counted above):
- `ProcessorHotPathBenchmark` — 6 benchmarks: `buildServiceFileMap`, `resolveActiveServices_{all,none}Present`, `writeFileIfChanged_{noChange,smallWrite,largeWrite}`. Run on every release-tagged baseline.
- `WriteCacheHitBenchmark` _(0.7.1)_ — 8 benchmarks proving the cache: `(small=1KB, medium=12KB, large=1MB) × (marker .md, non-marker .cursorrules) × (cacheHit, noCache)` minus the four cache-hit cases at the same body size that are constant-time. Plots in `load-tests/results/_plots/cache-hit-{time,alloc}.png`.

### Concurrency & Thread-Isolated Logging

To run the whole suite concurrently without static resource conflicts, the VibeTags test suite leverages a thread-isolated execution architecture under JUnit 5.

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

These are the test legs only. [WORKFLOW.md](../WORKFLOW.md) lists every workflow and job CI runs,
including the static-analysis, corpus and self-check gates.
