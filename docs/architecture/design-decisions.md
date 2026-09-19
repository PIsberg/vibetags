# Architecture: Design Decisions

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## Design Decisions

### 1. SOURCE Retention

**Decision:** All annotations use `RetentionPolicy.SOURCE`

**Rationale:**
- Zero runtime overhead — annotations stripped during compilation
- No dependency pollution in production artifacts
- Processor only needed at compile-time
- Consumer projects have no runtime dependency on VibeTags

### 2. Single Processor, Multiple Outputs

**Decision:** One processor generates all 17+ output files in a single pass

**Rationale:**
- Single source of truth for annotation data
- Consistent content across all platforms
- No duplication of parsing logic
- Atomic generation (all or nothing)

**Internal split (since 0.6.0):** the single SPI entry point (`AIGuardrailProcessor`) is now a thin orchestrator. The actual work is divided across eight focused helpers in `internal/`: a collector for accumulation, a validator and an orphan warner for compile-time warnings, a registry for service↔file mapping and the file-existence opt-in, a builder for per-platform string assembly, and two writers (one general, one granular) for atomic file I/O. This keeps each class testable in isolation while preserving the "one processor, single pass" property externally.

### 3. File-existence Opt-in Model

**Decision:** The annotation processor uses the presence of specific files on disk to determine which AI services are active.

**Implementation:**
```java
static Set<String> resolveActiveServices(Messager messager, Map<String, Path> allServiceFiles) {
    Set<String> optInKeys = Set.of(
        "cursor", "claude", "aiexclude", "codex", "gemini", "copilot", "qwen",
        "cursor_ignore", "claude_ignore", "copilot_ignore", "qwen_ignore",
        "llms", "llms_full"
    );

    return allServiceFiles.entrySet().stream()
        .filter(e -> optInKeys.contains(e.getKey()))
        .filter(e -> Files.exists(e.getValue()))
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
}
```

**Rationale:**
- **Manual Control**: Developers decide which AI tools they support
- **No Clutter**: VibeTags never creates files for unused AI tools
- **Zero Configuration**: No complex config needed — `touch` or `rm` is sufficient

### 4. Write-if-Changed Logic

**Decision:** Only write files when content actually differs.

**Implementation** (current, after 0.7.1 layered fast paths):
```java
boolean writeFileIfChanged(String path, String content, boolean hasNewRules) {
    Path file = Paths.get(path);

    // Fast path 1: WriteCache hit — size + mtime + 32-bit fingerprint match
    if (writeCache != null && writeCache.isUnchanged(file, content)) {
        return false;
    }

    // Fast path 2 (non-marker files): streaming byte-compare with early exit
    if (!supportsMarkers && fileExists && existingSize == contentByteLen) {
        if (fileBytesEqual(file, contentBytes)) {
            writeCache.recordWrite(file, content);
            return false;
        }
        // sizes match but bytes differ → write directly, no readString needed
    }

    // Slow path: Files.readString + strip-tolerant equals (marker files,
    // or non-marker files where size differs by ≤64 bytes)
    String existing = Files.readString(file, UTF_8);
    if (existing.strip().equals(finalContent.strip())) return false;

    writeContentWithBackup(file, finalContent); // tmp + atomic-move
    writeCache.recordWrite(file, content);
    return true;
}
```

**Rationale:**
- Prevents unnecessary file system writes
- Avoids triggering file watchers
- Preserves file modification timestamps
- Git-friendly (no false-positive changes)
- Three-layer fast path means warm-cache no-change rebuilds skip nearly all I/O

### 5. Write Cache (since 0.7.1)

**Decision:** Maintain a per-output-file content cache in `.vibetags-cache` at the project root, looked up before any read or write inside `writeFileIfChanged`.

**What it stores** — one tab-separated row per generated file:
```
<absolute-path>\t<8-char-fingerprint>\t<size-bytes>\t<mtime-millis>
```

**The fingerprint is `String.hashCode()`**, not SHA-256 or CRC32C. Why:
- 32-bit collision space matches CRC32; for two non-adversarial VibeTags bodies the collision probability is 2⁻³² ≈ 1 in 4 billion. Size and mtime are checked first as independent guards, so a hash collision can only cause us to skip writing identical content — never silently corrupt output.
- Cached internally on the `String` after first computation → O(1) on subsequent lookups for the same reference.
- HotSpot intrinsifies `String.hashCode()` on x86 with vectorised instructions for the first computation.
- Crucially: **no UTF-8 byte array materialisation per call.** An earlier CRC32C-of-bytes design allocated a fresh `byte[s.length()]` per cache lookup — for a 1 MB body that's 1 MB of garbage per hit, defeating the cache's allocation-saving purpose.

**Lookup** (`WriteCache.isUnchanged`) — single `Files.readAttributes(BasicFileAttributes.class)` for size + mtime, then fingerprint compare. ~10 µs per call on warm-cache local SSD; constant time regardless of body size.

