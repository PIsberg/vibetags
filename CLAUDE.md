# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

VibeTags is a **compile-time Java annotation processor** (`se.deversity.vibetags.processor.AIGuardrailProcessor`) that generates AI platform-specific guardrail configuration files from Java annotations. Zero runtime overhead — all annotations use `RetentionPolicy.SOURCE`.

Independent Maven (and where noted, Gradle) subprojects:

- `vibetags-annotations/` — the 44 `@interface` classes, zero deps. On the consumer's compile classpath. **Build first.**
- `vibetags/` — the processor itself (`AIGuardrailProcessor` + `VibeTagsLogger`). On the consumer's annotation-processor path only.
- `vibetags-bom/` — pom-only BOM managing the published versions. Maven only; Gradle reads it via `mavenLocal()` / `platform(...)`.
- `vibetags-cli/` — companion CLI (`init` creates opt-in files, `doctor` reports project health). Depends on `vibetags` as a library for `ServiceRegistry.optInKeys()` and the marker constants — it must never carry its own platform list. Build after `vibetags`.
- `example/`, `example-multimodule/`, `example-multimodule-indexed/` — demo consumers (the last two are reactors, asserted in CI).
- `example-kotlin/`, `example-groovy/`, `example-scala/` — JVM-language consumers, all built on the JDK 21 Gradle CI leg. Kotlin (kapt) and Groovy (joint-compilation stubs + `javaAnnotationProcessing`) get full support with the same stub caveats (no body-scoped annotations, stub positions); Scala is Java-sources-only (scalac has no JSR 269) and its CI step asserts the annotated Scala class does NOT appear. Clojure is documented as impossible (no javac; SOURCE retention inexpressible) — support matrix in USAGE.md.
- `load-tests/` — standalone benchmark harness; pins `<processor.version>` directly (intentional — cross-version comparison is the wrong workload for a BOM).
- `action/locked-files/` — GitHub Action consuming `.vibetags-locks`.

- `vibetags-parent/` — pom-only. **Every version in the repository is declared here and nowhere
  else**: third-party dependencies, plugins, and `<revision>`, which is the VibeTags release
  version. Not published: `flatten-maven-plugin` resolves it away before deploy, so the POMs on
  Maven Central are self-contained and consumers gain nothing new to resolve. Subprojects reference
  it by `<relativePath>`, so each still builds straight from a checkout with no install step.

Build order matters: `vibetags-annotations` → `vibetags` → `vibetags-bom` → `example` (or `load-tests`). CI installs them in this order; do the same locally. The parent needs no install of its own.

**Never write a version literal into a managed pom.** `vibetags-annotations`, `vibetags`,
`vibetags-bom` and `load-tests` inherit every version; a literal there is one the next release will
miss. The Gradle builds and the standalone example poms cannot inherit, so they hold literals —
`BuildVersionParityTest` fails the build when any of them disagrees with the parent. To bump:
`scripts/set-version.sh <version>`, then that test.

## Build Commands

Run from the relevant subproject's own directory — the processor uses the JVM working directory for output unless `vibetags.root` is set.

```bash
# Build and install the library, in order
cd vibetags-annotations && mvn install          # or: gradle clean build publishToMavenLocal
cd ../vibetags         && mvn clean install     # or: gradle clean build publishToMavenLocal
cd ../vibetags-bom     && mvn install           # Maven only

# Tests (from vibetags/)
mvn test                                   # fast tier: 101 classes, 1289 tests. Skips @Tag("e2e").
mvn test -Pe2e                             # everything: 156 classes, 1888 tests. What CI runs.
mvn test -Dtest=AnnotationProcessorEndToEndTest   # -Dtest overrides the tag filter
mvn test -Dtest=AIGuardrailProcessorUnitTest#methodName

# Example (triggers annotation processing; library must be installed first)
cd example && mvn clean compile            # or: gradle clean build

# Dogfood: regenerate this repo's own guardrail files
cd vibetags && mvn compile -Pself-annotate
```

## Architecture — invariants

The reasoning, the processing flow, the marker rules and the internal class map live in
`docs/LOAD-BEARING.md`. Read it before changing the processor. These are the invariants themselves,
kept here because breaking one of them fails silently:

