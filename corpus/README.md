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

Then a second phase, because non-interference is only half the question. Two real elements per
repo are annotated in the clone, `CLAUDE.md`, `GEMINI.md` and `.vibetags-locks` are created empty
to opt in, and the generated output is read back:

| # | Assertion | What it protects |
|---|---|---|
| 5 | Opting in produces content in every platform file | A file that exists is an opt-in and must be written |
| 6 | `CLAUDE.md` carries a `VIBETAGS-START` marker pair | The markers are what protect hand-authored content |
| 7 | Every annotated element reaches the file | Keyed on a unique reason string, not a predicted path: predicting the path means reimplementing the thing under test |
| 8 | No type-use annotation in any generated identity | In `path=` attributes, lock entries and filenames alike |

The annotations are applied to the clone under `target/corpus` and reverted with
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