**Persistence** — loaded lazily on first lookup, written atomically once at the end of `generateFiles()` via tmp+move. Safe to delete (rebuilt on the next compile); gitignored.

**Invalidation** — `mtime` change (user edited the file), `size` change, file deletion, or fingerprint mismatch all bypass the cache and fall through to the read-and-compare path. The granular-rules orphan cleanup explicitly invalidates the cache for any file it deletes or rewrites outside the marker block.

**Measured impact** (`WriteCacheHitBenchmark` in `load-tests/`, JMH AverageTime + GC profiler, 100-call batches):

| Body | File type | cache hit | no cache | wall-clock | allocation |
|---|---|---:|---:|---:|---:|
| 1 KB | `.md` | 16.4 µs | 208.5 µs | **13×** | **15×** |
| 12 KB | `.md` | 18.1 µs | 262.7 µs | **15×** | **135×** |
| 1 MB | `.md` | 18.6 µs | 3 405 µs | **183×** | **11 159×** |

Cache-hit cost is bounded by the single stat syscall — flat curves regardless of body size. The no-cache path scales linearly with body size because it must `readString` the entire file.

### 6. Streaming Byte-Compare for Non-Marker Files (since 0.7.1)

**Decision:** When a non-marker output file exists at exactly the new content's byte length, stream-compare bytes with early exit on first mismatch instead of materialising the full file as a `String` for `.equals()`.

**Where it applies** — `.cursorignore`, `.aiderignore`, `.aiexclude`, ignore-style files, `.json`/`.toml` configs. Marker files (`.md`, `.mdc`, `llms*.txt`) keep the `readString` path because they need the full string for marker-position parsing and front-matter handling.

**Implementation** — `GuardrailFileWriter.fileBytesEqual(Path, byte[])` reads through an 8 KB buffered `InputStream` and compares byte-by-byte against the expected array. Caller has already verified `Files.size(file) == expected.length` — early return on size mismatch is the existing `nonMarkerSizeMismatch` check.

**Rationale:**
- Avoids a multi-MB `String` allocation when the file matches.
- Finds mismatches in the first kilobyte without reading the rest of the file.
- Strip-tolerant `readString` path is still used for ≤64-byte size differences (handles trailing-whitespace drift).

### 7. Pre-sized Per-Platform StringBuilders (since 0.7.1)

**Decision:** `GuardrailContentBuilder` pre-allocates the nine main per-platform buffers based on the collected element count instead of relying on `StringBuilder`'s default 16-char capacity.

**Implementation** — `mainBuilderHint()` returns `clamp(4096, 1500 × elementCount, 256·1024)`:
- Floor of 4 KB so empty/small projects don't waste cycles on grows.
- ~1500 chars per annotated element across all sections (Locked/Context/Audit/Draft/Privacy/Core/Performance/Contract/Ignore).
- Cap of 256 KB so a hypothetical 10 000-element codebase doesn't pre-allocate megabytes per platform across the ~12 active platforms.

**Affected buffers:** `cursorRules`, `claudeMd`, `codexAgents`, `copilot`, `qwenMd`, `windsurfRules`, `zedRules`, `llmsTxt` (sized larger because it aggregates), `llmsFullTxt` (same).

**Rationale:**
- Eliminates the log₂(N) `char[]` grow-and-copy passes that the eight per-annotation `appendXxx()` loops previously triggered as content accumulated.
- Output is byte-identical to prior versions — verified by all 75 end-to-end snapshot tests on every release commit.

### 6. Wildcard Annotation Matching

**Decision:** `@SupportedAnnotationTypes("*")`

**Rationale:**
- Automatically picks up new annotations without code changes
- Single processor handles all VibeTags annotations
- Easy to extend with new annotation types

### 7. Version Stamping

**Decision:** Every generated file includes version header:
```
# Generated by VibeTags v1.0.0-SNAPSHOT | https://github.com/PIsberg/vibetags
```

**Rationale:**
- **Traceability**: Identifies processor version
- **Debugging**: Simplifies troubleshooting
- **Attribution**: Links back to source repository

### 8. Smart Validation Layer

**Decision:** Processor performs lightweight validation and emits compiler WARNINGs

**Supported Checks:**
- `@AIDraft + @AILocked`: Warns about contradictory annotations
- Empty `@AIAudit`: Warns if no checkFor items
- `@AIPrivacy + @AIIgnore`: Warns that `@AIPrivacy` is redundant — `@AIIgnore` already hides the element from AI
- `@AIContract + @AIDraft`: Warns that the combination is contradictory — a frozen signature cannot also need drafting
- `@AIContract + @AILocked`: Warns that the combination has overlapping intent — `@AILocked` already prohibits all modifications
- Orphaned annotations: Warns if recommended files missing

**Example:**
```
[WARNING] VibeTags: @AIIgnore used but .qwenignore is missing for Qwen support. Consider creating it.
[WARNING] VibeTags: myField is annotated with both @AIPrivacy and @AIIgnore. @AIIgnore already excludes the element from AI context; @AIPrivacy is redundant.
```
