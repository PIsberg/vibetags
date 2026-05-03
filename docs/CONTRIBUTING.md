# Contributing to VibeTags

Thank you for your interest in contributing! VibeTags is an open-source annotation processor that generates AI guardrail configuration files.

## Code of Conduct

This project follows a code of conduct. By participating, you are expected to uphold it. Please read [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before contributing.

## Getting Started

### Prerequisites

- **JDK 17 or higher** (tested on 17, 21, 25, 26)
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

# Run JMH microbenchmarks (~2 min)
cd load-tests && mvn package exec:java -Dexec.mainClass=org.openjdk.jmh.Main
```

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
├── vibetags/                      # Core annotation processor library
│   ├── src/main/java/
│   │   ├── annotations/           # @AILocked, @AIContext, @AIDraft, @AIAudit, @AIIgnore, @AIPrivacy
│   │   └── processor/             # AIGuardrailProcessor, VibeTagsLogger
│   └── src/test/java/             # Unit, integration, and end-to-end tests
├── example/                       # Example e-commerce application
│   └── src/main/java/             # Demonstrates real-world annotation usage
├── load-tests/                    # Stress test and benchmark harness
│   └── src/
│       ├── main/java/             # JMH benchmarks
│       └── test/java/             # Stress + concurrency tests
└── .github/workflows/             # CI/CD pipelines
```

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
