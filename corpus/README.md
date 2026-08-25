# The third-party corpus

Six real Java libraries, pinned to commits, compiled twice on every CI run: once without
VibeTags and once with it. Nothing is vendored. The sources are cloned at build time into
`target/corpus` and never committed, so no third-party code enters this repository.

```bash
corpus/run-corpus.sh               # all of it
corpus/run-corpus.sh commons-cli   # one repo
```

Requires `vibetags-annotations` and `vibetags` installed first (see
[CLAUDE.md](../CLAUDE.md#build-and-test)).

## Why it exists

Every fixture in this repository was written by somebody who knew what VibeTags does.
`examples/basic` has the annotations a demo needs; `GuardrailModels` has the members a test
author thought of. Neither contains code written by people who had never heard of this project,
and that is precisely the code VibeTags has to survive.

The corpus is that code. It found a real gap on its first run (see below).

## What is asserted

Each repo is compiled twice with identical sources, classpath and flags. The only difference is
whether VibeTags is on the processor path. The comparison is the point: asserting "it compiled"
against a hard-coded zero would silently pass on a repo that never compiled to begin with.

| # | Assertion | What it protects |
|---|---|---|
| 1 | The treatment exits exactly as the control did | The promise the design rests on: adding VibeTags to a build must not fail it |
| 2 | The treatment raises no error or warning the control did not | A processor that turns a clean build noisy has broken the same promise, more quietly |
| 3 | Nothing is written to the VibeTags root | File presence is the only opt-in (tier-1 invariant 1) and none of these repos opted in |
| 4 | `ElementNaming` renders every member as javac does | That string is the element's identity in `.vibetags-locks` and in granular rule filenames |

Assertion 0 sits in front of all of them: **the control itself must compile.** Comparing against
a control is what stops a broken repo being blamed on VibeTags, but it also means a repo that
cannot compile at all passes assertions 1 and 2 trivially, both sides failing identically, while
the model behind 3 and 4 is full of error types. That is not a corpus member, it is a vacuous
pass, and it happened: see [What it found](#what-it-found).

## Phase two: opt in, and read the output back

Non-interference is only half the question. The second phase turns VibeTags on the way a consumer
would and inspects what comes out.

**Three platforms, the most used ones, aggregate and granular:**

| Platform | Aggregate | Granular | Opt-in |
|---|---|---|---|
| Claude | `CLAUDE.md` | `.claude/rules/` | create both |
| Gemini | `GEMINI.md` | `.gemini/rules/` | create both |
| Codex | `AGENTS.md` | none | create it **with a marker pair** |

Codex is the interesting one. Invariant 4 says `AGENTS.md` is written only as the sole AI config
file or when it already carries a `VIBETAGS-START`/`END` pair, so an empty `AGENTS.md` alongside
Claude and Gemini is silently dropped from the active set. The harness seeds the pair, which is
what a real consumer does, and then asserts `AGENTS.md` grew beyond it. Without that second check
the corpus would report Codex working while generating none of it.

**Two sources of annotations, for two different reasons:**

- **Real elements in the repo's own code**, chosen deterministically: the first public type, and
  the first method with a jspecify `@Nullable` parameter. This is what proves VibeTags handles
  *their* signatures, and it is what found both naming defects below.
- **A showcase compiled into a package of its own** under the repo's source root
  (`corpus/showcase/`). No third-party repo contains `@AI*` annotations, so the full surface has
  to come from somewhere; putting it in their tree means it compiles with their classpath, their
  language level and their compiler settings.

The showcase carries a guardrail at **every level one can attach to**, in both tiers:

| Level | Annotations | Lands in |
|---|---|---|
| package | `@AIArchitecture`, `@AISecure` on `package-info.java` | Tier 1 (safety) and Tier 3 |
| type | `@AIContext`, `@AIPublicAPI` | Tier 3 |
| nested type | `@AICore`, `@AIImmutable` | Tier 1 (safety) and Tier 3 |
| field | `@AIPrivacy`, `@AISecureLogging`, `@AIPerformance` | Tier 1 (safety) and Tier 3 |
| method | `@AILocked`, `@AIAudit`, `@AISecure`, `@AIIgnore`, `@AIContract`, `@AIPerformance`, `@AITestDriven`, `@AIThreadSafe`, `@AILoadBearing`, `@AIKeepInSync`, `@AIBannedApi`, `@AIGenerated` | both tiers |
| constructor | `@AILocked`, `@AIContract` on two overloads | Tier 1 (safety) and Tier 3 |
| parameter | `@AIInputSanitized`, `@AILoadBearing` | Tier 3 |

The constructor row exists because the showcase failed to compile without it. No annotation
declared `ElementType.CONSTRUCTOR`, so a constructor could not be guarded at all, even though
`ElementNaming` had always rendered constructors: javac hands them to the collector as enclosed
elements of an annotated type, which is why `ElementNamingFormatParityTest` covers the shape.
Constructors were visible to the renderer and unaddressable by an author, and the way anyone
found out was a compiler error. 34 annotations accept one now (#488); two do not, and
`ConstructorLevelGuardrailTest` names them with the reason. Two overloads are annotated on
purpose: they must be addressed by their own parameter lists.

### What phase two asserts

| # | Assertion | What it protects |
|---|---|---|
| 5 | Every opted-in platform file has content, **checked per platform** | One renderer can be dropped and the other two will cover for it in any total |
| 5b | `AGENTS.md` grew beyond the seeded marker pair | Codex being dropped from the active set looks identical to Codex working |
| 6 | Every aggregate carries a `VIBETAGS-START` pair | The markers are the whole promise that hand-authored content survives |
| 6b | Both granular directories were written | An opted-in directory that stays empty looks exactly like "this project has no rules" |
| 6c | **The tier split**: `@AIPrivacy` inline, `@AIContract` *not* inline but present in the rules directory | Invariant 6, checked on somebody else's code. Wrong in one direction, safety guardrails become comments; wrong in the other, the aggregate bloats |
| 6d | The parameter level survived | The finest addressing VibeTags produces, and the only level a hand-written rules file cannot express |
| 6e | The package level survived | The only level with no owning member to hang off |
| 6f | **The richness floor**: at least 17 distinct showcase guardrails reached a generated file | Every other assertion names one guardrail, so a change that stopped rendering half the surface would still pass them all |
| 7 | Every annotated element reaches the file | Keyed on a unique reason string, not a predicted path: predicting the path means reimplementing the thing under test |
| 8 | No type-use annotation in any generated identity | In `path=` attributes, lock entries and filenames alike |

Assertion 6f is the one that keeps the rest from rotting. 17 is measured, not aspirational: it is
every marker the showcase declares, read off a green run. It went 15 to 17 when constructors
became annotatable, which is what raising it looks like.
**Lowering it to make a run green is how this check stops meaning anything.**

The annotations and the showcase are applied to the clone under `target/corpus` and reverted with
`git checkout -- .` afterwards. Nothing is committed and no third-party source is modified in
place.

`vibetags.log` is the documented exception to assertion 3 (`vibetags.log.path`, disabled with
`OFF`; see [USAGE.md](../USAGE.md)). It is created empty even in a project with no annotations.

Assertion 4 is the reason for the size. `ElementNamingFormatParityTest` checks the same property
against a 26-member fixture; the corpus checks it against **15,683 members** nobody chose.

## What it found

On its first run, over the two corpus members that use [jspecify](https://jspecify.dev):

```
javac   : org.semver4j.Semver.parse(java.lang.@org.jspecify.annotations.Nullable String)
derived : org.semver4j.Semver.parse(java.lang.String)
```

javac's `toString()` includes JSR-308 type-use annotations. The structural derivation introduced
in #480 drops them, so **any consumer using jspecify or the Checker Framework would have seen
their generated files move** when that landed. No fixture in this repository uses a type-use
annotation, so nothing caught it.

Dropping them is the right behaviour, and is now deliberate rather than accidental. Keeping them
would put the annotation into a rule *filename* -
`...parse-java-lang--org-jspecify-annotations-Nullable-String-` - so adding or removing a
`@Nullable` would rename a committed file and break a lock match, for a change that does not
alter the signature. The identity is the signature, not its annotations.

The harness therefore classifies a difference rather than just counting it: annotation-only
differences are reported as `TYPE-ANN` and pass; anything else fails. That distinction is what
makes the assertion meaningful instead of merely tolerant.

### And then a second one, which the parity check could not have found

Assertion 4 compares VibeTags against javac. That is blind to the case where **both are wrong**,
and that case was real. `DeclaredType` parameters had their annotations stripped through
`asElement()`, but a **type variable** fell through to `toString()` and kept its annotation, so
`jimfs` generated this into `CLAUDE.md` and into `.vibetags-locks`:

```
JimfsAsynchronousFileChannel.<A>lock(long,long,boolean,@org.jspecify.annotations.Nullable A, ...)
```

javac renders it the same way, so the auditor saw agreement and stayed silent. Only generating
output and reading it back exposed it. `ElementNaming` now resolves a type variable through
`asElement()` like everything else, and assertion 8 checks the generated identities directly
rather than trusting the comparison.

That is the argument for the opt-in phase in one example: a check that compares two
implementations can only find disagreement, never a shared mistake.

### And a third, in the harness rather than the product

`dependency:build-classpath` exits 0 while writing nothing when it decides the classpath has not
changed. On a warm machine the file is already there and everything looks fine. On a cold CI
runner it was not, so jimfs compiled with **no dependencies at all**, reported 100 diagnostics on
each side, and the run carried on: assertions 1 and 2 passed because both sides failed
identically, and assertions 3 and 4 ran against a model made of error types.

Two changes came out of it. `-Dmdep.regenerateFile=true` makes the plugin always write, and
assertion 0 refuses to evaluate a repo whose control did not compile. The second is the one that
matters: the first only fixes the cause that was found, the second fails loudly on every cause
that has not been.

Assertion 0 then immediately earned its place, on the very next run. CI restored `target/corpus`
from the cache written by the *failing* run, `.corpus-cp` among it, and the harness trusted it
because it was non-empty. jimfs compiled without guava on a branch where the resolution bug was
already fixed. So the resolved classpath is now rebuilt every run and never read from a cache: a
cache written by a broken run is indistinguishable from a good one once it is on disk. The cache
key covers `run-corpus.sh` as well as `repos.tsv`, and what the cache is actually for is the
checkouts, which are pinned to SHAs and therefore always safe to reuse.

### What that adds up to

| Defect | Found by | Would the others have caught it? |
|---|---|---|
| Type-use annotations dropped for declared types | assertion 4, first run | No: no fixture here uses one |
| Type-use annotation kept on a **type variable** | assertion 8, first opt-in run | No: javac agrees, so assertion 4 sees no disagreement |
| Corpus silently running on an unresolved classpath | assertion 0, first CI run | No: assertions 1 and 2 passed, identically failing |

Three defects, three different assertions, none of which could have found the others.

## The repos, and why each is here

Chosen for variety rather than volume. The construct counts were measured, not estimated.

| Repo | Files | Licence | What it contributes |
|---|---:|---|---|
| `commons-cli` | 36 | Apache-2.0 | Smallest and dependency-free: the fast smoke test, and the one that still runs when dependency resolution is down |
| `commons-codec` | 87 | Apache-2.0 | Static-utility style, byte-array APIs, 7 `package-info` files, 26 nested types |
| `commons-io` | 277 | Apache-2.0 | The largest: 15 `package-info` files, 279 sources using generics, 86 nested types |
| `jimfs` | 62 | Apache-2.0 | Generics- and varargs-dense (72 and 32 of 62 files), Guava-based, an NIO SPI implementation, jspecify-annotated |
| `record-builder` | 6 | Apache-2.0 | Records, and an annotation library whose own processor also runs at compile time |
| `semver4j` | 27 | MIT | Targets Java 17 rather than 8, so the corpus is not entirely legacy language level, and jspecify-annotated |

Roughly 495 files and 15,683 members in total.

## Adding a repo

1. Pick something small, permissively licensed, and *different* from what is already there. A
   seventh repo that looks like `commons-codec` adds runtime and no coverage.
2. Prove it compiles cleanly at the SHA you are pinning, with its own dependencies resolved. A
   corpus member that does not compile makes every assertion above vacuous for it.
3. Add a row to `repos.tsv` with the SHA, not a branch. A branch lets an upstream push turn this
   repository's CI red for a reason nobody here changed.
4. Record what it contributes, here and in the `why` column. "More code" is not a reason.

## Keeping the pins current

Deliberately not automated. A pin bump should be a commit somebody looked at, because the
interesting outcome is exactly the one an automated bump would paper over: upstream adopts a
language feature VibeTags renders wrongly. Bump when you want new coverage, and read the diff in
the `TYPE-ANN` and `MEMBERS` columns when you do.
