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
14. Version literals live in `vibetags-parent/pom.xml` and nowhere else; bump via `tools/set-version.sh`. `BuildVersionParityTest`
15. Logging is law: `domain.event key=value`, `reason=` on every `.skip`, tested events are contracts. [docs/LOGGING.md](docs/LOGGING.md), `GuardrailFileWriterLogContractTest`
16. Every module that compiles Java runs the same static-analysis stack, and each module keeps its own `.mvn/jvm.config` because Error Prone silently does not run without it. `BuildToolchainParityTest`
17. A round that was not shown every annotated source in its module writes nothing, sweeps nothing, and says so. `PartialRoundGuardrailLossTest`

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
- Third-party corpus: `corpus/run-corpus.sh` (Java, javac) and `corpus/run-corpus-jvm.sh`
  (Kotlin, Groovy, Scala, each built by its own Gradle). Both run in CI on every PR;
  [corpus/README.md](corpus/README.md) says what each asserts and what they have found.
- Run `pre-commit run --all-files` after `git add`, before committing.

## Reference docs (read on demand)

- [docs/README.md](docs/README.md) — the documentation index: every document, the question it answers, and which tier it is in (maintained reference, unshipped proposal, frozen archive). Read it when the right file is not obvious from the list below.
- [docs/MULTI-MODULE.md](docs/MULTI-MODULE.md) — reactors: sidecar merge, per-module output, `.vibetags-root-index`, `.vibetags-roles`, `.vibetags-mirror`, granular file layout.
- [docs/PROCESSOR.md](docs/PROCESSOR.md) — processor options, write cache + fingerprint short-circuit, check mode, `.vibetags-locks`, SPI/Gradle incremental.
- [docs/ANNOTATIONS.md](docs/ANNOTATIONS.md) — adding or changing an annotation: full table, semantics, validation warnings.
- [docs/PLATFORMS.md](docs/PLATFORMS.md) — adding a platform, or a question about a specific output file.
- [docs/JVM-LANGUAGES.md](docs/JVM-LANGUAGES.md) — Kotlin, Groovy, Scala and Clojure: what is
  supported, what is silently lost, and how each rating is measured rather than claimed.
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
    <file path="se.deversity.vibetags.processor.internal.TransitiveManifest.RESOURCE_PACKAGE">
      <reason>Must stay a valid Java package name. javac&#39;s CLASS_PATH location skips archive directories that are not package identifiers, so moving these manifests under META-INF/ leaves Filer.getResource listing zero entries and transitive discovery fails silently while the conventional location looks correct. TransitiveManifestPathTest pins the working path.</reason>
    </file>
    <file path="se.deversity.vibetags.processor.model.GuardrailAnnotations.ALL">
      <reason>Append only. This order fixes the insertion order of every LinkedHashSet downstream, so reordering or removing an entry rewrites generated files in every consuming build, with nothing failing to name the cause. BuildFingerprint hashes in its own separately pinned order; the two are not the same list and must not be aligned.</reason>
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
    <element path="se.deversity.vibetags.processor.internal.PartialRoundDetector">
      <sensitivity>high</sensitivity>
      <note>Both conditions in unreadAnnotatedSources are load-bearing and neither may be dropped as redundant: without the missing-element check an excluded-but-annotated source stops VibeTags writing for good, and without the unread-source check a genuinely deleted annotation can never have its rule file retired</note>
    </element>
    <element path="se.deversity.vibetags.processor.internal.WriteCache">
      <sensitivity>high</sensitivity>
      <note>Per-file content cache backed by .vibetags-cache; false positives (wrongly treating stale output as unchanged) would silently corrupt generated files</note>
    </element>
  </core_elements>

<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>
  <security_elements>
    <element path="se.deversity.vibetags.processor.internal.JsonValueSpans">
      <aspect>Splices annotation text, including attributes copied out of third-party dependency JARs, into greptile.json, a review configuration the user owns. The span body must stay Escape.json-encoded and marker-defused: without the first a dependency can close the string and add settings such as skipReview, and without the second it can end the span early so the value grows a copy of itself on every build.</aspect>
    </element>
    <element path="se.deversity.vibetags.processor.internal.TransitiveManifestReader">
      <aspect>Trust boundary. Manifests read here are authored by third-party dependency JARs, and their rules are merged into the consumer&#39;s always-loaded instruction files, so a dependency can put text in front of the consumer&#39;s agent. Treat every value as untrusted input: keep the MAX_LOOKUPS cap and the SKIPPED_PREFIXES list, and route interpolation through Escape rather than widening what a manifest may contain.</aspect>
    </element>
    <element path="se.deversity.vibetags.processor.internal.content.Escape">
      <aspect>Output encoding for the generated instruction files. Every interpolated value reaches an aggregate through here, including annotation attributes copied verbatim out of third-party dependency JARs; a weakened method lets that text close a tag and forge its own &lt;locked_files&gt; or &lt;rule&gt; entries in a file the agent loads on every session.</aspect>
    </element>
  </security_elements>

<rule>Elements listed in <security_elements> are security-critical. Never weaken their security properties. Every proposed change must be explicitly reviewed for security impact.</rule>
  <scoped_rules>
    <note>Detailed per-element guardrails for the elements below live in scoped rule files that load automatically when the matching source file is opened. Unless an entry carries an explicit path, its file is .claude/rules/{path, every non-alphanumeric character replaced by &#39;-&#39;}.md. Consult the file before modifying an element.</note>
    <element path="se.deversity.vibetags.processor.AIGuardrailProcessor"/>
    <element path="se.deversity.vibetags.processor.VibeTagsLogger"/>
    <element path="se.deversity.vibetags.processor.internal.AnnotationCollector"/>
    <element path="se.deversity.vibetags.processor.internal.BuildFingerprint"/>
    <element path="se.deversity.vibetags.processor.internal.EnforcementBaseline"/>
    <element path="se.deversity.vibetags.processor.internal.GranularRulesWriter"/>
    <element path="se.deversity.vibetags.processor.internal.GuardrailFileWriter"/>
    <element path="se.deversity.vibetags.processor.internal.JsonValueSpans"/>
    <element path="se.deversity.vibetags.processor.internal.ModuleSidecar"/>
    <element path="se.deversity.vibetags.processor.internal.PartialRoundDetector"/>
    <element path="se.deversity.vibetags.processor.internal.ServiceRegistry"/>
    <element path="se.deversity.vibetags.processor.internal.TransitiveManifest"/>
    <element path="se.deversity.vibetags.processor.internal.TransitiveManifestReader"/>
    <element path="se.deversity.vibetags.processor.internal.WriteCache"/>
    <element path="se.deversity.vibetags.processor.internal.content"/>
    <element path="se.deversity.vibetags.processor.internal.content.Escape"/>
    <element path="se.deversity.vibetags.processor.internal.content.PlatformRenderer"/>
    <element path="se.deversity.vibetags.processor.internal.validation.ValidationRule"/>
    <element path="se.deversity.vibetags.processor.model"/>
    <element path="se.deversity.vibetags.processor.model.GuardrailAnnotations"/>
  </scoped_rules>

<rule>When you work on any element listed in <scoped_rules>, open its referenced rule file and apply the guardrails there. The rule files are the authoritative source for those elements.</rule>
</project_guardrails>

<rule>Never propose edits to files listed in <locked_files>.</rule>
<!-- VIBETAGS-END -->
