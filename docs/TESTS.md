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
| `MultiModuleYamlValidityTest` | The six generated YAML documents survive a multi-module merge as *one* document: parsed with duplicate keys forbidden, every module's guardrails still reachable, empty modules contributing nothing |
| `YamlMergeShapeContractTest` | Each `PlatformRenderer.mergeShape()` still describes what its renderer writes, and no generated `.yaml` ships without one |
| `MultiModuleWholeFileMergeTest` | The marker-free JSON/TOML outputs survive a reactor: sidecar bodies exist so the file is allowed to refresh at all, every module's guardrails are in the merged document, `.mentatconfig.json` stays valid JSON, and a new marker-free service whose content varies without a declared merge fails the build (#265) |
| `SourceSetIsolationEndToEndTest` | `compile` and `test-compile` are separate rounds over the same module: neither erases the other's guardrails or rule files, two source sets still render as one region, and a module's own aggregate merges across them (#330) |
| `WrappedProcessingEnvironmentTest` | Module identity survives a wrapped `ProcessingEnvironment` (Gradle's incremental decorator, where the javac Tree API is unavailable) instead of collapsing onto one content hash (#331) |
| `DestructiveRewriteWarningTest` | The diagnostics that make a silently-shrinking guardrail file visible: a wholesale element replacement, and a sweep that removes more than it writes — and, equally important, silence on ordinary work |
| `DetachedModuleWarningTest` | A module compiling as its own root while a Maven `<module>`/Gradle `include` above declares it (#296), and the guards that keep a nested standalone project quiet |
| `IndexedRootCopilotEndToEndTest` | Indexed root with Copilot's aggregate and granular directory at the ROOT (#319): collapse, cross-module survival of the shared granular directory, and no empty heading per module |
| `EnforcingModeEndToEndTest` | Opt-in enforcement (#284): baseline recording, signature-change failure, silence when not asked for, unrecorded-baseline warning, and the refusal of families it cannot prove |
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
| `AnnotationCollectorUnitTest` | `AnnotationCollector` accumulation across multiple rounds — one case per registered annotation type, so a bucket dropped from `GuardrailAnnotations.ALL` fails here |
| `ArchitectureRulesTest` | Formatter/renderer statelessness, and the compiler boundary: `content` and `model` must not import `javax.lang.model`/`com.sun.source`, `content` must not depend on `internal`, `model` must not depend on either |
| `ElementTagMappingTest` | `ElementTag` mirrors `ElementKind` name-for-name — fails if a JDK adds a kind, before generated output can report it as `UNKNOWN` |
| `AnnotationValidatorUnitTest` | All compile-time validation warning combinations |
| `ValidationRegistryTest` | Properties of the rule registry itself: every rule scans an annotation that is actually in `GuardrailAnnotations.ALL` (one that is not would silently never fire), rules share round queries rather than issuing one each, and `all()` hands out a copy so a caller cannot empty the registry for the rest of the JVM's life |
| `ModernJavaDetectorTest` | The detectors that read the declaration rather than the annotation pair: array component under `@AIImmutable`, `@AIExtensible` on final/record/enum/sealed, `@AIPure` on `void`, `@AIPublicAPI` on something unreachable, `ThreadLocal` strategy under virtual threads, unnamed package. Each case is paired with a clean fixture asserting the detector stays quiet |
| `OutputOrderDeterminismTest` | Generated output is a function of the annotations, not of the order javac enumerated the sources in. Compiles the same classes twice with the file list reversed and requires byte-identical `CLAUDE.md` — `getElementsAnnotatedWith` has no specified iteration order, so without the sort in `GuardrailModel` two developers rewrite each other's committed guardrail files |
| `SignatureCaptureTest` | `ElementSignature` is computed only when the enforcing mode will read it. Drives `AnnotationCollector` inside a real compilation, because a mocked `Element` yields an empty signature either way and would pass whatever the collector did |
| `ElementNamingUnitTest` | FQN construction for TYPE, METHOD, FIELD, and PACKAGE elements |
| `WriteCacheTest` | Cache hit/miss/invalidation/persistence/corruption-fallback |
| `WriteCacheAsyncTest` | Write-cache correctness under concurrent access |
| `GuardrailFileWriterAsyncTest` | The parallel write phase's shape under stress — one file per worker over a shared `GuardrailFileWriter` and `WriteCache` — asserting hand-authored content outside the markers survives and exactly one marker pair remains. `ParallelFileWriteTest` covers one real compile; this one repeats the write until a race would show |
| `ModuleSidecarAsyncTest` | Concurrent `save()` + `readAll()` in one reactor root: no torn read (a body that was never saved) and no wrongful prune (a sibling's sidecar deleted as malformed mid-write) |
| `ModuleSidecarResilienceTest` | The deterministic half of the same problem: the rename retry (succeeds after transient failures, gives up at the attempt cap and removes its temp file, does not retry a non-filesystem failure) and the unreadable-vs-malformed split (`readAll` prunes corrupt content but never a file it could not read) |
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
| `NewAnnotationsV6DefinitionTest` | Definition-level tests for the evidence-based wave (`@AIGenerated`, `@AILoadBearing`, `@AIBannedApi`, `@AIThreadAffinity`, `@AIKeepInSync`): retention, targets, and which attributes are required vs. defaulted |
| `NewAnnotationsV6EndToEndTest` | End-to-end content for the evidence-based wave — asserts the *wording* each annotation exists to produce (the `@AIGenerated` redirect, the "do not add locks" warning on `@AIThreadAffinity`) across CLAUDE.md's XML blocks, `.cursorrules`, `llms-full.txt`, and granular rules |
| `NewAnnotationsV6ValidationTest` | The 11 new validation warnings, plus clean fixtures asserting each stays silent when its condition is not met |
| `AIGuardrailProcessorIntegrationTest` | Full workflow (requires `-Drun.integration.tests=true`) |
| `ClaudeLocalEndToEndTest` | `CLAUDE.local.md` generation for Claude Code local overrides |
| `ClaudeSkillEndToEndTest` | `.claude/skills/vibetags-guardrails/SKILL.md` generation, including required Skill frontmatter |
| `ClaudeGranularEndToEndTest` | `.claude/rules/*.md` granular rule generation for Claude Code, including `paths:` frontmatter |
| `CopilotGranularEndToEndTest` | `.github/instructions/*.instructions.md` granular rule generation for GitHub Copilot, including `applyTo:` frontmatter |
| `GranularIndexEndToEndTest` | Scoped-rules index: dual opt-in (aggregate + granular) collapses the aggregate to a locked/core/safety summary plus a per-element index; single opt-in stays full; reuse renderers (Cline) stay full while `CLAUDE.local.md` mirrors `CLAUDE.md` |
| `LeanIndexedRootEndToEndTest` | Lean indexed reactor root (`.vibetags-root-index`): modules are linked rather than embedded, the safety tier stays inline per module (#332), aggregates without a granular sibling keep the full merge, and the no-opt-in shape is unchanged |
| `PerModuleOutputEndToEndTest` | Per-module (nested) output: a module that opts into its own `CLAUDE.md`/granular dir gets a module-scoped file (only its own guardrails), the reactor root still merges all modules, the module index composes, and a non-opted module gets nothing |
| `RoleConfigTest` | `.vibetags-roles` parsing, glob→regex (stars/braces), first-match routing, exact-FQN matchers, package/directory globs, content hash |
| `RoleBasedGranularEndToEndTest` | Role/topic grouping: matching classes grouped into `<role>` files with multi-glob frontmatter; unmatched class keeps its per-class file; FQN override; first-match-wins; roles-off stays per-class; per-module `.vibetags-roles` |
| `GranularSectionsTest` | Granular section collapsing (#313): shared-line hoisting (leading and trailing), `Applies to` lists, per-section independence, qualified (role-file) headings, pluralization, and byte-identical single-stanza output |
| `GranularHoistingEndToEndTest` | End-to-end #313: three `@AIPrivacy` fields state the constant rule once; a lone field keeps the historical singular form; a role file spanning two classes collapses across owners |
| `MirrorEndToEndTest` | Cross-module mirroring (#312): `.vibetags-mirror` opt-in, empty vs. explicit source list, `glob =` override, namespaced filenames surviving the target's and siblings' cleanups, orphan removal, config-edit cache invalidation, check-mode drift, and no-op when unconfigured |
