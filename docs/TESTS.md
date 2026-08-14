# Test Structure

Index of every test class in `vibetags/src/test` and what it covers — use this to find which test to run or extend for a given change.

## Running the tests: the fast tier and `-Pe2e`

`mvn test` runs the fast tier only — it skips every class tagged `@Tag("e2e")`. `mvn test -Pe2e` runs
everything, and that is what CI runs on all three legs that execute tests (`build-maven`,
`cross-platform`, `build-gradle`). Gradle mirrors both: `gradlew test` and `gradlew test -Pe2e`.

Measured on a 16-core Windows machine on 2026-08-10, warm compile, `-Dmaven.pmd.skip -Dspotbugs.skip`:

| Command | Classes | Tests | Wall clock |
|---|---|---|---|
| `mvn test` | 88 | 957 | 41s |
| `mvn test -Pe2e` | 142 | 1546 | 61s |

Compile, Error Prone and JaCoCo account for the first few seconds of either figure rather than
tests — `mvn test -DskipTests` costs 4.6s fully warm on the same machine — so the tests themselves
go from ~36s to ~56s.

**What is tagged, and why.** The 52 classes that took over 5s in the full-suite baseline of
2026-08-06: 599.66s of the 704.15s the suite spent. The rule is cost, not category.
`NewAnnotationsV4EndToEndTest` runs in 0.01s and stays in the fast tier; `AIGuardrailProcessorUnitTest`
takes 13.71s and does not. Do not tag by name suffix — `EndToEnd` correlates with nothing here.

**The fast tier still exercises the processor.** 21 of its classes drive `javac` through
`ProcessorTestHarness`: the V3/V5/V6 annotation round-trips, Qwen and Claude-skill output, granular
hoisting, the validation rules and the parallel write path. A change that breaks generation fails
locally. What the fast tier does *not* cover is the expensive machinery — the write cache, build
fingerprinting, multi-module reactors and mirroring, international characters, and the async stress
loops. Those are exactly the areas where "it compiled and the file looked right" is not evidence, so
run `-Pe2e` before pushing anything that touches them.

**Naming a test overrides the tag.** `mvn test -Dtest=WriteCacheProcessorIntegrationTest` runs that
class even though it is tagged, and so does `gradlew test --tests '*WriteCacheProcessorIntegrationTest'`.
Both build files special-case this deliberately: without it the command prints `Tests run: 0` and
`BUILD SUCCESS`, a green result that ran nothing.

**Per-class times are not stable enough to re-derive the split from.** They are measured under
concurrency (`src/test/resources/junit-platform.properties` sets `parallel.enabled=true`), so a
class's recorded time depends on what else was running beside it. `Coverage1dot0GapTest` measured
4.71s in the full suite and 13.93s in the fast tier on its own; the fast tier's summed class time
rose from 104s to 220s purely by having fewer classes to share the machine with. If you re-tag, take
the numbers from a full-suite run, and trust wall clock over the sum.

`TestTagVocabularyTest` fails the build if a tag is misspelled, or if `pom.xml` and `build.gradle`
stop excluding the same one.

## Index


