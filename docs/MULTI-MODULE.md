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

### YAML outputs merge differently

Stacking whole renderings is right for Markdown and for ignore-file lists. It is wrong for the six
generated YAML documents (`sweep.yaml`, `.plandex.yaml`, `.coderabbit.yaml`, `ellipsis.yaml`,
`.roomodes`, `.interpreter/profiles/vibetags.yaml`): a YAML document has one `rules:`, one
`reviews:`, one `customModes:`, and stacking N modules repeated the key N times. A strict parser
rejects that; a lenient one keeps the last occurrence, so every module but one lost its guardrails
silently. Measured on `example-multimodule` before the fix: `.roomodes` and `.coderabbit.yaml`
exposed 1 module of 4, `ellipsis.yaml` 90 rules of 100, `sweep.yaml` 54 of 59.

Those platforms therefore declare a `PlatformRenderer.mergeShape()` — the line their shared scaffold
ends on, the column their entries sit at, and what they emit when they have nothing to say. The
merge writes the scaffold once and puts every module's entries under it, still wrapped in
`VIBETAGS-MODULE` sub-markers (indented to the entries' column, because a dedented `#` line would
terminate a block scalar). `.plandex.yaml` merges bucket by bucket instead, since its `locked:` /
`audit:` / `privacy:` keys are conditional and would otherwise repeat in turn.

`YamlMergeShapeContractTest` renders each platform and fails if a declaration no longer matches what
its renderer writes, or if a new YAML platform ships without one.

### JSON and TOML outputs merge differently again

The marker-free files — `.mentatconfig.json` and `.pr_agent.toml` — failed twice over in a reactor,
and the first failure hid the second.

They never refreshed. The write phase decides whether a shared file may be rewritten from
`anyContributed`, which asks whether any module's sidecar holds a body for that service, and sidecar
bodies were stored only for marker-based services. For a JSON or TOML output the answer was
permanently "no module contributed", so the writer's `no-new-rules` guard skipped every update to an
existing file. Whatever the first successful write produced was frozen there: on the four-module
`example-multimodule`, `.mentatconfig.json` held **1 entry from 1 module**, and every later build
logged `no changes`.

Fixing only that would have turned a frozen file into a last-writer-wins file, because a whole-file
overwrite carries the compiling module's view of the project. So those renderers also declare a
`PlatformRenderer.wholeFileMerge()`, which re-assembles the document from every module's rendering:
JSON rules arrays are unioned inside their key, and PR-Agent's two `extra_instructions` blocks are
both rewritten from the union of the instruction lines. After the fix the same file holds **51
entries across 9 sections from all 4 modules**, and `.pr_agent.toml` went from 6 guardrail lines to
200.

The merges are format-aware rather than generic because there is no generic answer — concatenating
two JSON documents is not JSON. They parse only VibeTags' own output, whose shape is fixed by a
renderer in the same package, and return `null` rather than guessing when a document is not that
shape, leaving the caller with the previous behaviour.

The static configs (`.cody/config.json`, `.qwen/settings.json`, `.codex/config.toml`) declare no
merge: their content does not vary with the annotations, so every module renders the same bytes.
They still benefit from the refresh fix — without it, upgrading VibeTags never updated them in a
reactor.

`MultiModuleWholeFileMergeTest` derives the rule rather than listing it: it renders every marker-free
service with an empty model and a populated one, and fails any whose output differs but which
declares no merge.