- **File presence is the opt-in.** The processor regenerates only files that already exist, and
  deleting one deactivates that platform permanently. Never "helpfully" create an output file.
  One documented exception: activating `codex` also writes the Codex sidecar (`.codex/config.toml`,
  `.codex/rules/vibetags.rules`), creating `.codex/` if absent.
- **`process()` returns `false`** so other processors still see the annotations; all writing happens
  on `processingOver()`.
- **Hand-authored content outside the markers must never be lost.** Generated content is written
  strictly between `VIBETAGS-START` / `VIBETAGS-END` (HTML or hash form per file type); JSON and TOML
  configs are whole-file overwrites.
- **`AGENTS.md` is a write target only when it is the sole AI config file present,** or when it
  already carries a marker pair. Otherwise `codex` is dropped, and so is the Codex sidecar config.
- **All 44 `@AI*` annotations are `RetentionPolicy.SOURCE`.** They must not leak into runtime.
- **When a platform has both an aggregate and a granular directory opted in, the aggregate collapses
  to a scoped-rules index:** only the safety buckets (`@AILocked`, `@AICore`, `@AIPrivacy`,
  `@AIIgnore`, `@AIAudit`, `@AISecure`) stay inline. Gating is `GranularIndexSection.governingGranularKey`.
- **The rendering layer must stay compiler-free.** `processor/internal/` talks to javac;
  `processor/model/` is plain data (`GuardrailModel`, `TaggedElement`); `processor/internal/content/`
  renders and must never import `javax.lang.model`, `javax.annotation.processing` or
  `com.sun.source`. An `Element` is only valid while its round is live, and the parallel write phase
  runs after the last one closes — `AnnotationCollector.model()` snapshots once, which is what makes
  reading it afterwards safe. `ArchitectureRulesTest` enforces the direction.
- **Adding a validation check** is a line in `ValidationRules.PAIRS` or an entry in `CoreRules` /
  `ModernJavaRules` under `processor/internal/validation/` — never a new loop in
  `AnnotationValidator`, which is now a thin entry point. A rule declares the annotation it
  `scans()`, and the registry runs one round query per annotation type however many rules share it.
- **Adding a platform** touches `Platform` + `PlatformRendererRegistry` + a renderer; **adding an
  annotation** touches `GuardrailAnnotations.ALL` + a formatter + `FormatterRegistry`. Use the
  `add-platform` / `add-annotation` skills rather than improvising.
- **A renderer that emits a YAML document must declare `PlatformRenderer.mergeShape()`.** The
  multi-module merge otherwise stacks whole renderings and repeats the document's top-level key once
  per module — invalid to a strict parser, silently truncated to the last module by a lenient one.
  `YamlMergeShapeContractTest` fails a missing or drifted declaration.
- **A renderer whose marker-free file varies with the annotations must declare
  `PlatformRenderer.wholeFileMerge()`.** A file with no markers is a whole-file overwrite, so
  without one it publishes whichever module compiled last. `MultiModuleWholeFileMergeTest` derives
  the rule — it renders each such service empty and populated, and fails any that differ without a
  merge. Note the coupling that made this invisible: sidecar bodies are also what
  `anyContributed` reads, so a service missing from the sidecar never refreshes at all.
- **A dependency manifest must live under a valid Java package path, never `META-INF/`.** javac's
  `CLASS_PATH` location skips archive directories whose names are not valid package identifiers, so
  a resource under `META-INF/` is unreadable from an annotation processor — `Filer.getResource`
  throws and javac's own file manager lists zero entries. `vibetags/manifests/<package>.json` works;
  the conventional location does not. `TransitiveGuardrailLifecycleE2ETest` pins both directions.
- **Anything that becomes generated content must reach `BuildFingerprint`.** Guardrails inherited
  from dependencies are an input the annotation set cannot speak for: a dependency upgrade changes
  the correct output while every local annotation is byte-identical. Left out of the fingerprint,
  `generateFiles()` short-circuits and the files silently stop tracking reality.
  `TransitiveFingerprintTest` asserts it directly — the end-to-end path passes either way, because
  it also rewrites the sidecar whose mtime feeds the other half of the same check.
