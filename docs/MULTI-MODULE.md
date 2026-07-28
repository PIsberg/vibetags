# Multi-module reactors

How VibeTags behaves when more than one Maven/Gradle module compiles into the same project root, and
the four opt-ins that shape that output. Split out of `CLAUDE.md` — read this when working on
sidecars, per-module output, roles, or cross-module mirroring.

`example-multimodule/` is a three-module reactor demonstrating the sidecar merge end-to-end (built
and asserted in CI); `example-multimodule-indexed/` demonstrates the lean root index.

## Sidecar aggregation (the default)

Every module contributes to the shared marker files via per-module sidecars
(`.vibetags-mod-<moduleId>` at the VibeTags root): each compile persists its own rendered bodies,
reads all sibling sidecars, and merges them into the shared files with `VIBETAGS-MODULE: <id>`
sub-markers.

Module identity comes from `ModuleRootResolver` — it walks up from the compiled sources to the
nearest `pom.xml`/`build.gradle(.kts)` — **not** from the JVM working directory, which is the reactor
root for every module of an in-process Maven/Gradle build (issue #278: last-writer-wins). Sidecars
are format v2; v1 files carry the broken working-directory identity and are pruned on read.

Two preservation guards keep compiles with **no annotations** (e.g. Maven's test-compile pass) from
destroying content: the module's sidecar is only saved when annotations were found, and shared-file
writes with no contributions preserve the existing file content. Consequence: removing *all*
annotations from a module leaves its last contribution in place until its `.vibetags-mod-*` file is
deleted (or the module directory disappears).

## Per-module (nested) output

The sidecar/merge above produces the **root** files. Independently, a module can opt into a guardrail
file or granular dir **inside its own directory** (`touch module-a/CLAUDE.md`), and
`ModuleOutputWriter` writes that module's own guardrails there — scoped to that module's annotations,
with **no sidecar and no merge**. It simply re-runs the single-module pipeline (`ServiceRegistry` →
`GuardrailContentBuilder` → `GuardrailFileWriter`/`GranularRulesWriter`) against `compilationRoot()`
with the module dir's own file-existence opt-ins, so the scoped-rules index composes per-module too.

Called as a terminal step in `generateFiles()`/`checkFiles()`; **gated on `moduleRoot != null` and
`!compilationRoot.equals(root)`** so in-memory/non-javac compiles (which fall back to the JVM working
dir) never write there. The module's own opt-in set is folded into the `BuildFingerprint` input so a
freshly-touched module file isn't skipped by the short-circuit. The sidecar remains untouched and
serves only the root aggregate.

## Lean indexed root aggregate (`.vibetags-root-index`)

By default the reactor-root aggregate (`CLAUDE.md`, `.cursorrules`, `.windsurfrules`,
`.github/copilot-instructions.md`) embeds a full verbatim copy of every module's guardrails via the
sidecar merge. In a reactor where each module already carries its own scoped rules (`.claude/rules/`
etc.), that root block is a second copy of content the tool auto-loads from the module files (issue
#298). Touching `.vibetags-root-index` at the root opts into a **lean index**: for the four
aggregates that have a granular sibling, the merge replaces each module's embedded body with a short
pointer to that module's own scoped rules (and/or its own aggregate file), still wrapped in the
`VIBETAGS-MODULE` sub-markers. The root module's own body stays inline, and aggregates **without** a
granular sibling (`GEMINI.md`, `AGENTS.md`, `llms.txt`, `.vibetags-locks`, …) keep the full merge.

Losslessness guard: a module is linked only when it actually emits its own per-module output for that
service (its module dir opted into `.claude/rules/` and/or `CLAUDE.md`); a module with no output of
its own keeps its embedded body so nothing is dropped. The decision is computed on the filesystem in
`ModuleSidecar.readAll()` (which has the root) and stashed on the sidecar instances as transient,
never-persisted state — so `ModuleSidecar.mergeFor()` stays disk-free and its `@AIContract` signature
is untouched, and the `@AILocked` `generateFiles()` step order is unchanged. The opt-in registers as
the `root_index` service (`ServiceRegistry`), so its presence folds into the build fingerprint and
toggling it reliably regenerates. Check mode mirrors it automatically (`checkFiles()` calls the same
two methods).

## Role/topic-based granular rules (`.vibetags-roles`)

`RoleConfig.load(root)` reads an optional `.vibetags-roles` (name → globs/FQNs, one role per line;
null when absent). `GranularRulesWriter.writeAll(..., roles)` then partitions the granular owners: an
owner matching a role (first-match, config order — glob matched against the FQN-reconstructed path,
or exact FQN) is grouped into one human-named file `<role>.<ext>` with the role's globs in the
platform frontmatter; owners matching no role keep their per-class file (non-lossy). Loaded at the
root in `generateFiles()`/`checkFiles()` and per module in `ModuleOutputWriter`; the config's
`contentHash()` is folded into the fingerprint set so edits regenerate. The 12 per-platform
frontmatter shapes are unified in `GranularRulesWriter.GranularFormat` — the single-glob (per-class,
roles-off) path stays byte-for-byte identical.

A role file spans several owners, so it is rendered in `GranularSections` *qualified* mode: organised
by topic with fully-qualified element headings, and with each section's shared rule sentence hoisted
once (see [Granular rule files](#granular-rule-file-layout) below).

## Cross-module mirroring (`.vibetags-mirror`)

Guardrails are scoped to the module that owns the annotated source. A reactor that centralises its
tests in a separate module therefore leaves the code that exercises `@AILocked` native bridges and
`@AIPrivacy` key material with no rules in reach — and silently, because nothing in the build reports
the gap (issue #312).

A module declares that it wants another module's scoped rules by dropping a `.vibetags-mirror` file
in **its own** directory. The consumer opts in, not the producer: file presence on disk is how every
other VibeTags output is enabled, and the consuming module is the one that knows what it exercises.
The target needs no `@AI*` annotations of its own.

```
# payments-tests/.vibetags-mirror

# Source modules to mirror from, relative to this file's directory.
# No source lines at all = mirror from every module in the reactor.
../payments-core
../payments-api

# Globs appended to every mirrored rule file's frontmatter, so the mirrored rules
# actually match this module's sources.
# Defaults to **/<this-dir>/**/*.java when omitted.
glob = **/payments-tests/src/test/java/**/*.java
```

Mechanics:

- Mirrored files land in whichever **granular** rule directories the target has opted into
  (`.claude/rules/`, `.cursor/rules/`, …). Aggregate files are not mirrored — a module's own
  `CLAUDE.md` is its to own, and merging siblings into the root aggregate is what the sidecar
  merge already does.
- Filenames carry the reserved prefix `mirrored-<sourceModuleId>-`. Modules of a reactor compile in
  separate javac invocations, so each source module must be able to clean up its own stale mirrors
  without touching the target's own rules or another module's. A module's ordinary granular cleanup
  skips anything starting with `mirrored-`, and `cleanupMirrored` only ever considers its own prefix.
  **`mirrored-` is therefore reserved** at the start of a granular filename: a role named
  `mirrored-…`, or a class in a top-level package named `mirrored`, produces a stem VibeTags will
  still write and refresh, but will not garbage-collect once its annotations are gone. Rename either
  and the file is cleaned up normally.
- The config file is registered as a **watched input** in `.vibetags-cache`
  (`WriteCache.recordInput`, via `GuardrailFileWriter.watchInput`). It lives in a directory the
  compiling module's build fingerprint knows nothing about, so without that an edit would be
  invisible to the top-level short-circuit. A deleted config invalidates exactly one build and is
  then pruned.
- Check mode covers mirroring automatically: `checkFiles()` runs the same terminal step with the
  dry-run writer, so a missing or stale mirrored file is reported as drift.
- Absent any `.vibetags-mirror` file the feature costs one shallow directory listing per compile and
  changes no output at all. Discovery reaches two directory levels below the root and skips build
  output and source directories.

Removing a `.vibetags-mirror` stops future mirroring but leaves the already-written `mirrored-*`
files in place — same semantics as deleting any other opt-in file. Delete them to be rid of them.

## Granular rule file layout

Within one granular rule file, stanzas are grouped by section title and the lines shared by *every*
stanza in a section — where the constant `- **Rule**:` sentence always sits — are hoisted once under
the section heading and pluralized; each element keeps only what differs, typically its
`- **Reason**:` (issue #313). Elements whose whole stanza is shared collapse into a single
`- **Applies to**:` list.

Below `GranularSections.MIN_GROUP_SIZE` (2) stanzas, or when a section's stanzas share no lines, the
historical per-class output is emitted byte-for-byte — a lone element costs nothing.
