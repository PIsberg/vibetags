# OSS-Fuzz Integration

This directory contains the artefacts that **[OSS-Fuzz](https://github.com/google/oss-fuzz)** — Google's continuous fuzzing service for open-source projects — needs to build and run a [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzzer against `AIGuardrailProcessor`.

> **Status (2026-05): compile-ready, not yet submitted upstream.** Files were added in commit
> [`b3a3edf`](https://github.com/PIsberg/vibetags/commit/b3a3edf) (2026-04-17) as a starter template, sat unused for a few weeks, and were brought back into sync with the v0.7.1 processor API in this PR. CI does not currently run them, and the project has not been submitted to upstream OSS-Fuzz — see [What's left](#whats-left) below.

## What OSS-Fuzz is

OSS-Fuzz runs continuous coverage-guided fuzzing on accepted open-source projects, reports crashes / hangs / sanitizer findings privately to maintainers, and publishes them after a 90-day disclosure window. To opt in, a project submits a PR to the [`google/oss-fuzz`](https://github.com/google/oss-fuzz) repo containing a `projects/<name>/` directory with the same four files this directory holds.

## What's here

| File | Purpose |
|---|---|
| `project.yaml` | OSS-Fuzz metadata: `language: jvm`, `fuzzing_engines: [libfuzzer]`, `sanitizers: [address]`, primary contact email. |
| `Dockerfile` | Builds on `gcr.io/oss-fuzz-base/base-builder-jvm`, installs Maven, copies the repo into `$SRC/vibetags`, and stages the build script + fuzzer for OSS-Fuzz to compile. |
| `build.sh` | Compiles the processor jar via Maven, then `javac`s every `*Fuzzer.java` against the Jazzer API, copies the resulting `.class` files to `$OUT`, and generates per-fuzzer launcher shell scripts. |
| `VibeTagsFuzzer.java` | Jazzer harness. Targets the file-content merger inside `AIGuardrailProcessor.writeFileIfChanged` — the most complex string-parsing boundary in the processor (YAML front-matter detection, marker block parsing, content merge, legacy-block stripping). |

## What the harness exercises

`VibeTagsFuzzer.fuzzerTestOneInput` takes a single `FuzzedDataProvider` and constructs four random inputs:

1. `fakeExistingContent` — up to 500 bytes; written to a temp file before the call. Simulates whatever might already be on disk: empty, valid VibeTags markers, malformed markers, legacy pre-marker headers, random binary, partial YAML front-matter, etc.
2. `hasNewRules` — boolean, controls the multi-module preservation guard. Both branches need coverage; the fuzzer flips a coin per case.
3. `newContentPayload` — the rest of the fuzz input; passed to `writeFileIfChanged` as the new body to merge in.
4. A random file extension drawn from `{".md", ".mdc", ".json", ""}`. This fans the harness across the three marker regimes the writer supports:
   - `.md` / `.mdc` → HTML markers (`<!-- VIBETAGS-START -->`)
   - `.json` → no markers (full overwrite, plus the 0.7.1 streaming byte-compare fast path on size match)
   - `""` (e.g. rules) → hash markers (`# VIBETAGS-START`)

The point isn't to find functional bugs — `AnnotationProcessorEndToEndTest` and `WriteFileFrontMatterTest` cover those — but to discover **memory-safety**, **infinite-loop**, and **uncaught-exception** classes of bugs in the marker-parsing and content-merging code paths. A coverage-guided fuzzer can hit string boundaries (UTF-8 boundaries, marker-prefix-but-no-suffix, nested marker patterns, etc.) that hand-written tests don't.

## Running locally (without OSS-Fuzz)

You don't need OSS-Fuzz infrastructure to run a Jazzer fuzzer:

```bash
# 1. Install the processor locally
cd vibetags-annotations && mvn install -DskipTests
cd ../vibetags             && mvn install -DskipTests

# 2. Grab a Jazzer release (https://github.com/CodeIntelligenceTesting/jazzer/releases)
JAZZER=~/jazzer-linux-x86_64

# 3. Compile the fuzzer
PROCESSOR_JAR=$(find ~/.m2/repository/se/deversity/vibetags/vibetags-processor -name "*.jar" | head -1)
javac -cp $PROCESSOR_JAR:$JAZZER/jazzer_api_deploy.jar oss-fuzz/VibeTagsFuzzer.java -d /tmp/fuzz

# 4. Run a few thousand iterations
$JAZZER/jazzer --cp=/tmp/fuzz:$PROCESSOR_JAR --target_class=VibeTagsFuzzer -runs=10000
```

A short smoke run (`-runs=10000`) takes about 30 seconds on a quiescent laptop and is enough to verify the harness is wired up correctly. Submit-grade campaigns are open-ended — OSS-Fuzz runs them indefinitely.

## Submitting to OSS-Fuzz upstream

When ready, the path is:

1. Open a PR to [`google/oss-fuzz`](https://github.com/google/oss-fuzz) creating `projects/vibetags/` with these four files (`project.yaml`, `Dockerfile`, `build.sh`, plus `VibeTagsFuzzer.java`).
2. Once accepted, OSS-Fuzz reports findings to the contact in `project.yaml` (currently `pontus.isberg@deversity.se`) and creates issues on its own tracker that auto-disclose after 90 days.

## What's left

Two follow-ups still open, both genuinely optional:

1. **Broaden the harness.** `VibeTagsFuzzer` only exercises `writeFileIfChanged`. Equally interesting attack surfaces — `stripLegacyVibeTagsBlock` against malformed XML, `cleanupGranularDirectory` against directory-traversal-like paths, the marker-position parsing in `writeWithMarkers` — are not covered. Adding `*Fuzzer.java` siblings is enough; `build.sh` already loops over every `*Fuzzer.java` it finds and emits a launcher.
2. **CI smoke-compile.** Nothing currently verifies the harness still compiles against the live processor jar — a future API change can rot it again. A 30-second job in `build.yml` that runs the local recipe with `-runs=1` would catch this.

Neither blocks an upstream submission; both reduce the chance of the harness silently rotting again.
