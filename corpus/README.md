# The third-party corpus

Nine real libraries, pinned to commits, compiled twice on every CI run: once without VibeTags and
once with it. Six are Java, compiled by javac, and are what the first half of this document is
about. The other three are Kotlin, Groovy and Scala, built by their own Gradle wrappers, and have
[a harness and a section of their own](#the-other-three-jvm-languages).

Nothing is vendored. The sources are cloned at build time into `target/corpus` and
`target/corpus-jvm` and never committed, so no third-party code enters this repository.

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

## Phase three: every platform, once

The opt-in phase covers three platforms, which leaves 40-odd renderers with no third-party
coverage at all. Renderer output does not depend on which repository it ran in, so this phase
runs on one repo rather than six: the cost is one extra compile.

Every service file the registry knows about is created, the showcase is compiled, and the result
is read back. Two assertions:

| # | Assertion | What it protects |
|---|---|---|
| 9 | Every opted-in platform file was written non-empty | Opting a file in and getting nothing back looks exactly like a project with no guardrails |
| 10 | Every YAML, TOML and JSON file **parses** | The question no fixture test asks |

Assertion 10 is the point of the phase. The fixture tests assert what a renderer *contains*; none
asserts that a real parser accepts it. A YAML renderer emitting an unquoted value that starts with
`@`, or a JSON one leaving a trailing comma, satisfies every `contains` assertion ever written and
cannot be loaded by the tool it was written for. Ten files carry a structured format
(`.coderabbit.yaml`, `.codex/config.toml`, `.cody/config.json`, `.interpreter/profiles/vibetags.yaml`,
`.mentatconfig.json`, `.plandex.yaml`, `.pr_agent.toml`, `.qwen/settings.json`, `ellipsis.yaml`,
`sweep.yaml`) and all ten parse.

Measured: **48 of 62 platform files written, 10 parsed.** The remainder are opted out or are mode
switches. `.vibetags-root-index` is excluded from the emptiness rule by name, because its presence
*is* the message: touching it turns the root aggregate into a lean index and nothing is ever
written to it. `ServiceRegistry` says the same in code by excluding `root_index` from the keys it
treats as output files.

The list of platform files is extracted from `ServiceRegistry` rather than kept here. A copy would
be a second source of truth whose failure is the quiet kind: a platform is added, the list does not
know, and the sweep reports success over a set that no longer matches the code. The extraction
asserts it found at least 40 entries, so a change to the registry's shape fails loudly instead of
silently narrowing what is checked.

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

## The other three JVM languages

Everything above is Java, compiled by javac, because that is the compiler JSR 269 belongs to.
`corpus/run-corpus-jvm.sh` asks the same questions of Kotlin, Groovy and Scala, and it is a
separate harness rather than three more rows in `repos.tsv` because none of them reaches the
processor the way Java does.

```bash
corpus/run-corpus-jvm.sh                  # all of it
corpus/run-corpus-jvm.sh kotlin-obd-api   # one repo
```

| Language | Route to the processor |
|---|---|
| Kotlin | kapt generates Java stubs and runs the processor over them. KSP does not run JSR 269 processors, so kapt is the only route |
| Groovy | groovyc generates stubs the same way, but only when `javaAnnotationProcessing` is on, and it is off by default in Gradle |
| Scala | scalac has no JSR 269 support at all. Only the Java half of a mixed build reaches the processor |

USAGE.md has said all three for several releases. Until this harness existed, none of them had
been run against code outside this repository.

### How VibeTags gets into a build nobody wrote for it

Each repository is built twice by its own Gradle wrapper, and the only difference between the two
runs is `corpus/inject-vibetags.init.gradle`:

```
control    ./gradlew <task>
treatment  ./gradlew <task> --init-script corpus/inject-vibetags.init.gradle
```

A Gradle init script is the supported way to add a plugin or a dependency to a build you do not
own, so nothing here edits a `build.gradle` it did not write. It is switched on by the presence of
`VIBETAGS_ROOT` in the environment, which is how the control runs the same command line without it.

Two details in that file are load-bearing, and both are there because the first version got them
wrong:

- **It skips `buildSrc` and included builds.** They are not the build under test — they compile
  the target project's own build logic, often in another language. splain's `buildSrc` is Kotlin,
  so without the guard the Scala member would have run kapt over the build logic of a Scala
  project.
- **On Kotlin projects it configures kapt only, never javac.** That is how `examples/kotlin` and
  USAGE.md configure it. Doing both put `-Avibetags.root` on kapt's javac command line as a raw
  flag as well as into kapt's option map.

`--rerun-tasks` is also not optional. Gradle's up-to-date checks would let the treatment skip
compilation entirely, because the control had just compiled the same sources from the same inputs,
and a treatment that never ran a compiler passes every assertion below while testing nothing.

### What is asserted

| # | Assertion | What it protects |
|---|---|---|
| J0 | The control build succeeds | Everything else is relative to it; a repository that cannot build passes exit parity trivially, both sides failing identically |
| J1 | The treatment exits exactly as the control did | Adding VibeTags to somebody's build must not fail it |
| J2 | No new compiler diagnostic, line by line, against a one-entry allow-list | A processor that turns a clean build noisy has broken the same promise, more quietly |
| J3 | Nothing written into a project that never opted in | File presence is the only opt-in (tier-1 invariant 1) |
| J4 | The annotated build still succeeds | Otherwise nothing was generated and J5 onward report on an empty directory |
| J5 | Every opted-in platform file has content, checked per platform | One renderer can be dropped and the other two cover for it in any total |
| J5b | `AGENTS.md` grew beyond the seeded marker pair | Codex being dropped from the active set looks identical to Codex working |
| J6 | Every aggregate carries a `VIBETAGS-START` pair | The markers are the whole promise that hand-authored content survives |
| J6b | Both granular directories were written | An opted-in directory that stays empty looks exactly like "this project has no rules" |
| J6c | The tier split: a safety guardrail inline, `@AIContract` not inline but present in the rules directory | Invariant 6, checked through three compilers instead of one |
| J6d | The parameter level survived | The level most exposed to a stub generator: a parameter name that does not survive into the stub cannot be addressed |
| J6f | Every marker the showcase declares, minus the language's documented exclusions, reached a generated file | A change that stopped rendering half the surface would still pass every assertion that names one guardrail |
| J6g | **No marker on the exclusion list appeared** | A limitation that quietly gets fixed leaves the documentation wrong in the generous direction, which nobody notices because the run is green |
| J8 | No type-use annotation in any generated identity | These compilers reach `ElementNaming` by different routes; there is no reason to assume they agree |
| J9 | Scala only: no marker from the annotated `.scala` file appears anywhere | USAGE.md's Scala row, turned into a test |
| J9b | Scala only: the Scala showcase produced class files | Without it, "scalac generated nothing" and "scalac was never asked" are the same green run |

J2 differs from the Java corpus's assertion 2 on purpose. There, javac runs either way and the
only difference is a `-processor` flag, so "no new diagnostics" is a fair comparison. Here it is
not: switching VibeTags on adds the kapt task graph to a Kotlin build and joint compilation to a
Groovy one, and those emit diagnostics about the machinery rather than about VibeTags. Counting
them fails for the wrong reason; raising a threshold to make them pass blinds the check. So every
new line must either be absent or be named in the allow-list, which has exactly one entry.

J6f and J6g are a pair, and the pair is the point. Anywhere a language genuinely cannot render a
guardrail, that marker is named in `excluded_markers()` with the evidence, and both directions are
then checked: it must not go missing from the expected set, and it must not appear from the
excluded one.

### The members

| Repo | Lang | Licence | Why this one |
|---|---|---|---|
| [`eltonvs/kotlin-obd-api`](https://github.com/eltonvs/kotlin-obd-api) | Kotlin | Apache-2.0 | A plain `kotlin("jvm")` library — not Android, not multiplatform — so kapt applies exactly as USAGE.md documents |
| [`bentsherman/nf-boost`](https://github.com/bentsherman/nf-boost) | Groovy | Apache-2.0 | A Groovy library whose classes groovyc can turn into valid Java stubs, which most of the candidates could not |
| [`tek/splain`](https://github.com/tek/splain) | Scala | MIT | One of very few Gradle-built Scala projects on a JDK 21 wrapper; `:core:classes` runs `compileJava` and `compileScala`, which is what makes the two-sided Scala assertion possible |

The Scala member carries two showcases in one build: a Java one that must generate everything, and
a Scala one that must generate nothing. Both halves are needed. Without the positive half, "nothing
came from Scala" and "nothing came at all" are the same result.

### What it found

**Groovy drops every field-level annotation, silently.**

This is the finding that justifies the harness. `@AIPrivacy` on a Groovy field generates nothing —
no file, no warning, no trace. It is one of the six safety annotations, it is the natural way to
mark a PII field, and until this ran, USAGE.md called the Groovy route "Full (joint compilation)".

The first guess was wrong, which is worth recording: a Groovy field with no access modifier
becomes a property, so the obvious theory was that the annotation had moved onto a generated
accessor. It had not. Keeping the stubs (`groovyOptions.keepStubs`) and reading one settles it —
the stub has the class, both constructors, all twelve methods and both parameters of `render`,
each with its annotations intact, and no fields whatsoever:

```java
@AIContext(...) @AIPublicAPI() public class CorpusShowcase
@AILocked(reason="...CONSTRUCTOR-LOCKED...") public CorpusShowcase
@AILocked(reason="...METHOD-LOCKED...") public long invoiceNumber(long id) { return (long)0;}
@AIContract(...) public String render(@AIInputSanitized(...) String customerNote, ...)
```

`billingEmail`, `authToken` and `tenantId` appear nowhere in it. The annotations are not lost by
VibeTags; they never reach any processor. Nothing in VibeTags can fix that, so it is documented
instead — and pinned, because a limitation nothing exercises is a limitation nobody notices has
been fixed. The Groovy showcase annotates those fields on purpose, `excluded_markers()` names the
two markers, and J6g fails the day they start appearing.

**Most real Groovy projects cannot switch annotation processing on at all.**

Eight Groovy repositories were tried before one could be used, and the failures were not
incidental:

| Repository | Outcome once `javaAnnotationProcessing` is on |
|---|---|
| `jenkinsci/JenkinsPipelineUnit` | stub rejected: `'_' is a keyword, and may not be used as an identifier` |
| `int128/gradle-ssh-plugin` | stub rejected: `illegal combination of modifiers: public and private` |
| `longwa/build-test-data` | stub rejected: `method does not override or implement a method from a supertype` |
| `jk1/Gradle-License-Report` | toolchain pinned to Java 17: `class file version 65.0` |
| `allegro/axion-release-plugin` | toolchain pinned to Java 17: same |
| `jwagenleitner/groovy-wslite` | wrapper too old: `Could not determine java version from '21.0.9'` |
| `gpc/grails-postgresql-extensions` | **compiles** |
| `bentsherman/nf-boost` | **compiles** — the member |

Three of the eight compile perfectly well on their own and break the moment stubs are generated,
each for a different reason. That is a property of groovyc's stub generator, not of VibeTags, and
a Groovy user hitting it sees a compile error in a file they did not write, in a directory named
`build/tmp/compileGroovy/groovy-java-stubs`. Worth knowing before recommending the switch.

**A Gradle toolchain below 21 fails in a way that names nothing useful.**

`java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }` runs javac from a JDK 17,
which cannot load the processor. README has always said "Java 21 or higher"; what it did not say
is that a toolchain pin overrides the JDK you launched Gradle with, so a developer on JDK 21 gets
`class file version 65.0` and no mention of VibeTags or of toolchains. Now in USAGE.md.

**kapt reports `vibetags.root` as unrecognised.**

`warning: The following options were not recognized by any processor: '[vibetags.root,
kapt.kotlin.generated]'` — for an option `AIGuardrailProcessor` declares in `@SupportedOptions`
and demonstrably receives, since it prints the resolved root from the same task.

Attributed on 2026-08-31 (#493) by an `-i` treatment run of this member: the warning is emitted
by `:kaptKotlin` — not stub generation — two lines after the processor's own
`Note: VibeTags: Root resolved: …` from the very option being reported. kapt forwards its option
map to its embedded javac without registering any processor's supported-option set, which is why
`kapt.kotlin.generated`, kapt's own option, is flagged in the same message: even perfect
delegation on our side would leave the warning firing for kapt's half. The version hypothesis is
dead — `examples/kotlin` is silent with its Kotlin/kapt pinned to this member's 2.3.10 as well
as at 2.4.10 — so surfacing is a property of the consumer's Gradle/kapt logging configuration.
Allow-listed as attributed kapt bookkeeping; USAGE.md's kapt section says the same to consumers.

**And one in the harness, which is the reason J2 can be trusted at all.**

The diagnostic counter could not see the diagnostic it existed for. It required a colon before the
word — `: warning:` — and javac emits its summary diagnostics with no file prefix at all. So the
kapt warning above was raised by the treatment, absent from the control, and counted `0` against
`0`. A parity check blind to an entire class of diagnostic is worse than no parity check, because
it is reported as a pass.

### Adding a member

1. It must build on JDK 21 with its own wrapper, at the SHA you pin, **and** with annotation
   processing switched on. Those are two different tests for Groovy, and the second is the one
   that eliminates most candidates.
2. Prefer a library over a build plugin. Plugins pin old toolchains for consumer compatibility,
   which is what disqualified two Groovy candidates outright.
3. Add a row to `repos-jvm.tsv` with the SHA, never a branch, and say in `why` what it covers that
   the others do not.
4. If it cannot render some level, name the markers in `excluded_markers()` **with the evidence**,
   and write the limitation in USAGE.md in the same change. An exclusion without a reason is how a
   real defect gets absorbed into the expected set.

## Adding a Java repo

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
