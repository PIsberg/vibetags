# OSS-Fuzz Integration (template — currently unused)

This directory contains the artefacts that **[OSS-Fuzz](https://github.com/google/oss-fuzz)** — Google's continuous fuzzing service for open-source projects — would need to build and run a [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzzer against `AIGuardrailProcessor`.

> **Status (2026-05): not wired up.** The files were added in commit
> [`b3a3edf`](https://github.com/PIsberg/vibetags/commit/b3a3edf) (2026-04-17, "feat(security): integrate OSS-Fuzz") as a starter template, but they were never submitted upstream to OSS-Fuzz, no CI workflow runs them, and the harness has fallen out of sync with the live processor API. See [Current state](#current-state--known-issues) below.

## What OSS-Fuzz is

OSS-Fuzz runs continuous coverage-guided fuzzing on accepted open-source projects, reports crashes / hangs / sanitizer findings privately to maintainers, and publishes them after a 90-day disclosure window. To opt in, a project submits a PR to the [`google/oss-fuzz`](https://github.com/google/oss-fuzz) repo containing a `projects/<name>/` directory with the same four files this directory holds.

## What's here

| File | Purpose |
|---|---|
| `project.yaml` | OSS-Fuzz metadata: `language: jvm`, `fuzzing_engines: [libfuzzer]`, `sanitizers: [address]`, primary contact email. |
| `Dockerfile` | Builds on `gcr.io/oss-fuzz-base/base-builder-jvm`, installs Maven, copies the repo into `$SRC/vibetags`, and stages the build script + fuzzer for OSS-Fuzz to compile. |
| `build.sh` | Compiles the processor jar via Maven, then `javac`s every `*Fuzzer.java` against the Jazzer API, copies the resulting `.class` files to `$OUT`, and generates per-fuzzer launcher shell scripts. |
| `VibeTagsFuzzer.java` | Jazzer harness. Targets the file-content merger inside `AIGuardrailProcessor.writeFileIfChanged` — the most complex string-parsing boundary (YAML front-matter, marker block detection, content merge). |

## What the harness exercises

`VibeTagsFuzzer.fuzzerTestOneInput` takes a single `FuzzedDataProvider` and constructs three random inputs:

1. `fakeExistingContent` — up to 500 bytes; written to a temp file before the call. Simulates whatever might already be on disk: empty, valid VibeTags markers, malformed markers, legacy pre-marker headers, random binary, partial YAML front-matter, etc.
2. `newContentPayload` — the rest of the fuzz input; passed to `writeFileIfChanged` as the new body to merge in.
3. A random file extension drawn from `{".md", ".mdc", ".json", ""}`. This fans the harness across the three marker regimes the writer supports: HTML markers (`<!-- VIBETAGS-START -->` for `.md`/`.mdc`), no markers (full overwrite for `.json`/`.toml`), and hash markers (`# VIBETAGS-START` for everything else).

The point isn't to find functional bugs — `AnnotationProcessorEndToEndTest` and `WriteFileFrontMatterTest` cover those — but to discover **memory-safety**, **infinite-loop**, and **uncaught-exception** classes of bugs in the marker-parsing and content-merging code paths. A coverage-guided fuzzer can hit string boundaries (UTF-8 boundaries, marker-prefix-but-no-suffix, nested marker patterns, etc.) that hand-written tests don't.

## Current state — known issues

Every item below would need fixing before the `gcr.io/oss-fuzz-base/base-builder-jvm` build succeeds, let alone before submitting to OSS-Fuzz upstream:

| # | Issue | Fix |
|---|---|---|
| 1 | `Dockerfile` line 9: `COPY oss-fuzz/se/deversity/vibetags/fuzz/VibeTagsFuzzer.java $SRC/` references a path that doesn't exist. The fuzzer file is directly in `oss-fuzz/`, not nested under `se/deversity/vibetags/fuzz/`. | Either move the fuzzer into the package path (and add a `package se.deversity.vibetags.fuzz;` declaration) or change the COPY to `oss-fuzz/VibeTagsFuzzer.java`. |
| 2 | `VibeTagsFuzzer.java` line 37: calls `processor.writeFileIfChanged(tempFile.toString(), newContentPayload)` — 2 args. The live API has been 3-arg since pre-0.7.0 (`boolean hasNewRules` was added). The harness won't compile against the current jar. | Add a third argument — `data.consumeBoolean()` is the most fuzzer-friendly choice. |
| 3 | `VibeTagsFuzzer.java` line 41: `Files.deleteIfExists(Path.of(tempFile.toString() + ".bak"))`. The writer hasn't created `.bak` files since 0.5.6 — the sidecar is now `.vibetags-tmp`. The line is a no-op (no harm, but stale). | Update the suffix, and also clean up `.vibetags-cache` if `WriteCache` writes a sidecar (it does in normal use, though it shouldn't here because no `WriteCache` is wired into the fuzzer's processor instance). |
| 4 | `build.sh` lines 4 & 8: `cd vibetags && cd vibetags`. The first `cd` enters the repo root that OSS-Fuzz unpacks into `$SRC/vibetags`; the second `cd` enters the `vibetags/` subproject. Looks like a copy-paste artefact but happens to be correct given the OSS-Fuzz layout. | Add a comment explaining the two-step `cd` so it's not mistaken for a typo. |
| 5 | The harness only fuzzes `writeFileIfChanged`. Equally interesting attack surfaces — `stripLegacyVibeTagsBlock`, `cleanupGranularDirectory` against malicious file paths, the marker-position parsing in `writeWithMarkers` — are not covered. | Add additional `*Fuzzer.java` files; `build.sh` already loops over every `*Fuzzer.java` it finds. |
| 6 | No CI workflow runs the fuzzer or even verifies it still compiles against the live processor jar. | Either add a "smoke compile" step to `build.yml` (5-minute Jazzer build on a single fuzz case) or accept that the harness will rot until upstream submission. |

## Running locally (without OSS-Fuzz)

You don't need OSS-Fuzz infrastructure to run a Jazzer fuzzer. Once issues #1 and #2 above are fixed:

```bash
# 1. Install the processor locally
cd vibetags-annotations && mvn install -DskipTests
cd ../vibetags             && mvn install -DskipTests

# 2. Get Jazzer (https://github.com/CodeIntelligenceTesting/jazzer/releases)
JAZZER=~/jazzer-linux-x86_64

# 3. Compile the fuzzer
PROCESSOR_JAR=$(find ~/.m2/repository/se/deversity/vibetags/vibetags-processor -name "*.jar" | head -1)
javac -cp $PROCESSOR_JAR:$JAZZER/jazzer_api_deploy.jar oss-fuzz/VibeTagsFuzzer.java

# 4. Run a few thousand iterations
$JAZZER/jazzer --cp=oss-fuzz:$PROCESSOR_JAR --target_class=VibeTagsFuzzer -runs=10000
```

## Submitting to OSS-Fuzz upstream

If/when this is revived, the path is:

1. Fix all six items above.
2. Open a PR to [`google/oss-fuzz`](https://github.com/google/oss-fuzz) creating `projects/vibetags/` with these four files (`project.yaml`, `Dockerfile`, `build.sh`, plus the fuzzer source).
3. Once accepted, OSS-Fuzz reports findings to the contact in `project.yaml` (currently `pontus.isberg@deversity.se`) and creates issues on its own tracker that auto-disclose after 90 days.

## Decision time

This directory is currently neither documented in the main `README.md` nor wired to CI nor submitted to OSS-Fuzz — it sits as a half-finished template. Three reasonable paths forward:

- **Revive** — fix items 1-2 (compile-correctness blockers), drop the rotted `.bak` cleanup, add a one-line CI smoke-compile, then submit upstream.
- **Park** — leave the files; this README at least makes the intent legible to anyone who finds them.
- **Remove** — `git rm -rf oss-fuzz/` if nobody plans to revisit. Lower maintenance surface.

For now this README parks the directory and documents it. Choose **Revive** or **Remove** when there's appetite to commit one way or the other.
