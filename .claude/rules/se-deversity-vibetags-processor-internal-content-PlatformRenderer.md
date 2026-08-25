---
paths: ["**/PlatformRenderer.java"]
---

<!-- VIBETAGS-START -->
# Rules for PlatformRenderer

## Load-Bearing Oddity
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.
- **Invariant**: A renderer whose output is YAML declares mergeShape(); a renderer whose marker-free output varies per module declares wholeFileMerge(). The defaults return null, which means plain concatenation.
- **Breaks if changed**: Silent data loss across a reactor. Concatenated YAML repeats a top-level key, so the parse either fails or keeps only the last module; a marker-free file is a whole-file overwrite, so it ends up holding one module's view of the whole project. Neither shows up in a single-module build, which is where a new renderer gets tested.
<!-- VIBETAGS-END -->
