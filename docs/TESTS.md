# Test Structure

Index of every test class in `vibetags/src/test` and what it covers — use this to find which test to run or extend for a given change.

| Class | Coverage |
|---|---|
| `AnnotationDefinitionsTest` | Annotation structure and defaults (the original 27 annotations; the 12 newest are covered by `NewAnnotationsV4`/`V5` definition tests) |
| `AIGuardrailProcessorTest` | Processor configuration |
| `AIGuardrailProcessorUnitTest` | Processor logic, opt-in, warning emission |
| `AIIgnoreProcessorUnitTest` | `@AIIgnore` annotation definition and opt-in behaviour |
| `AIPrivacyProcessorTest` | `@AIPrivacy` annotation definition, validation, and per-platform output |
| `AIContractProcessorTest` | `@AIContract` annotation definition, validation (contradictory/overlap combinations), and per-platform output |
| `AIGuardrailProcessorProcessTest` | `process()` method, `checkOrphanedAnnotations()`, `buildServiceFileMap()`, `writeFileIfChanged()` |
| `AnnotationProcessorEndToEndTest` | Generated file content |
| `GranularRulesEndToEndTest` | Cursor/Trae/Roo granular rule file generation |
| `NewPlatformsEndToEndTest` | Windsurf, Zed, Cody, Supermaven, Continue, Tabnine, Amazon Q, universal `.ai/rules/` |
| `NewPlatformsV2EndToEndTest` | PearAI, Mentat, Sweep, Plandex, Double.bot, Open Interpreter, Codeium |
| `QwenEndToEndTest` | Qwen-specific output |
| `QwenProcessorUnitTest` | Qwen processor options |
| `VibeTagsLoggerUnitTest` | File logging |
| `VibeTagsLoggerAsyncTest` | Async/background logging behaviour |
| `VibeTagsLoggerConcurrencyTest` | Logger thread-safety under concurrent writes |
| `MultiModuleStabilityTest` | Multi-module safety (no-annotation module doesn't wipe shared files) |
| `MultiModuleAggregationTest` | Sidecar aggregation and sub-marker output across multiple modules |
| `MultiModuleProcessorTest` | Per-module sidecar write/read/merge cycle |
| `AITestDrivenProcessorTest` | `@AITestDriven` annotation definition, validation (contradictory combinations), and per-platform output |
| `NewAnnotationsV3DefinitionTest` | Definition-level tests for `@AIThreadSafe`, `@AIImmutable`, `@AIDeprecated`, `@AIObservability`, `@AIRegulation` |
| `NewAnnotationsV3EndToEndTest` | End-to-end generated content for v0.9.5 annotations across all platforms |
| `NewAnnotationsV3MinimalTest` | Minimal smoke tests for v0.9.5 annotation output |
| `NewAnnotationsV3ValidationTest` | Compile-time validation warnings for v0.9.5 annotations |
| `NewAnnotationsV4DefinitionTest` | Definition-level tests for the 9 new annotations (`@AIArchitecture`, `@AILegacyBridge`, etc.) |
| `NewAnnotationsV4EndToEndTest` | End-to-end generated content for the 9 new annotations across all platforms |
| `NewAnnotationsV4ValidationTest` | Compile-time validation warnings for the 9 new annotations |
| `BuildFingerprintIntegrationTest` | Top-level fingerprint short-circuit: cache creation, stable mtimes on unchanged rebuild, fingerprint invalidation on annotation change |
| `BuildFingerprintUnitTest` | `BuildFingerprint.compute()` determinism and collision properties |
| `FingerprintShortCircuitTest` | End-to-end short-circuit skip behaviour when inputs are unchanged |
| `CheckModeTest` | Opt-in check mode (`-Avibetags.check=true`): pass/fail verdicts, zero writes, multi-module merge parity, dry-run `GuardrailFileWriter` |
| `LocksReportEndToEndTest` | `.vibetags-locks` machine-readable lock report: class/method positions via the javac Tree API, JSON escaping, opt-in behaviour |
| `IncrementalProcessorDeclarationTest` | Verifies `META-INF/gradle/incremental.annotation.processors` is present and declares the processor as `aggregating` |
| `GuardrailContentBuilderLazyAllocationTest` | Pre-sized `StringBuilder` allocation based on collected element count |
| `GuardrailContentBuilderUnitTest` | Per-annotation content generation for each platform |
| `GuardrailFileWriterCoverageTest` | `GuardrailFileWriter` branch coverage |
| `GuardrailFileWriterEdgeCaseTest` | Edge cases: empty content, missing parent dir, read-only file |
| `GranularRulesWriterUnitTest` | Per-class rule file writes and cleanup ordering |
| `CleanupGranularDirectoryTest` | Orphan granular file removal after annotation deletion |
| `AnnotationCollectorUnitTest` | `AnnotationCollector` accumulation across multiple rounds |
| `AnnotationValidatorUnitTest` | All compile-time validation warning combinations |
| `ElementNamingUnitTest` | FQN construction for TYPE, METHOD, FIELD, and PACKAGE elements |
| `WriteCacheTest` | Cache hit/miss/invalidation/persistence/corruption-fallback |
| `WriteCacheAsyncTest` | Write-cache correctness under concurrent access |
| `WriteCacheProcessorIntegrationTest` | Cache integration: created on first compile, stable mtimes on second, rewrite on external edit |
| `StreamingByteCompareTest` | Streaming byte-compare for non-marker overwrite files |
| `StripLegacyVibeTagsBlockEdgeCasesTest` | Legacy marker migration edge cases (files without markers) |
| `WriteFileFrontMatterTest` | YAML front-matter preservation in `.mdc`/`.md` granular rule files |
| `DesignMdEndToEndTest` | `DESIGN.md` generation for AI design agents |
| `NewPlatformsV3EndToEndTest` | `GEMINI.md` and `.antigravityignore` generation (v0.9.6) |
| `NewPlatformsV4EndToEndTest` | AI PR reviewers (`.coderabbit.yaml`, `.pr_agent.toml`, `ellipsis.yaml`), context-packer ignore files (`.repomixignore`, `.gitingestignore`, `.gptignore`, `.ghostcoderignore`, `.piecesignore`), Void (`.void/rules.md`), and Roo modes (`.roomodes`) |
| `ClineEndToEndTest` | `.clinerules` generation for Cline AI assistant (v0.9.7) |
| `JunieEndToEndTest` | `.junie/guidelines.md` generation for JetBrains Junie (v0.9.7) |
| `KiroGranularEndToEndTest` | `.kiro/steering/` granular rule generation for Amazon Kiro (v0.9.7) |
| `ParallelFileWriteTest` | Parallel file-write correctness: 50+ active services written via `ForkJoinPool.commonPool()` without corruption (v0.9.7) |
| `NewAnnotationsV5DefinitionTest` | Definition-level tests for `@AIIdempotent`, `@AIFeatureFlag`, and `@AISecure` |
| `NewAnnotationsV5EndToEndTest` | End-to-end generated content for `@AIIdempotent`, `@AIFeatureFlag`, and `@AISecure` across all platforms |
| `NewAnnotationsV5ValidationTest` | Compile-time validation warnings for `@AIIdempotent`, `@AIFeatureFlag`, and `@AISecure` |
| `AIGuardrailProcessorIntegrationTest` | Full workflow (requires `-Drun.integration.tests=true`) |
| `ClaudeLocalEndToEndTest` | `CLAUDE.local.md` generation for Claude Code local overrides |
| `ClaudeSkillEndToEndTest` | `.claude/skills/vibetags-guardrails/SKILL.md` generation, including required Skill frontmatter |
| `ClaudeGranularEndToEndTest` | `.claude/rules/*.md` granular rule generation for Claude Code, including `paths:` frontmatter |
| `CopilotGranularEndToEndTest` | `.github/instructions/*.instructions.md` granular rule generation for GitHub Copilot, including `applyTo:` frontmatter |
| `GranularIndexEndToEndTest` | Scoped-rules index: dual opt-in (aggregate + granular) collapses the aggregate to a locked/core/safety summary plus a per-element index; single opt-in stays full; reuse renderers (Cline) stay full while `CLAUDE.local.md` mirrors `CLAUDE.md` |
