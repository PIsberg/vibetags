# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.3] - 2026-04-17

### Fixed
- **Granular directory path resolution**: Removed spurious trailing slash from `.cursor/rules/`, `.roo/rules/`, and `.trae/rules/` paths in `buildServiceFileMap`, which prevented directory opt-in detection on some file systems.

### Changed
- `cleanupGranularDirectory` is now package-private to allow direct unit testing without going through a full compilation round.

### Tests
- Added `testPackageKind_GranularRules`: verifies that a `PACKAGE`-kind element annotated with `@AILocked` produces a correctly-scoped `.mdc` file (glob `**/pkg/**/*.java`) in the Cursor granular rules directory.
- Added `testCleanupGranularDirectory_NonExistent` and `testCleanupGranularDirectory_IOException`: cover the early-return guard and file-as-directory edge case in `cleanupGranularDirectory`.
- Added `testWriteFileIfChanged_IOException`: exercises the read-only file path through `writeFileIfChanged`.
- Added `testMessager_MiscellaneousOverloads`: covers the three extra `printMessage` overloads on the inner `Messager` proxy.
- Added `testOptions_ComplexPaths`: verifies `init()` accepts a custom root path, project name, and log path without crashing.
- Added `forRootInvalidLevel_fallbacksToInfo` and `forRootPathIsDirectory_triggersCatchAndReturnsStandardLogger` to `VibeTagsLoggerUnitTest` for logger error-handling branches.
- Added `@AfterEach VibeTagsLogger.shutdown()` teardown to prevent logger state leaking between tests.

## [0.5.2] - 2026-04-16

### Fixed
- **Aider `CONVENTIONS.md` generation**: Resolved an issue where the file could end up empty after a reset-and-compile cycle, and stabilized the processor's handling of the Aider conventions output.
- **Gradle release coordinates**: `vibetags/build.gradle` was still publishing `0.5.1`, which prevented Gradle consumers (including the example project in CI) from resolving `0.5.2`.
- **Version drift**: Aligned `load-tests/pom.xml` (`processor.version`) and `README.md` install snippets to `0.5.2`.

## [0.5.1] - 2026-04-15

### Added
- **Granular AI Rules**: Support for Cursor (`.cursor/rules/*.mdc`), Roo Code (`.roo/rules/*.md`), and Trae (`.trae/rules/*.md`).
- **Aider Integration**: Support for project-wide `CONVENTIONS.md` and `.aiderignore` exclusion patterns.
- **Automatic Scoping**: Granular rules now include auto-generated `globs` (e.g., `**/MyClass.java`) to ensure AI tools only apply rules where relevant.
- **Orphaned File Cleanup**: Processor now automatically deletes generated VibeTags files in granular directories if the source annotations are removed.
- **YAML Front-Matter Safety**: VibeTags markers now correctly place themselves *after* YAML metadata in `.mdc` and `.md` rule files to preserve IDE compatibility.

### Fixed
- **Windows File System Compatibility**: Sanitized rule filenames by replacing invalid characters (`<`, `>`) with hyphens to prevent `InvalidPathException`.
- **JDK 25 / Gradle Stability**: Fixed `NullPointerException` and assertion failures in unit tests triggered by specific JDK/build environments.
- **Unicode Preservation**: Ensured UTF-8 encoding is strictly followed for all generated AI configuration files.
- **Marker Duplicate Prevention**: Resolved logic errors that could cause VibeTags marker sections to be duplicated on repeated compiles.

### Changed
- Refactored `AIGuardrailProcessor` into a cleaner, round-aware stateful architecture.
- Optimized file build map resolution for faster compile-time performance.

## [0.5.0] - 2026-04-07

### Added
- Initial public test release of VibeTags annotation processor
- Six annotations: `@AILocked`, `@AIContext`, `@AIDraft`, `@AIAudit`, `@AIIgnore`, `@AIPrivacy`
- Automatic generation of AI platform configuration files at compile time
- Support for Cursor, Claude, Qwen, Gemini, Codex CLI, GitHub Copilot, and Windsurf Cascade
- `llms.txt` / `llms-full.txt` output following the [llms.txt standard](https://llmstxt.org/)
- Strict opt-in model: only populates files that already exist on disk
- Compile-time validation warnings for contradictory or empty annotations
- Configurable file-based logging (`vibetags.log`)
- Maven and Gradle build support
- Multi-JDK CI (17, 21, 25, 26)
- JaCoCo code coverage with Codecov integration
- Load test harness with annotation-volume stress tests and concurrent-build safety tests
- OpenSSF Scorecard, CodeQL scanning, and dependency review workflows

### Notes
- This is a **test release** (v0.5.0) intended for validation before the 1.0.0 GA.
- API and generated file formats may change before 1.0.0.
- Publishes to both GitHub Packages and Maven Central (Sonatype OSSRH).

[Unreleased]: https://github.com/PIsberg/vibetags/compare/v0.5.3...HEAD
[0.5.3]: https://github.com/PIsberg/vibetags/compare/v0.5.2...v0.5.3
[0.5.2]: https://github.com/PIsberg/vibetags/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/PIsberg/vibetags/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/PIsberg/vibetags/releases/tag/v0.5.0