| Class | Coverage |
|---|---|
| `AnnotationDefinitionsTest` | Annotation structure and defaults (the original 27 annotations; the 12 newest are covered by `NewAnnotationsV4`/`V5` definition tests) |
| `JsonTest` | The dependency-free JSON reader: escapes, ordering, and above all what it does with malformed input from a JAR the build did not write |
| `TransitiveRuleTest` | The inherited-rule value type: immutability (which earns a SpotBugs exclusion), ordering, equality |
| `TransitiveManifestTest` | Manifest format: round-trip, byte-stable serialisation, version skip, and re-deriving the tier rather than trusting the JAR's claim |
| `TransitiveManifestMemberOrderTest` | That an annotation's attributes are ordered by name. `Class.getDeclaredMethods()` is unordered and differs between JDK 25 and 26, which made the same sources publish different manifests depending on who compiled them |
| `TransitiveManifestReaderTest` | Candidate-key derivation from imports, skipped prefixes, and the pre-extracted directory fallback |
| `TransitiveSectionTest` | The inherited-guardrail appendix: attribution, tier order, and which platforms carry it (pinned both ways) |
| `TransitiveFingerprintTest` | That inherited rules reach `BuildFingerprint` — the check the end-to-end test cannot make |
| `TransitiveExampleCoverageTest` | That `example-multimodule/` still demonstrates the feature rather than quietly demonstrating nothing |
| `TransitiveGuardrailLifecycleE2ETest` (`e2e`) | The whole lifecycle through a real `javac`: library compiled and jarred, consumer resolving it off the compile classpath. Includes the negative that shaped the design — a manifest under `META-INF/` is invisible — and a compilation driven through a Gradle-shaped `ProcessingEnvironment` wrapper, which is what discovery silently failed on |
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
| `GuardrailLifecycleEndToEndTest` | The second compile: editing an annotation's value replaces the old text everywhere, removing one drops it from the merged root and the whole-file JSON/TOML, and deleting a generated file opts that platform out permanently (and restoring it opts back in). Also pins the documented limitation that emptying a module of *every* annotation leaves its last contribution until its sidecar is deleted |
| `ProjectLifecycleEndToEndTest` | The project's timeline rather than one compile: day zero with nothing opted in (no guardrail file written, and the footprint pinned to VibeTags' own three state files), the steady state of repeated no-change builds (every generated file byte-identical *and* untouched across three builds), a platform opted into months later, and a module deleted or renamed out of a reactor. Three of its tests began as pins on measured limitations and now assert the fixed behaviour: the fingerprint short-circuit fires on an unchanged rebuild and keeps firing, a platform opted into after a module last compiled warns and names the modules missing from the file, and a deleted module takes its granular rule files with it. Each carries the two guards that came with it — module identity in the fingerprint, staleness in the skip condition, and the survivor exclusion that stops a departure deleting a shared role file |
| `GuardrailFileRecoveryEndToEndTest` | The generated file is still there but no longer looks the way VibeTags left it: the marker block deleted by a merge-conflict resolution, a start marker with no end from a half-applied patch, a file truncated to empty, an edit made *inside* the block, and a `.vibetags-cache` lost to `git clean` (output must stay byte-identical without it). As much a test of `WriteCache.allCachedFilesStable()` as of the writer — an unchanged annotation set is exactly when the short-circuit would skip past the damage |
| `AnnotationTransitionEndToEndTest` | The element survives and what is true about it changes: the annotation *type* swapped on the same FQN (the old bucket must go, or the file states two contradictory things about one class), a class moved between modules in a reactor (the losing module's sidecar must drop it — the failure here is a duplicate, not an absence), a role renamed in `.vibetags-roles`, and the roles config deleted entirely (per-class fallback, old role files retired) |
| *(CI step)* `example-all-tiers` | Not a JUnit class: the workflow builds the all-tiers example and asserts the split — six safety buckets inline at Tier 1 and no verbose bucket, a Tier-1 pointer naming both lower tiers per module, Tier-2 files that do not leak each other, role-grouped Tier-3 files with `paths:` front-matter, and a parameter-level rule |
| `SourceSetIsolationEndToEndTest` | `compile` and `test-compile` are separate rounds over the same module: neither erases the other's guardrails or rule files, two source sets still render as one region, and a module's own aggregate merges across them (#330) |
| `MultiModuleGranularRoleMergeTest` | A role matched in several modules resolves to one shared rule file: every module's guardrails survive, a one-module rebuild leaves the file byte-identical, reactor order does not change it, check mode agrees with generation, unrouted classes keep their per-class file, and the same merge covers two source sets of one module and a module's own nested rules (#365) |
| `GranularContributionMergeTest` | The merge primitives underneath it: the contribution's wire form (round-trip, no-glob case, malformed value rejected), `mergeGranular` (lone contributor verbatim, several wrapped in sub-markers with globs unioned, two source sets counted as one contributor), `mergeModuleGranular` scoped to one region, and sidecar persistence — including a pre-#365 sidecar with no contributions and a corrupt one (#365) |
| `WrappedProcessingEnvironmentTest` | Module identity and `.vibetags-locks` positions survive a wrapped `ProcessingEnvironment` (Gradle's incremental decorator): identity via `Elements.getFileObjectOf` (#331), positions via the reflective Tree-API unwrap |
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
| `FingerprintShortCircuitTest` | End-to-end short-circuit skip behaviour when inputs are unchanged, and non-skip when `-Avibetags.project` / `-Avibetags.module` change |
| `ErrorRaisedRoundGuardTest` | The final round leaves guardrail files, sidecars and cache untouched when the compilation already raised errors |
| `CheckModeTest` | Opt-in check mode (`-Avibetags.check=true`): pass/fail verdicts, zero writes, multi-module merge parity, dry-run `GuardrailFileWriter` |
| `LocksReportEndToEndTest` | `.vibetags-locks` machine-readable lock report: class/method positions via the javac Tree API, JSON escaping, opt-in behaviour |
| `IncrementalProcessorDeclarationTest` | Verifies `META-INF/gradle/incremental.annotation.processors` is present and declares the processor as `aggregating` |
| `GuardrailContentBuilderUnitTest` | Per-annotation content generation for each platform |
| `GuardrailFileWriterCoverageTest` | `GuardrailFileWriter` branch coverage |
| `GuardrailFileWriterEdgeCaseTest` | Edge cases: empty content, missing parent dir, read-only file |
| `GranularRulesWriterUnitTest` | Per-class rule file writes and cleanup ordering |
| `CleanupGranularDirectoryTest` | Orphan granular file removal after annotation deletion |
| `AnnotationCollectorUnitTest` | `AnnotationCollector` accumulation across multiple rounds — one case per registered annotation type, so a bucket dropped from `GuardrailAnnotations.ALL` fails here |
| `ArchitectureRulesTest` | Formatter/renderer statelessness, and the compiler boundary: `content` and `model` must not import `javax.lang.model`/`com.sun.source`, `content` must not depend on `internal`, `model` must not depend on either |
| `ElementTagMappingTest` | `ElementTag` mirrors `ElementKind` name-for-name — fails if a JDK adds a kind, before generated output can report it as `UNKNOWN` |
| `AnnotationValidatorUnitTest` | All compile-time validation warning combinations |
| `LockedOverrideValidationTest` | The unlocked-override warning: fires for a same-compilation override of a locked concrete method, quiet for abstract locks and for overrides that carry the lock themselves |
| `AnnotationMirrorAnchorTest` | Pair-rule diagnostics anchor at the conflicting annotation's mirror, so the IDE caret lands on the annotation line rather than the declaration |
| `LocalAndAnonymousElementsEndToEndTest` | Guardrails inside method bodies: invisible to JSR 269, warned about via the Tree-API body scan, plus the body-only boundary where the processor never runs at all |
| `ValidationRegistryTest` | Properties of the rule registry itself: every rule scans an annotation that is actually in `GuardrailAnnotations.ALL` (one that is not would silently never fire), rules share round queries rather than issuing one each, and `all()` hands out a copy so a caller cannot empty the registry for the rest of the JVM's life |
| `ModernJavaDetectorTest` | The detectors that read the declaration rather than the annotation pair: array component under `@AIImmutable`, `@AIExtensible` on final/record/enum/sealed, `@AIPure` on `void`, `@AIPublicAPI` on something unreachable, `ThreadLocal` strategy under virtual threads, unnamed package. Each case is paired with a clean fixture asserting the detector stays quiet |
| `OutputOrderDeterminismTest` | Generated output is a function of the annotations, not of the order javac enumerated the sources in. Compiles the same classes twice with the file list reversed and requires byte-identical `CLAUDE.md` — `getElementsAnnotatedWith` has no specified iteration order, so without the sort in `GuardrailModel` two developers rewrite each other's committed guardrail files |
| `SignatureCaptureTest` | `ElementSignature` is computed only when the enforcing mode will read it. Drives `AnnotationCollector` inside a real compilation, because a mocked `Element` yields an empty signature either way and would pass whatever the collector did |
| `ElementNamingUnitTest` | FQN construction for TYPE, METHOD, FIELD, and PACKAGE elements |
| `WriteCacheTest` | Cache hit/miss/invalidation/persistence/corruption-fallback |
| `WriteCacheAsyncTest` | Write-cache correctness under concurrent access |
| `GuardrailFileWriterAsyncTest` | The parallel write phase's shape under stress — one file per worker over a shared `GuardrailFileWriter` and `WriteCache` — asserting hand-authored content outside the markers survives and exactly one marker pair remains. `ParallelFileWriteTest` covers one real compile; this one repeats the write until a race would show |
| `ModuleSidecarAsyncTest` | Concurrent `save()` + `readAll()` in one reactor root: no torn read (a body that was never saved) and no wrongful prune (a sibling's sidecar deleted as malformed mid-write) |
| `ModuleSidecarResilienceTest` | The deterministic half of the same problem: the rename retry (succeeds after transient failures, rides out a blocker past the old 10-attempt cap, keeps a retry budget over 2 s, gives up at the attempt cap and removes its temp file, does not retry a non-filesystem failure) and the unreadable-vs-malformed split (`readAll` prunes corrupt content but never a file it could not read). The budget assertion is what stops the 2026-08-06 flake being reintroduced by shrinking the schedule |
| `WriteCacheProcessorIntegrationTest` | Cache integration: created on first compile, stable mtimes on second, rewrite on external edit |
| `StreamingByteCompareTest` | Streaming byte-compare for non-marker overwrite files |
| `StripLegacyVibeTagsBlockEdgeCasesTest` | Legacy marker migration edge cases (files without markers) |
| `WriteFileFrontMatterTest` | YAML front-matter preservation in `.mdc`/`.md` granular rule files |
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
| `AIGuardrailProcessorIntegrationTest` | Full workflow. Self-contained via `ProcessorTestHarness`. Tagged `@Tag("e2e")` (13.63s), so it needs `mvn test -Pe2e` — the older `-Drun.integration.tests=true` gate was dropped in 2026-04 and is not what tags it now |
| `TestTagVocabularyTest` | The fast/e2e split itself: every `@Tag` value is one the build files filter on, and `pom.xml` and `build.gradle` still exclude the same tag |
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

## Other modules

`vibetags-cli/src/test` has its own small suite, run by `cd vibetags-cli && mvn test` and by the
`build-maven` and `cross-platform` CI legs (the latter are the only place its filesystem behaviour
is exercised on Windows and macOS path separators):

| Test class | What it covers |
|---|---|
| `InitCommandTest` | `vibetags init`: `--list` shows opt-in keys without creating anything; `--platforms` creates empty opt-in files (directories for `*_granular` keys, parents for nested paths); an existing file is reported active and never truncated; an unknown key rejects the whole request before creating the valid half; bare `init` creates nothing; `--dir` targets another root |
| `OnboardingLifecycleTest` | The onboarding path end to end, and the only place the CLI and the processor run against one directory: `init` creates the opt-in files, a real `AIGuardrailProcessor` round fills them, and `doctor` reports healthy, including `markers: all intact`, which is doctor recognising the marker form the writer actually emits rather than one a fixture typed. Plus the negative: half a marker pair removed from a *generated* file must be a finding |
| `DoctorCommandTest` | `vibetags doctor`: exit 0 only for a wired project with intact markers; findings (exit 1) for missing processor/annotations wiring, no opt-in files, unbalanced `VIBETAGS-START`/`END` pairs, and no build file; the AGENTS.md pointer rule is explained as a note, not a finding |
