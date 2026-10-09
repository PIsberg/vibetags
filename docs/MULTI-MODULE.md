# Multi-module reactors

How VibeTags behaves when more than one Maven/Gradle module compiles into the same project root, and
the four opt-ins that shape that output. Split out of `CLAUDE.md` — read this when working on
sidecars, per-module output, roles, or cross-module mirroring.

`examples/multimodule/` is a three-module reactor demonstrating the sidecar merge end-to-end (built
and asserted in CI); `examples/multimodule-indexed/` demonstrates the lean root index.

## Sidecar aggregation (the default)

Every module contributes to the shared marker files via per-module sidecars
(`.vibetags-mod-<moduleId>` at the VibeTags root): each compile persists its own rendered bodies,
reads all sibling sidecars, and merges them into the shared files with `VIBETAGS-MODULE: <id>`
sub-markers.

A module contributes a region when it has guardrail content — an annotation in its own sources, or
a rule inherited from a dependency it imports (see
[PROCESSOR.md](PROCESSOR.md#transitive-guardrails-dependency-tree-propagation)). Inherited rules
land inside that module's own region rather than once at the root, which is the answer that is true
per module: a module inherits what *its* sources import, and two modules of a reactor rarely depend
on the same set. `.vibetags-mirror` on its own still creates no region — mirrored rules are scoped
files, and they never reach the aggregate.

A sidecar is `key=value` lines with Base64 bodies, opened by `# version=3` and closed by `# end`.
Its `modulePath` is written with forward slashes on every OS, and a backslashed one written by an
older processor on Windows is read the same way, so a checkout built from both Windows and Linux
(WSL, a bind mount) does not prune a live module as departed (#868).
The trailer is what says the file is whole: `Base64.getDecoder()` accepts most cut-off input, so
without it a sidecar truncated by a torn write still decoded — to a body that was never saved, which
the merge rendered into every sibling's aggregate (issue #553). A sidecar with no trailer is treated
like one that could not be opened at all: skipped, never deleted. That holds when the cut left a
value the decoder rejects, too: the trailer is judged first, and only a file that has one is
deleted as `malformed` for a value that will not decode. That also covers the file a
processor older than the trailer wrote, which is skipped until its module recompiles; older readers
in the other direction skip the `# end` line as an unrecognised comment, which is why this is an
appended line rather than a format-version bump.

When a sidecar is left out, `vibetags.log` says why: `sidecar.prune reason=… path=…` for one that
was deleted (`stale-format`, `malformed`, `module-gone`, `invalid-module-path`, `superseded`) and
`sidecar.skip reason=…` for one that was kept and merely ignored this build (`future-version`,
`unreadable`, and every prune reason under check mode, which deletes nothing). That is the trace to
grep for when a module's section is missing from a generated file.

### YAML outputs merge differently

Stacking whole renderings is right for Markdown and for ignore-file lists. It is wrong for the
generated YAML documents (`.coderabbit.yaml`, `.roomodes`, `.aider.conf.yml`; until 1.4.0 also
`sweep.yaml`, `.plandex.yaml`, `ellipsis.yaml` and the Open Interpreter profile, #720): a YAML
document has one `reviews:`, one `customModes:`, one `read:`, and stacking N modules repeated the
key N times. A strict parser
rejects that; a lenient one keeps the last occurrence, so every module but one lost its guardrails
silently. Measured on `examples/multimodule` before the fix: `.roomodes` and `.coderabbit.yaml`
exposed 1 module of 4, `ellipsis.yaml` 90 rules of 100, `sweep.yaml` 54 of 59.

Those platforms therefore declare a `PlatformRenderer.mergeShape()` — the line their shared scaffold
ends on, the column their entries sit at, and what they emit when they have nothing to say. The
merge writes the scaffold once and puts every module's entries under it, still wrapped in
`VIBETAGS-MODULE` sub-markers (indented to the entries' column, because a dedented `#` line would
terminate a block scalar).

`YamlMergeShapeContractTest` renders each platform and fails if a declaration no longer matches what
its renderer writes, or if a new YAML platform ships without one.

### JSON and TOML outputs merge differently again

The marker-free files — `.pr_agent.toml`, and Mentat's `.mentatconfig.json` until 1.4.0 removed it
(#720) — failed twice over in a reactor,
and the first failure hid the second.

They never refreshed. The write phase decides whether a shared file may be rewritten from
`anyContributed`, which asks whether any module's sidecar holds a body for that service, and sidecar
bodies were stored only for marker-based services. For a JSON or TOML output the answer was
permanently "no module contributed", so the writer's `no-new-rules` guard skipped every update to an
existing file. Whatever the first successful write produced was frozen there: on the four-module
`examples/multimodule`, `.mentatconfig.json` held **1 entry from 1 module**, and every later build
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

A marker-free output whose content never varies would need no merge, only the refresh fix, or
upgrading VibeTags would never update it in a reactor. `.codex/config.toml` was the only one, and it
stopped being an output in #934.

`MultiModuleWholeFileMergeTest` derives the rule rather than listing it: it renders every marker-free
service with an empty model and a populated one, and fails any whose output differs but which
declares no merge.

Module identity comes from `ModuleRootResolver` — it walks up from the compiled sources to the
nearest `pom.xml`/`build.gradle(.kts)` — **not** from the JVM working directory, which is the reactor
root for every module of an in-process Maven/Gradle build (issue #278: last-writer-wins). Sidecars
are format v2; v1 files carry the broken working-directory identity and are pruned on read.

The id is the module's path with every character outside `[a-zA-Z0-9._-]` turned into `_`, so two
directories can share one: `a/b` and `a_b`, or a module directory named `core__test` and module
`core`'s test source set (`__` separates the source set). Two modules with one id share one sidecar
and one region, and each one's build replaces the other's guardrails. The build names both modules
in a WARNING when it sees the sidecar under its id was written by another module whose directory
still exists (#869); `-Avibetags.module=<name>` on one of them gives it an id of its own. Ids are
not renamed automatically, because every committed region marker would move with them.

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

### One module, two identities

A sidecar is retired when its `modulePath` no longer names a directory under the root. That check is
blind to a sidecar whose module path *is* the root, because the root directory always exists.

A Gradle repository with one included subproject reaches exactly that state. `settings.gradle` at
the git root declares `include 'app'`, every source lives under `app/`, and `compileJava` passes
`-Avibetags.root` pointing at the git root. A build whose module-root walk stopped at the git root
writes `.vibetags-mod-_root_` with an empty `modulePath`; a build that resolves the subproject
writes `.vibetags-mod-app`. Nothing retired the first, so every later build emitted both regions,
with byte-identical content, into every generated file: 24 rule files, 212 duplicated lines.

`readAll` therefore also retires a region whose annotated elements are *all* claimed by a **fresher**
region above or below it in the module tree. An annotated element belongs to exactly one module — its
source file lives in exactly one module directory — so two regions claiming it are the same sources
read twice, and one is a leftover. Which one is settled by the sidecar timestamps, not by depth,
because the move happens in both directions:

| Move | Leftover | Live |
|---|---|---|
| Sources move **down** into a subproject | `_root_` (`modulePath=`) | `app` |
| Sources move **up** out of one, `app/` stays as a directory | `app` | `_root_` |

Depth alone gets the second case backwards, and getting it backwards is worse than the duplication it
fixes: the aggregate is assembled from sidecars, so retiring the live region means every later edit
renders into a region that is then dropped, and the generated files freeze on the departed module's
last text with no diagnostic. `ModuleFlattenedIntoRootTest` is that case end to end.

Ties go to the more specific module: on equal timestamps a descendant still retires its ancestor,
never the reverse. Two sidecars written inside one filesystem tick therefore resolve the same way on
every build.

Timestamps decide only where they have to. A region whose elements are all claimed by the modules
nested inside it, where those modules between them also know **at least one element it never had**,
is retired without consulting timestamps at all. Freshness gated this until #621, and that left a
build repairing nothing in either direction: the ancestor was the more recently written file, so its
descendant was not allowed to retire it, and the ancestor's smaller element set could not cover the
descendant's, so neither went. Both survived and every element they shared was written twice.

Recency is not evidence of correctness in that direction, and arguably the reverse: a nested identity
is only ever produced by resolving a real build file, while the ancestor identity is the *fallback* a
compilation takes when it cannot resolve its module root from the round's sources. So the fresher of
the two is the one more likely to be wrong. The strictness is what keeps the second row of the table
above working: equal element sets are exactly what "sources moved up" also looks like, and there the
nested sidecar is the leftover, so equality is still settled by freshness.

The symptom to recognise, because it misleads: only *some* annotated elements duplicate, and they are
exactly the ancestor's smaller set. That reads like a bug specific to certain annotations, or to
elements carrying more than one, and it is neither.

If you need to rule this out entirely rather than have it repaired, pass `-Avibetags.module` with a
stable name alongside `-Avibetags.root`. That pins the identity instead of leaving it to per-round
resolution, so the fallback identity is never produced in the first place.

The rule is conservative in every other respect. A region is dropped only when the fresher regions
cover *all* of its elements, so a reactor root that compiles sources of its own keeps at least one
element no submodule has and keeps its region and its sub-markers. Sibling modules are never in a
path relation. A sidecar that records no element ids says nothing and is left alone, as is one whose
timestamp cannot be read. `SupersededAncestorRegionTest` pins those boundaries;
`AncestorModuleDuplicateRegionTest` pins the reported build end to end.

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

Inside a region the source sets' bodies are joined main first. A YAML platform joins them through
its `mergeShape()`, because two stacked documents repeat a top-level key. `CLAUDE.md` and
`CLAUDE.local.md` join them through `ClaudeSectionMerge` (`PlatformRenderer.sourceSetMerge()`):
one `<project_guardrails>` block, each section once with both source sets' entries, each rule
sentence once. Stacked, the test round repeated the header, the wrapper and every rule, 955 of
the 1,755 bytes it added to this repository's `CLAUDE.md` (#839). The merge declines, and the
bodies are concatenated as before, when a body is not the shape it knows, for example a sidecar
written by another processor version. The Markdown renderers (`AGENTS.md`, `GEMINI.md`,
`llms.txt`, `.cursorrules` and the rest) join them through `MarkdownSectionMerge` (#841): the
generated header once, each `##` section once with both source sets' bullets, and the
`TESTING.md` pointer once at the end. It declines on the same terms. `.vibetags-locks` joins through
`LocksReportMerge` (#851), a union of its JSON Lines, so the header and the format record appear
once. The JSON outputs are still concatenated.

Each sidecar also records the granular rule stems it wrote (`GranularRulesWriter.stemsFor`, a pure
function computed *before* the write so the `@AILocked` `generateFiles()` step order is unchanged).
Every cleanup pass adds every *other* sidecar's stems to its exclusion list, which is what stops a
round from deleting rule files belonging to another source set — or another module.

That list is necessary but not sufficient, because it can only exclude what it can see. Sidecars are
gitignored, so on a fresh clone they arrive one module at a time and a module round looking at the
shared root sees a directory full of files nothing has claimed *yet*. Sweeping on that evidence
deleted 256 committed rule files on a cold `mvn -B -pl core clean compile`, exit 0 (issue #383). So
the rule is jurisdiction, not arithmetic: **a reactor module round never sweeps the shared root.**
It cleans its own per-module directory and its own mirrors, both already scoped to it; only a round
whose compilation root *is* the VibeTags root may retire a root rule file. Counting sidecars instead
does not work — the sweep simply moves to reactor module 3, which sees two siblings and still not
the fourth. The cost is the same one the next paragraph describes: an orphan can outlive the build
that orphaned it.

Two preservation guards keep compiles with **no annotations** from destroying content: the module's
sidecar is only saved when annotations were found, and shared-file writes with no contributions
preserve the existing file content. Both have one exception since #781: a round that found nothing,
**was handed sources of its own**, and whose sidecar on disk records elements has had its
annotations removed, not hidden. It saves its empty sidecar and rewrites the files it withdrew
from, the module's own module-scoped files included, so removing *all* annotations from a module or a source set retires its contribution on the
next compile of it. What makes the empty result safe to believe is that a partial round is refused
before generation (invariant 17). The case still out of reach is a module or source set with no
sources left at all, which no build compiles: its contribution stays until its `.vibetags-mod-*`
file is deleted (or the module directory disappears).

### Parallel builds

A module round saves its sidecar, reads every sidecar, merges, and writes the root files. Under
`mvn -T` or Gradle `--parallel` two modules can do that at once, and before #908 nothing stopped
the one that read first from writing last: its merge did not include the sibling that saved in
between, so that sibling's region was missing from `CLAUDE.md` and every other root file until the
next build. `ParallelReactorGenerationTest` holds one module between its read and its write and
reproduces it every time.

A module round now generates under an exclusive lock on `.vibetags-generate.lock` at the VibeTags
root (empty, never read; gitignore it like the sidecars). The second module waits, then reads after
the first has written, so every merge sees every saved sidecar. Details that matter when reading a
log:

- **Only module rounds of an opted-in root lock.** The root's own round and a single-module build
  never race a sibling, and a root with no opt-in gets no root files to race over, so none of them
  takes the lock or creates the file: a project that never opted in still has nothing written to it
  (invariant 1). The module test is the one the root sweep uses (`maySweepRoot`).
- **It works within one JVM as well as across processes.** A file lock belongs to the whole JVM, so
  a second module in the same build process gets `OverlappingFileLockException` instead of waiting,
  whichever classloader loaded each processor. The lock polls `tryLock()` every 10 ms, which waits
  in both cases. Threads of one JVM queue at an in-JVM gate before opening the file at all (#923):
  on Linux closing any channel on a locked file drops the process's lock, so a waiter that gave up
  and closed its own channel used to free the holder's lock for another process.
- **It never fails a build.** A root that will not open the file, a filesystem that refuses locks, an
  interrupt, or a wait over two minutes proceed unlocked, as every build did before, and say so:
  `generate.lock.skip module= reason=unopenable|unsupported|interrupted|timeout` (WARN). A module
  that had to wait logs `generate.lock.wait module= reason=sibling-generating` and then
  `generate.lock.acquired module= waitedMs=` (INFO).

Generation is milliseconds to seconds per module, so the cost is that much serialisation on the
VibeTags step only; compilation itself still runs in parallel.

The write cache is shared the same way: every module's processor holds its own view of the one
`.vibetags-cache`, loaded before any of them generates. A flush re-reads the file and writes back
only what that module changed (the entries it recorded or removed, its own `# module:` section,
and the root sidecar stamp if it set one), so the result is what a serial build would have left
(#907). Before, each flush wrote its whole stale view, and the last module to flush dropped its
siblings' entries, fingerprints and source digests: a cache miss for each, on the build after a
parallel one. On this repository's `examples/multimodule`, the second of two `mvn -T 4 clean
verify` builds hit the cache for 285 of 544 file writes before the merge and 544 after, as many
as a serial build; the misses fell back to reading and comparing the file (#915).

The merge is not a lock, so every flush a module round makes runs under the lock above:
generation's, and the one a round skipped as unchanged makes when another processor's
generated sources force it to clear its source digest (#916). Two unlocked flushes could each
re-read before the other wrote, and losing that clear would leave a digest vouching for the skip.

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

A nested `TESTING.md` composes the same way, and is supported (#786). `touch module-a/TESTING.md`
and that module's test-round guardrails go to its own file instead of the root one: its own
`CLAUDE.md` gives them up and carries the pointer once, and the root `TESTING.md` is unaffected.
This needs no special case — `ModuleOutputWriter` re-runs the same pipeline through the same
registry, so routing composes per module exactly as every other opt-in does, which is why it is
documented rather than merely tolerated. Pinned by
`MultiModuleTestingMdTest.aTestingMdInsideAModuleTakesThatModulesTestGuardrailsOnly`.

Called as a terminal step in `generateFiles()`/`checkFiles()`; **gated on `moduleRoot != null` and
`!compilationRoot.equals(root)`** so in-memory/non-javac compiles (which fall back to the JVM working
dir) never write there. The module's own opt-in set is folded into the `BuildFingerprint` input so a
freshly-touched module file isn't skipped by the short-circuit. The sidecar remains untouched and
serves only the root aggregate.

## Lean indexed root aggregate (`.vibetags-root-index`)

By default the reactor-root aggregate (`CLAUDE.md`, `.cursorrules`, `.windsurfrules`,
`.github/copilot-instructions.md`, `GEMINI.md`) embeds a full verbatim copy of every module's guardrails via the
sidecar merge. In a reactor where each module already carries its own scoped rules (`.claude/rules/`
etc.), that root block is a second copy of content the tool auto-loads from the module files (issue
#298). Touching `.vibetags-root-index` at the root opts into a **lean index**: for the five
aggregates that have a granular sibling, the merge replaces each module's embedded body with a short
pointer to that module's own scoped rules (and/or its own aggregate file), still wrapped in the
`VIBETAGS-MODULE` sub-markers. The root module's own body stays inline, and aggregates **without** a
granular sibling (`AGENTS.md`, `llms.txt`, `.vibetags-locks`, …) keep the full merge.

`GEMINI.md` joined the list in #763. It already collapsed to a scoped-rules index inside a single
module (#320) and was missing from the root list only because the pairing was declared in four
places; a reactor with both files got a lean root `CLAUDE.md` and a fat root `GEMINI.md` from one
build. The five pairs now come from one table, `GranularPairing`. The Gemini pointer is worded
differently on purpose: Gemini CLI does not read `.gemini/rules/` by itself (#669), so the pointer
tells the agent to open the matching rule file instead of saying it loads automatically.

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

A test source set's sidecar can carry one more reserved key family, `~tfull~<service>`, written
only by a round that routed its guardrails to `TESTING.md`. It holds what the round would have
written to that service's file without `TESTING.md`. The ordinary `<service>` body of such a
sidecar is the safety half only, so the merge reads `~tfull~` instead once `readAll()` finds the
opt-in gone; the decision is transient state on the sidecar instances, like the index mode above,
and `mergeFor` stays disk-free. An unrouted round writes no such key, and an older processor
leaves it unstored like any reserved key it does not know. See
[PROCESSOR.md](PROCESSOR.md#test-code-guardrails-testingmd).

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
the same bytes and reactor order cannot churn the diff. The heading name and front-matter description
travel with each contribution under `~granname~<stem>` (a key of its own, so an older sibling that
does not know it leaves it unstored instead of reading it as glob or body text) and are joined the
way the single-module case-collision fold joins them: two modules whose stems differ only in
capitalisation, one physical file on Windows and macOS, get `# Rules for Payment, payment` from
either module rather than a heading that flips with compile order (issue #579).

The module's own nested rules (`module-a/.claude/rules/`) merge through the same machinery, under
`~modgran~<stem>` (naming under `~modgranname~<stem>`) and scoped to one region: no cross-module merge and no sub-markers there, but a
role matched by both a module's main and test sources still needs both rounds' contributions —
without them the second source set to compile replaced the first's file.

A sidecar written before this carries no contributions at all; the compiling module then publishes
its own rendering, which is the pre-merge behaviour, rather than failing. Same for a contribution
that will not parse.

## Build layouts: what resolves, and what needs telling

A module root is the nearest ancestor directory holding a `pom.xml`, `build.gradle` or
`build.gradle.kts`. `settings.gradle` is deliberately **not** one of those markers: it names the
root of a Gradle build, not a module inside it. Everything below follows from that one rule, and
each row has a worked example under [`examples/`](../examples/).

| Layout | Resolves by itself | What it needs |
|---|---|---|
| Maven aggregator, modules in subdirectories | Yes | Nothing |
| Nested modules (`a/b`) | Yes, innermost build file wins | Nothing |
| Gradle subprojects each with their own `build.gradle` | Yes | `-Avibetags.root` at the reactor root, because each subproject compiles in its own worker directory ([gradle-multimodule](../examples/gradle-multimodule/)) |
| Gradle subprojects configured from the **root** build file | **No** | `-Avibetags.module=${project.name}` ([gradle-shared-buildfile](../examples/gradle-shared-buildfile/)) |
| Flat layout: module beside the root, not below it (`includeFlat`, `projectDir` override) | Partially | `-Avibetags.root` at the directory containing both ([gradle-flat](../examples/gradle-flat/)) |
| Composite build (`includeBuild`) | **No** | `-Avibetags.root` at the containing directory, in every participating build ([gradle-composite](../examples/gradle-composite/)) |

### Subprojects with no build file of their own

This is the one layout that loses data rather than merely looking odd, so it is worth stating
plainly. When `settings.gradle` declares subprojects but all of their configuration lives in the
root build file, the upward walk from a source file passes straight through the subproject
directory and lands on the root. Every subproject then resolves to the same module identity,
writes the same sidecar, and overwrites the one before it.

Measured on `examples/gradle-shared-buildfile` before its remedy was applied: one
`.vibetags-mod-_root_` for two modules, and an aggregate carrying `com.example.gsb.core.Ledger`
with `com.example.gsb.app.Runner` absent. Not stale and not duplicated — gone. Whichever
subproject compiled last is the only one whose guardrails survive.

Since 1.2.4 the build says so, naming the subprojects and the option:

```
warning: VibeTags: core, app are declared as Gradle subprojects but have no build file of
their own, so they resolve to this root and share one module identity ... Pass
-Avibetags.module=${project.name} in the shared build file to give each one its own identity.
```

The remedy is one line in the shared build file:

```groovy
options.compilerArgs << "-Avibetags.module=${project.name}"
```

### Out-of-tree modules

A module that is not under the VibeTags root has no module path under it, so `computeModulePath`
returns `""`, and `computeModuleId` builds the id from the module directory's name and a hash of
its path *relative* to the root (`../sibling/api` gives `api_<hex>`). Since #436 that id is the same
from any checkout location, so committed output reproduces across machines; before, it hashed the
absolute path and check mode reported drift on CI. Two costs remain: the region marker carries a
hash suffix rather than a plain name, and the empty module path makes the staleness check skip its
directory test, so the sidecar is never pruned when the module goes away. A module on a different
drive from the root (Windows), where no relative path exists, still falls back to a hash of the
absolute path, which does move with the checkout.

Pointing `-Avibetags.root` at a directory that contains every module avoids all of these.

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
- Filenames carry the reserved prefix `mirrored-<sourceModuleId>-`; a non-primary source set of the
  module mirrors under `mirrored-<sourceModuleId>__<sourceSet>-` (the test round of `core` writes
  `mirrored-core__test-…`), so the main and test rounds of one module never clean up each other's
  files. Modules of a reactor compile in separate javac invocations, so each source module must be
  able to clean up its own stale mirrors without touching the target's own rules or another
  module's. A module's ordinary granular cleanup skips anything starting with `mirrored-`, and
  `cleanupMirrored` only ever considers its own prefix. One prefix can begin another's — `core`
  beside `core-api` — so the cleanup also leaves alone every file under the prefix of a sibling
  module directory whose id extends this module's id; the siblings are read off the module
  directories under the root (build file present, up to three levels down), which a cold clone has
  before any sidecar exists.
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
