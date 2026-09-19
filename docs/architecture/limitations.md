# Architecture: Limitations and Future Architecture

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## Limitations

### 1. Output Location Defaults to JVM Working Directory

**Default:** Uses `Paths.get("")` which resolves to the JVM working directory

**Impact of default:**
- Can write to wrong directory in IDE builds that don't set cwd to project root
- Breaks if build is invoked from a subdirectory

**Resolution:** Pass `-Avibetags.root=<path>` via `<compilerArg>` in Maven or `annotationProcessorArgs` in Gradle to override the output directory explicitly. Most IDE integrations need this set.

### 2. No Gradle Incremental-Annotation-Processing Registration

**Problem:** Not registered as `META-INF/gradle/incremental.annotation.processors`. Gradle therefore treats VibeTags as a non-incremental processor and recompiles every annotated source on each round.

**What's already mitigated:** The `WriteCache` (since 0.7.1) avoids the file-write side of the cost — when no annotations changed, generated files are byte-stable and no I/O happens. See [Design Decision 5](design-decisions.md#5-write-cache-since-071). The remaining gap is purely on the `javac`/Gradle side: input-source recompilation isn't yet skipped.

**Why we haven't registered:**
- VibeTags is structurally an aggregating processor (it needs to see the full picture across all rounds to compute orphan cleanup and shared platform files like `llms.txt`). Aggregating processors are supported by Gradle but the registration changes the contract: every modified source triggers a full processor rerun.
- Combined with the cache, the practical wall-clock win over the current behaviour is small.

**Workaround for now:** in Gradle, `gradle compileJava --no-daemon -PskipVibeTags=true` can be approximated by compiling without the annotation-processor path; the cache then preserves the existing files on the next regular build.

### 3. Hardcoded Output Formats

**Problem:** Each platform's format is hardcoded in the processor

**Impact:**
- Cannot customize template structure
- Adding new platforms requires code changes
- No user control over formatting

### 4. Limited Validation Logic

**Problem:** Basic validation only (contradictions, empty arrays)

**Impact:**
- Complex contradictory logic might slip through
- No enforcement of cross-file consistency beyond basic checks

---

## Future Architecture

See [archive/CONCEPT_PLUGIN.md](../archive/CONCEPT_PLUGIN.md) for the proposed migration to a
plugin/CLI architecture. It is archived rather than live: `vibetags-cli` shipped in 1.1.0 with
two commands that need no core extraction, so the sketch below records a shape, not a
commitment.

### Proposed Components

```
vibetags-core/          # Shared scanning + generation logic
vibetags-cli/           # Standalone CLI (any language support)
vibetags-maven-plugin/  # Maven plugin with configurable output paths
vibetags-gradle-plugin/ # Gradle plugin with task configuration avoidance
vibetags-processor/     # Legacy wrapper (deprecated)
```

### Key Improvements

- **Configurable output paths** via `vibetags.yaml`
- **Language-agnostic** support for comment-based annotations
- **Incremental build support** with file change detection
- **Customizable templates** for output formats
- **Enhanced validation** for annotation misuse
