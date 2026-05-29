# Contributing to VibeTags

Thank you for your interest in contributing! VibeTags is an open-source annotation processor that generates AI guardrail configuration files.

## Code of Conduct

This project follows a code of conduct. By participating, you are expected to uphold it. Please read [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before contributing.

## Getting Started

### Prerequisites

- **JDK 21 or higher (tested on 21, 25, 26))
- **Maven 3.6+** or **Gradle 7.0+**
- **Git**

### Building the Project

```bash
# Build the core library (Maven)
cd vibetags
mvn clean install

# Build the example project
cd ../example
mvn clean compile

# Run all tests
cd ../vibetags
mvn test

# Run load/stress tests
cd ../load-tests
mvn test
```

### Running Tests

```bash
# All tests (unit + integration)
mvn test

# Skip slow stress tests in CI
mvn test -Dstress.max.classes=100

# Run all JMH microbenchmarks with GC profiler (~3 min)
cd load-tests
mvn package -DskipTests
java -jar target/benchmarks.jar -wi 3 -i 5 -f 1 -tu us -bm avgt -prof gc

# Run only the cache-hit benchmark (since 0.7.1 — proves the WriteCache value)
java -jar target/benchmarks.jar WriteCacheHitBenchmark -wi 3 -i 5 -f 1 \
     -tu us -bm avgt -prof gc
```

See [`load-tests/README.md`](../load-tests/README.md) and
[`load-tests/results/README.md`](../load-tests/results/README.md) for the full
benchmark catalogue, baseline-capture procedure, and committed release-tagged
results.

### Faster local iteration

The full suite runs ~800 tests in ~16 s — most of which are full in-process
`javac` compilations, so it is already CPU-bound and parallel (JUnit runs tests
concurrently). When you are iterating on one area, don't run everything:

```bash
# Run a single test class (offline, skip coverage instrumentation)
mvn -o test -Dtest=WriteCacheProcessorIntegrationTest -Djacoco.skip=true

# Run a few classes, or a single method
mvn -o test -Dtest='WriteCacheProcessorIntegrationTest,FingerprintShortCircuitTest'
mvn -o test -Dtest=AIGuardrailProcessorUnitTest#process_processingOver_returnsEarlyWithFalse
```

`-o` (offline) skips dependency resolution; running `test` (not `verify`) skips
PMD/SpotBugs/CPD; `-Djacoco.skip=true` drops coverage instrumentation. Together
they cut a targeted run to a few seconds. Run the full `mvn clean test -B` once
before opening a PR.

## How to Contribute

### Reporting Bugs

1. Check the [issue tracker](https://github.com/PIsberg/vibetags/issues) for existing reports.
2. Open a new issue with:
   - **Title**: Short description of the bug
   - **Environment**: JDK version, OS, build tool (Maven/Gradle)
   - **Steps to reproduce**
   - **Expected vs actual behavior**
   - **Logs**: Include `vibetags.log` if relevant

### Suggesting Features

Open an issue with the `enhancement` label. Describe:
- The use case
- Why existing annotations don't cover it
- Proposed annotation API or behavior

### Pull Requests

1. **Fork** the repository and create a branch from `main`.
2. **Name branches** descriptively: `feature/your-feature`, `fix/issue-description`.
3. **Write tests** for new functionality.
4. **Update documentation** (README.md, example code, RELEASING.md).
5. **Run the full test suite**: `mvn clean test -B`
6. **Ensure no new warnings**: The build should produce zero compiler warnings.
7. Open a PR against `main` with a clear description of the change.

### Commit Message Style

Use conventional commits:
```
fix: resolve NPE when processing empty @AIAudit
feat: add @AIPrivacy annotation for PII fields
docs: update example project README
test: add integration test for Codex CLI output
chore: update dependency versions
```

## Project Structure

```
vibetags/                          # Root
├── vibetags-annotations/          # Published as se.deversity.vibetags:vibetags-annotations
│   └── src/main/java/
│       └── annotations/           # @AILocked, @AIContext, @AIDraft, @AIAudit, @AIIgnore, @AIPrivacy, @AICore, @AIPerformance
├── vibetags/                      # Published as se.deversity.vibetags:vibetags-processor
│   ├── src/main/java/processor/   # AIGuardrailProcessor, VibeTagsLogger
│   └── src/test/java/             # Unit, integration, and end-to-end tests (depends on vibetags-annotations)
├── vibetags-bom/                  # Published as se.deversity.vibetags:vibetags-bom (pom-only)
├── example/                       # Example e-commerce application
│   └── src/main/java/             # Demonstrates real-world annotation usage; consumes via the BOM
├── load-tests/                    # Stress test and benchmark harness
│   └── src/
│       ├── main/java/             # JMH benchmarks
│       └── test/java/             # Stress + concurrency tests
└── .github/workflows/             # CI/CD pipelines
```

**Build order:** `vibetags-annotations` → `vibetags` → `vibetags-bom` → `example` (or `load-tests`). The processor depends on the annotations module at compile time.

## Adding a New Annotation

1. Create the annotation interface in `vibetags/src/main/java/se/deversity/vibetags/annotations/`.
2. Update `AIGuardrailProcessor` to collect and process the new annotation.
3. Add generation logic for each AI platform (Cursor, Claude, Qwen, Gemini, Codex, Copilot, llms.txt).
4. Write unit tests in a new `*Test.java` file.
5. Add an integration test verifying generated file content.
6. Update this README with usage examples.
7. Update `example/src/` with a demo class using the annotation.

## Code Style

- **Java**: Follow the project's Checkstyle rules (enforced via pre-commit hooks).
- **Markdown**: Use semantic markup, proper heading levels, and consistent formatting.
- **Generated files**: Never hand-edit files like `.cursorrules`, `CLAUDE.md`, etc. — they are auto-generated.

## Security

See [SECURITY.md](SECURITY.md) for the vulnerability disclosure policy.

## License

By contributing, you agree that your contributions will be licensed under the [MIT License](LICENSE).
