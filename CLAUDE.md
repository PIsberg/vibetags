# CLAUDE.md

VibeTags is a compile-time Java annotation processor (`AIGuardrailProcessor`) that generates AI
platform guardrail files from `@AI*` annotations. Module map and per-subproject notes:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#repository-layout-notes). The reasoning behind every
invariant below: [docs/LOAD-BEARING.md](docs/LOAD-BEARING.md#the-invariants-stated-in-full).

## Tier-1 invariants

Each line names its enforcing check; run it, do not just read this list.

1. File presence is the only platform opt-in; never create an output file (sole exception: the codex sidecar). `GuardrailLifecycleEndToEndTest`
2. Hand-authored content outside `VIBETAGS-START`/`END` markers must never be lost. `GuardrailFileRecoveryEndToEndTest`, `MarkerInjectionTest`
3. `process()` returns `false`; all writing happens on `processingOver()`. `AIGuardrailProcessorProcessTest`
4. `AGENTS.md` is written only as the sole AI config file, or with an existing marker pair. `AgentsMdSoleFallbackTest`
5. All 44 annotations stay `RetentionPolicy.SOURCE`. `AnnotationDefinitionsTest`
6. An aggregate collapses to a scoped-rules index when its granular directory is opted in; the safety buckets stay inline. `GranularRulesEndToEndTest`
7. The rendering layer (`internal/content/`) never imports compiler APIs. `ArchitectureRulesTest`
8. A new validation check is a rule entry under `internal/validation/`, never a loop in `AnnotationValidator`. `ValidationRuleUnitTest`
9. Adding a platform or annotation follows the `add-platform` / `add-annotation` skill, never improvisation.
10. A YAML renderer declares `mergeShape()`; a marker-free renderer whose file varies declares `wholeFileMerge()`. `YamlMergeShapeContractTest`, `MultiModuleWholeFileMergeTest`
11. Dependency manifests live under a Java package path, never `META-INF/`. `TransitiveGuardrailLifecycleE2ETest`
12. Anything that becomes generated content reaches `BuildFingerprint`. `TransitiveFingerprintTest`
13. Granular rule files are written through `ModuleSidecar.mergeGranular`, never directly. `MultiModuleGranularRoleMergeTest`
14. Version literals live in `vibetags-parent/pom.xml` and nowhere else; bump via `scripts/set-version.sh`. `BuildVersionParityTest`
15. Logging is law: `domain.event key=value`, `reason=` on every `.skip`, tested events are contracts. [docs/LOGGING.md](docs/LOGGING.md), `GuardrailFileWriterLogContractTest`
16. Every module that compiles Java runs the same static-analysis stack, and each module keeps its own `.mvn/jvm.config` because Error Prone silently does not run without it. `BuildToolchainParityTest`

## Build and test

Build order: `vibetags-annotations` → `vibetags` → `vibetags-bom` → consumers. Run from each
subproject's own directory; the processor writes at the JVM working directory unless
`vibetags.root` is set.

```bash
cd vibetags-annotations && mvn install
cd ../vibetags         && mvn clean install
cd ../vibetags-bom     && mvn install            # Maven only

# From vibetags/ (tier split and per-class map: docs/TESTS.md):
mvn test                            # fast tier; skips @Tag("e2e")
mvn test -Pe2e                      # the whole suite; what CI runs
mvn test -Dtest=SomeTest            # -Dtest overrides the tag filter
mvn compile -Pself-annotate         # regenerate this repo's own guardrail files

cd examples/basic && mvn clean compile     # consumer fixture; library must be installed first
```

## Scoping and hygiene

- Per-element guardrails: the generated block below indexes `.claude/rules/`, loaded on demand
  by glob. Whether the rules in this file actually bind an agent is measured, not assumed:
  [evals/README.md](evals/README.md).
- Run `pre-commit run --all-files` after `git add`, before committing.

## Reference docs (read on demand)

- [docs/MULTI-MODULE.md](docs/MULTI-MODULE.md) — reactors: sidecar merge, per-module output, `.vibetags-root-index`, `.vibetags-roles`, `.vibetags-mirror`, granular file layout.
- [docs/PROCESSOR.md](docs/PROCESSOR.md) — processor options, write cache + fingerprint short-circuit, check mode, `.vibetags-locks`, SPI/Gradle incremental.
- [docs/ANNOTATIONS.md](docs/ANNOTATIONS.md) — adding or changing an annotation: full table, semantics, validation warnings.
- [docs/PLATFORMS.md](docs/PLATFORMS.md) — adding a platform, or a question about a specific output file.
- [docs/TESTS.md](docs/TESTS.md) — which test class covers what.
- [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md) — every third-party artifact, why it is here, what ships to consumers and what only runs the build.
- [docs/LOAD-BEARING.md](docs/LOAD-BEARING.md) — processing flow, marker rules, the scoped-rules index, the internal class map, and the invariants in full.
- [docs/LOGGING.md](docs/LOGGING.md) — the logging contract behind invariant 15.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — deep dive: system diagram, data flow, design decisions, limitations, repository layout notes, design history.
- [USAGE.md](USAGE.md) — consumer-facing usage (how to add VibeTags to a project).
- [README.md](README.md) — the test-enforced project facts (44 annotations, 37 platforms, 49 config files; pinned by `ProjectFactsConsistencyTest`), the platform table, install snippets.
- [docs/WORKFLOW.md](docs/WORKFLOW.md) — what CI actually runs, step by step, and why each verification exists.
- [docs/RELEASING.md](docs/RELEASING.md) — the release process.
- [docs/CHANGELOG.md](docs/CHANGELOG.md) — what each release changed and why.
- [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) — contribution workflow; [docs/SECURITY.md](docs/SECURITY.md) — reporting vulnerabilities.
- [evals/README.md](evals/README.md) — instruction evals: whether the rules in this file actually bind an agent, measured.
- [docs/vibetags-in-practice.md](docs/vibetags-in-practice.md) — survey of annotation/platform usage across five real consumer codebases (2026-07-16).

<!-- VIBETAGS-START -->
<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->
<project_guardrails>
  <locked_files>
    <file path="se.deversity.vibetags.processor.AIGuardrailProcessor.generateFiles()">
      <reason>Step order is load-bearing: fingerprint check → sidecar write → sidecar read → merge → file write → cache flush; reordering steps silently skips regeneration or corrupts multi-module output</reason>
    </file>
  </locked_files>
  <core_elements>
    <element path="se.deversity.vibetags.processor.AIGuardrailProcessor">
      <sensitivity>critical</sensitivity>
      <note>JSR 269 entry point; orchestrates annotation discovery, fingerprint short-circuit, sidecar aggregation, and all file writes</note>
    </element>
    <element path="se.deversity.vibetags.processor.internal.GuardrailFileWriter">
      <sensitivity>high</sensitivity>
      <note>Atomic marker-aware file writer; invariant: hand-authored content outside VIBETAGS-START/END markers must never be overwritten or lost</note>
    </element>
    <element path="se.deversity.vibetags.processor.internal.ModuleSidecar">
      <sensitivity>high</sensitivity>
      <note>Per-module sidecar for multi-module Maven/Gradle builds; the .vibetags-mod-* file format is shared across independently compiled modules — format changes break backward compatibility</note>
    </element>
    <element path="se.deversity.vibetags.processor.internal.WriteCache">
      <sensitivity>high</sensitivity>
      <note>Per-file content cache backed by .vibetags-cache; false positives (wrongly treating stale output as unchanged) would silently corrupt generated files</note>
    </element>
  </core_elements>

<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>
  <scoped_rules>
    <note>Detailed per-element guardrails for the elements below live in scoped rule files that load automatically when the matching source file is opened. Consult the referenced file before modifying an element.</note>
    <element path="se.deversity.vibetags.processor.AIGuardrailProcessor" rules=".claude/rules/se-deversity-vibetags-processor-AIGuardrailProcessor.md"/>
    <element path="se.deversity.vibetags.processor.VibeTagsLogger" rules=".claude/rules/se-deversity-vibetags-processor-VibeTagsLogger.md"/>
    <element path="se.deversity.vibetags.processor.internal.AnnotationCollector" rules=".claude/rules/se-deversity-vibetags-processor-internal-AnnotationCollector.md"/>
    <element path="se.deversity.vibetags.processor.internal.BuildFingerprint" rules=".claude/rules/se-deversity-vibetags-processor-internal-BuildFingerprint.md"/>
    <element path="se.deversity.vibetags.processor.internal.GranularRulesWriter" rules=".claude/rules/se-deversity-vibetags-processor-internal-GranularRulesWriter.md"/>
    <element path="se.deversity.vibetags.processor.internal.GuardrailFileWriter" rules=".claude/rules/se-deversity-vibetags-processor-internal-GuardrailFileWriter.md"/>
    <element path="se.deversity.vibetags.processor.internal.ModuleSidecar" rules=".claude/rules/se-deversity-vibetags-processor-internal-ModuleSidecar.md"/>
    <element path="se.deversity.vibetags.processor.internal.ServiceRegistry" rules=".claude/rules/se-deversity-vibetags-processor-internal-ServiceRegistry.md"/>
    <element path="se.deversity.vibetags.processor.internal.WriteCache" rules=".claude/rules/se-deversity-vibetags-processor-internal-WriteCache.md"/>
  </scoped_rules>

<rule>When you work on any element listed in <scoped_rules>, open its referenced rule file and apply the guardrails there. The rule files are the authoritative source for those elements.</rule>
</project_guardrails>

<rule>Never propose edits to files listed in <locked_files>.</rule>
<!-- VIBETAGS-END -->
