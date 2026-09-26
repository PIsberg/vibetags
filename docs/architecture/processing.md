# Architecture: Build Sequence and Data Flow

Part of the [architecture deep dive](../ARCHITECTURE.md), which indexes every part.

## Build Sequence

![Build Sequence](../diagrams/build-sequence.png)

*Figure 3: Sequence diagram of annotation processing during compilation*

This one is hand-drawn, and stays that way: its participants include a developer and a build
system, which no parser can see. The same story from inside the processor —
[`sequence/aiguardrailprocessor-sequence-diagram.svg`](../diagrams/codekarta/sequence/aiguardrailprocessor-sequence-diagram.svg),
[parsed from `AIGuardrailProcessor.java`](components.md#parsed-diagrams-code-karta) — is a numbered call
order rather than a picture of the flow, and at roughly 9800×7700 pixels it is meant to be
opened and panned rather than read on a page. Reach for it when you need to know *what actually
runs and in which order*, particularly around
`generateFiles()`, whose step order is [load-bearing](../LOAD-BEARING.md#core-processing-flow) and
under `<locked_files>` for that reason.

### Processing Phases

**Phase 1: Element Accumulation (every round)**
```java
collector.collect(roundEnv, presentFqns);   // every @AI* annotation type present this round
validateAnnotations(processingEnv.getMessager(), roundEnv, presentFqns, collector.roundIndex());
return false; // do not claim annotations
```
- Accumulates annotated elements into `LinkedHashSet`s across all rounds
- Validates annotations each round for early compiler feedback
- Returns `false` so other processors still see the annotations

**Phase 1b: Generation Trigger**
```java
if (roundEnv.processingOver() && !processed) {
    generateFiles();
    processed = true;
}
```
- `generateFiles()` runs exactly once, on the final round when `processingOver()` is true
- Idempotency guard (`processed` flag) prevents double-generation

**Phase 2: Validation**
```java
validateAnnotations(processingEnv.getMessager(), roundEnv);
```
- Checks for contradictory annotations (@AIDraft + @AILocked on same element)
- Warns about empty @AIAudit (no checkFor items)
- Emits compiler warnings via `Messager`

**Phase 3: Service Resolution**
```java
Map<String, Path> serviceFiles = buildServiceFileMap(root);
Set<String> activeServices = resolveActiveServices(messager, serviceFiles);
```
- Maps 17+ service keys to file paths
- Checks file existence (file presence = opt-in)
- Only active services get generated

**Phase 4: Content Generation**
- Iterates each annotation type
- Accumulates platform-specific content in StringBuilders
- Formats output per platform conventions (Markdown, XML, TOML, JSON)

**Phase 5: File Writing**
```java
boolean changed = fileWriter.writeFileIfChanged(filePath, content, hasNewRules);
```
Three layered fast paths in front of the actual write, in order of cheapness:
1. **Cache fast path** _(0.7.1)_ — if `WriteCache.isUnchanged(file, body)` is true (size + mtime + 32-bit fingerprint match what we recorded last build), return immediately. No file read, no compare, no write.
2. **Streaming byte-compare fast path** _(0.7.1, non-marker files only)_ — when the on-disk byte length matches the new content's byte length exactly, stream-compare with early exit on first byte mismatch. Avoids materialising the entire file as a `String`.
3. **Read-and-compare path** — `Files.readString` + strip-tolerant `.equals()`, the original logic. Used for marker files (`.md`, `.mdc`, `llms*.txt`) and non-marker files where the size already differs by ≤64 bytes (whitespace tolerance).

After a successful write or a streaming-byte-equal hit, `WriteCache.recordWrite(...)` updates the cache entry. After all platform files are processed, `generateFiles()` calls `writeCache.flush()` once to persist the sidecar atomically.

`Messager` emits NOTE: `"updated"` or `"no changes"` for each file.

**Phase 6: Orphaned Annotation Check**
```java
checkOrphanedAnnotations(messager, activeServices, ...);
```
- Warns if annotations used but recommended files missing
- Example: @AIIgnore used but .qwenignore missing

---

## Data Flow

![Data Flow](../diagrams/data-flow.png)

*Figure 4: Detailed data flow through the annotation processor*

### Annotation Processing Details

**@AILocked Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AILocked.class)) {
    AILocked locked = element.getAnnotation(AILocked.class);
    String className = element.toString();
    String reason = locked.reason();

    // Append to all platforms
    cursorRules.append("* `").append(className).append("` - Reason: ").append(reason).append("\n");
    qwenMd.append("* `").append(className).append("` — ").append(reason).append("\n");
    // ... other platforms
}
```

**@AIContext Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AIContext.class)) {
    AIContext context = element.getAnnotation(AIContext.class);
    String className = element.toString();

    // Platform-specific formatting
    cursorRules.append("* `").append(className).append("`\n")
               .append("  * Focus: ").append(context.focus())
               .append("\n  * Avoid: ").append(context.avoids()).append("\n");
}
```

**@AIIgnore Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AIIgnore.class)) {
    String className = element.toString();

    // Write to ignore sections
    qwenIgnore.append("* `").append(className).append("`\n");

    // Write glob patterns to standalone ignore files
    String globPattern = "**/"+ element.getSimpleName() + ".java\n";
    qwenIgnoreFile.append(globPattern);
}
```

**@AIAudit Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AIAudit.class)) {
    AIAudit audit = element.getAnnotation(AIAudit.class);
    String className = element.toString();
    String[] checkFor = audit.checkFor();

    // Platform-specific audit format
    qwenAudit.append("* `").append(className).append("`\n");
    qwenAudit.append("  - Required Checks: ").append(String.join(", ", checkFor)).append("\n");
}
```

**@AIPrivacy Processing:**
```java
for (Element element : roundEnv.getElementsAnnotatedWith(AIPrivacy.class)) {
    AIPrivacy privacy = element.getAnnotation(AIPrivacy.class);
    String elementPath = element.toString();
    String reason = privacy.reason();

    // Claude: XML pii_guardrails block
    claudePrivacy.append("    <element path=\"").append(elementPath).append("\">\n");
    claudePrivacy.append("      <reason>").append(reason).append("</reason>\n");
    claudePrivacy.append("    </element>\n");

    // Cursor / Codex / Copilot / Gemini / Qwen: Markdown list
    cursorPrivacy.append("* `").append(elementPath).append("` — ").append(reason).append("\n");
}

// After the loop, if hasPrivacyAnnotations == true, finalize PII sections for all platforms
// Claude gets <pii_guardrails> XML + <rule> about never logging values
// Others get a "## 🔐 PII GUARDRAILS" Markdown section
```