- **A granular rule file can have more than one author, so its content goes in the sidecar too.**
  A role in a reactor-root `.vibetags-roles` routes on the package, not the module, so one file is
  written by every module it matches — and each write replaced the last (issue #365). Anything that
  renders a granular file must record its share via `GranularRulesWriter.contributionsFor` and write
  through `ModuleSidecar.mergeGranular`; the stem alone only answers "may this be deleted?".
  `MultiModuleGranularRoleMergeTest` fails a path that writes without merging.

This repo dogfoods the index: the block at the bottom of this file is a scoped-rules index, and the
per-element detail lives in `.claude/rules/`, loaded on demand by glob.
## Reference docs (read on demand)

- `docs/MULTI-MODULE.md` — reactors: sidecar merge, per-module output, `.vibetags-root-index`, `.vibetags-roles`, `.vibetags-mirror`, granular file layout.
- `docs/PROCESSOR.md` — processor options, write cache + fingerprint short-circuit, check mode, `.vibetags-locks`, SPI/Gradle incremental.
- `docs/ANNOTATIONS.md` — adding or changing an annotation: full table, semantics, validation warnings.
- `docs/PLATFORMS.md` — adding a platform, or a question about a specific output file.
- `docs/TESTS.md` — which test class covers what.
- `docs/DEPENDENCIES.md` — every third-party artifact, why it is here, what ships to consumers and what only runs the build; where versions are declared and which newer versions were rejected.
- `docs/LOAD-BEARING.md` — processing flow, file-existence opt-in, marker rules, the scoped-rules
  index, and the internal class map. The reasoning behind the invariants above.
- `docs/ARCHITECTURE.md` — deep dive: system diagram, data flow, design decisions, limitations.
- `USAGE.md` — consumer-facing usage (how to add VibeTags to a project).
- `README.md` — the test-enforced project facts (44 annotations, 37 platforms, 49 config files; pinned by `ProjectFactsConsistencyTest`), the platform table, install snippets.
- `docs/WORKFLOW.md` — what CI actually runs, step by step, and why each verification exists.
- `docs/RELEASING.md` — the release process.
- `docs/CHANGELOG.md` — what each release changed and why.
- `docs/vibetags-in-practice.md` — survey of annotation/platform usage across five real consumer codebases (2026-07-16).

## Pre-commit Hooks

The repo uses `pre-commit` with Checkstyle, gitleaks (secret scanning), end-of-file fixer, and trailing-whitespace fixer. Run `pre-commit run --all-files` before committing.

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

## Logging

VibeTags runs inside javac, so its output is somebody else's build log. Two audiences, two
budgets.

- **`messager` (NOTE/WARNING) is the user-facing channel.** It goes to the compiler output every
  consumer sees. Add a line there only when a developer must act or would ask why a file changed.
- **`log` (SLF4J to `vibetags.log`) is the diagnostic channel.** `INFO` stays scarce: version,
  root, per-service status, the outcome of a run. `DEBUG` is where the narrative lives and it is
  free to be generous, because it is off unless `-Avibetags.log.level=DEBUG` asks for it.

Write events, not positions:

- `domain.event key=value key=value`, one event per line, lower-case dotted names
  (`write.skip`, `write.commit`, `round.write`). A grep for `write.skip` should answer
  "why was nothing written?" without a debugger.
- Log the branch taken and the values that decided it: `write.skip file=CLAUDE.md
  reason=cache-unchanged bytes=2481`, never `entering writeFileIfChanged`.
- `reason=` is mandatory on any `.skip` event. A skip with no reason is the log line people
  actually need and the one that is always missing.
- Guard with `log.isDebugEnabled()` in hot paths (the writer and the cache run per file, per
  build) so a disabled level formats nothing.
- `ERROR` means the build is affected. Generation failures that are downgraded to a warning are
  `WARN` at most.
- **A log event asserted in a test is a contract.** `GuardrailFileWriterLogContractTest` pins the
  skip reasons; renaming one of those events is a breaking change, not a cleanup.
- When you fix a bug, add the DEBUG line that would have made it obvious in one read, and keep it.

Rationale and the longer argument: *Vibe Architecture*, Chapter 6b, "The Log Is a Feedback Loop".