Module identity comes from `ModuleRootResolver` — it walks up from the compiled sources to the
nearest `pom.xml`/`build.gradle(.kts)` — **not** from the JVM working directory, which is the reactor
root for every module of an in-process Maven/Gradle build (issue #278: last-writer-wins). Sidecars
are format v2; v1 files carry the broken working-directory identity and are pruned on read.

The resolver reaches the source file two ways, and needs both. javac's Tree API is the fast path but
`Trees.instance` accepts only javac's own `ProcessingEnvironment`; Gradle wraps it for incremental
annotation processing (VibeTags declares itself `aggregating`), so under Gradle the Tree API is
*never* available. `Elements.getFileObjectOf` (Java 18+) answers the same question through the
standard API and survives the wrapper. Without it every Gradle module fell back to the working
directory — `~/.gradle/workers`, under neither the module nor the reactor — and collapsed onto one
content-hash id that appended a duplicate set of regions beside Maven's named ones (issue #331).
When identity still cannot be derived, `-Avibetags.module=<name>` sets it explicitly, and a build
that falls back to a content hash while named sidecars already exist emits a `[WARNING]` naming
both.

### Source sets

A module is compiled once per **source set**: Maven's `compile` and `test-compile` are two javac
invocations over disjoint sources, and a test-sources-only round legitimately cannot see a single
main source. Each source set therefore owns its own sidecar file — `.vibetags-mod-core` for `main`,
`.vibetags-mod-core__test` for anything else — so neither round can overwrite the other's
contribution or orphan-clean the rule files it could not see (issue #330).

They share a **region id**, though: the merge groups sidecars by region, so one module still
produces one `VIBETAGS-MODULE` region and a single-module project with annotated tests keeps its
historical sub-marker-free output. `ModuleSidecar.regionCount()`, not the sidecar count, is what
decides whether a build is multi-module.

Each sidecar also records the granular rule stems it wrote (`GranularRulesWriter.stemsFor`, a pure
function computed *before* the write so the `@AILocked` `generateFiles()` step order is unchanged).
Every cleanup pass adds every *other* sidecar's stems to its exclusion list, which is what stops a
round from deleting rule files belonging to another source set — or another module.

Two preservation guards keep compiles with **no annotations** from destroying content: the module's
sidecar is only saved when annotations were found, and shared-file writes with no contributions
preserve the existing file content. Consequence: removing *all* annotations from a module leaves its
last contribution in place until its `.vibetags-mod-*` file is deleted (or the module directory
disappears).

## Per-module (nested) output

The sidecar/merge above produces the **root** files. Independently, a module can opt into a guardrail
file or granular dir **inside its own directory** (`touch module-a/CLAUDE.md`), and
`ModuleOutputWriter` writes that module's own guardrails there — scoped to that module's annotations,
with **no sidecar and no merge**. It simply re-runs the single-module pipeline (`ServiceRegistry` →
`GuardrailContentBuilder` → `GuardrailFileWriter`/`GranularRulesWriter`) against `compilationRoot()`
with the module dir's own file-existence opt-ins, so the scoped-rules index composes per-module too.

Its content is rendered in `generateFiles()` (not inside `ModuleOutputWriter`) so it can go into the
sidecar under `~mod~<service>` keys, and the writer concatenates the bodies of every sidecar sharing
this module's region — main first. That is what makes a module's own `CLAUDE.md` survive a
`test-compile` round that saw none of its main sources.

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

**The safety tier stays inline** (issue #332). What each module contributes to the lean root is its
*safety digest* — `@AILocked`, `@AICore`, `@AIPrivacy`, `@AIIgnore`, `@AIAudit`, `@AISecure` and
nothing else — followed by the pointer. Those guardrails earn their keep by being unconditionally
present: `@AILocked` exists to stop an agent that has not yet opened the locked file, and an
`@AIAudit` an agent only learns after opening the file arrives after it has formed its plan. The
verbose per-element detail is exactly what should load on demand, and that is what the pointer
replaces. The digest is rendered by `GuardrailContentBuilder.safetyDigest()` — the same indexed
renderer variant the single-module case uses, minus the scoped-rules index, because the scoped files
live under the *module* directory and the root cannot name them relatively. A module with nothing in
the safety tier contributes only its pointer, so no empty `<project_guardrails>` shell appears.
Digests ride in the sidecar under `~idx~<service>` keys.

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

### A role file is written by every module it matches, so it merges

`.vibetags-roles` at the **reactor root** routes on the element's package, not on the module it
lives in, so one role routinely spans several modules — and all of them resolve the same output
path. Each module's compile therefore wrote the whole file, and each one overwrote the last: only
the module that happened to compile last kept its guardrails, and the rest disappeared with nothing
in the build reporting it. Which module won depended on which modules recompiled, so an unrelated
one-module edit also produced a spurious diff in a generated file (issue #365). Measured on
`async-test-lib`: the shared role file held **1 module of 3**, and an `@AICore` marked *critical* was
absent from `.gemini/rules/` entirely while appearing in the aggregate `GEMINI.md`.

Granular files therefore merge the same way the aggregates do. Each compilation records what it
contributes to each rule file — the globs its frontmatter needs and its rendered body — in its own
sidecar under `~gran~<stem>` (`GranularRulesWriter.contributionsFor`, computed from the same `plan`
the write itself uses, so a recorded contribution can never describe a file that would have been
written differently). `ModuleSidecar.mergeGranular` then groups those contributions by region and
hands the writer one body per file: a lone contributor's body verbatim — which is what keeps the
single-module output byte-for-byte unchanged — and several wrapped in `VIBETAGS-MODULE` sub-markers,
with their globs unioned. The union is taken whole rather than per module, so every module writes
the same bytes and reactor order cannot churn the diff.

The module's own nested rules (`module-a/.claude/rules/`) merge through the same machinery, under
`~modgran~<stem>` and scoped to one region: no cross-module merge and no sub-markers there, but a
role matched by both a module's main and test sources still needs both rounds' contributions —
without them the second source set to compile replaced the first's file.

A sidecar written before this carries no contributions at all; the compiling module then publishes
its own rendering, which is the pre-merge behaviour, rather than failing. Same for a contribution
that will not parse.

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
