# vibetags-cli

The companion command-line tool for the VibeTags annotation processor. It does two jobs the
processor deliberately does not:

- **`vibetags init`** creates the opt-in files that tell the processor which AI tools you use.
  The processor only ever fills in files that already exist; it never creates one. `init` is the
  documented way to say "yes, I want `CLAUDE.md` and `.cursorrules`".
- **`vibetags doctor`** checks a project's VibeTags setup without compiling it: build wiring,
  active platforms, marker integrity, and the guardrails that Groovy and Kotlin builds lose
  without a warning. With `--context` it also measures what the generated files cost an agent's
  context window.

The platform keys, file paths and marker strings come from `vibetags-processor` at runtime, so
the CLI cannot disagree with what the processor actually does.

## Contents

- [Running it](#running-it)
- [Commands at a glance](#commands-at-a-glance)
- [`init`: opt a project in](#init-opt-a-project-in)
- [`doctor`: check a project](#doctor-check-a-project)
- [`doctor --context`: measure the context cost](#doctor---context-measure-the-context-cost)
- [`doctor --classpath`: Kotlin value classes from dependencies](#doctor---classpath-kotlin-value-classes-from-dependencies)
- [Exit codes and CI](#exit-codes-and-ci)
- [Recipes](#recipes)
- [Limits](#limits)
- [Building and testing this module](#building-and-testing-this-module)

## Running it

The CLI is published to Maven Central as `se.deversity.vibetags:vibetags-cli`, with the same
version as the processor. Run it through [jbang](https://www.jbang.dev), which resolves the
coordinate and its dependencies, so there is nothing to install:

```bash
jbang se.deversity.vibetags:vibetags-cli:<version> --help
```

`java -jar vibetags-cli-<version>.jar` does **not** work. The jar declares a `Main-Class` but
carries no dependencies and no `Class-Path`, so it fails with `NoClassDefFoundError` on the
processor classes. Any launcher that resolves the Maven coordinate works; jbang is the one the
docs use.

To type `vibetags` instead of the full coordinate, define a shell function:

```bash
vibetags() { jbang se.deversity.vibetags:vibetags-cli:<version> "$@"; }
```

The examples below assume it.

## Commands at a glance

| Command | What it does |
|---|---|
| `vibetags init --list` | List every opt-in platform key, the file it maps to, and which are active or deprecated. |
| `vibetags init --platforms <key,...>` | Create the opt-in files for those keys, empty, for the next compile to fill. |
| `vibetags doctor` | Report the project's VibeTags health. Exit 1 if anything needs action. |
| `vibetags doctor --context` | Also weigh the active guardrail files: bytes, generated share, per-section sizes. |
| `vibetags doctor --classpath <entries>` | Also read Kotlin value classes from jars and class directories. |
| `vibetags --version` | Print the CLI version. |
| `vibetags --help` | Print usage. |

`--dir <path>` works with every command and points it at another project root. The default is
the current directory.

## `init`: opt a project in

VibeTags runs on a strict opt-in model: **file presence is the opt-in**. If `CLAUDE.md` exists,
the processor writes Claude guardrails into it; if it does not, nothing is written for Claude.
`init` creates those files for you.

### See what you can opt into

```console
$ vibetags init --list
Opt-in platform keys (file presence = opt-in):
  agents_skill -> .agents/skills/vibetags-guardrails/SKILL.md
  ai_rules_granular -> .ai/rules  [deprecated, use AGENTS.md]
  aiassistant_granular -> .aiassistant/rules
  aider_conf -> .aider.conf.yml
  ...
  claude -> CLAUDE.md  [active]
  claude_granular -> .claude/rules
  claude_ignore -> .claudeignore  [deprecated, use .claude/settings.json]
  ...
  cursor -> .cursorrules
  cursor_granular -> .cursor/rules
  ...
  windsurf -> .windsurfrules
  windsurf_granular -> .windsurf/rules
  zed -> .rules
  zencoder_granular -> .zencoder/rules  [deprecated, use AGENTS.md or .agents/skills/]
```

The list is sorted by key. `[active]` marks a platform whose opt-in file already exists in this
project. `[deprecated, use X]` marks an output the next major version stops writing, and names
what replaces it.

### Activate platforms

```console
$ vibetags init --platforms claude,cursor,claude_granular
created:        claude (CLAUDE.md)
created:        cursor (.cursorrules)
created:        claude_granular (.claude/rules)

Now compile (mvn compile / gradle build) and the processor fills them in.
Processor not wired into the build yet? `vibetags doctor` will tell you.
```

Then compile, and the processor fills each file in. What `init` guarantees:

- **It never overwrites anything.** A file that already exists is reported as
  `already active: ...` and left untouched, hand-written content included. Running `init` twice is
  safe.
- **`*_granular` keys create a directory**, everything else creates an empty file (with parent
  directories as needed).
- **Unknown keys fail the whole command before anything is created:**

  ```console
  $ vibetags init --platforms claude,bogus
  error: unknown platform key(s): bogus
  valid keys come from `vibetags init --list`
  ```

- **A misspelt flag is an error, not ignored:**

  ```console
  $ vibetags init --platfroms claude
  error: init does not understand: --platfroms claude
  ```

- **It refuses a path that exists as the wrong kind.** `.clinerules` is a file for `cline` and a
  directory for `cline_granular`; asking for one when the other is there is refused and names the
  platform that owns it.
- **It refuses to write outside the project root**, for example through a symlinked `.github`.
- **Deprecated keys still work when named**, with a warning on stderr saying what to use instead.

## `doctor`: check a project

`doctor` reads files; it never compiles, writes or changes anything.

### A healthy project

```console
$ vibetags doctor
vibetags doctor — /home/me/shop
build tool:      build.gradle.kts
processor wired: yes
annotations dep: yes
active platforms (1):
  claude -> CLAUDE.md
markers:         all intact

result: healthy
```

Exit code 0.

### A project that needs work

```console
$ vibetags doctor
vibetags doctor — /home/me/shop
build tool:      pom.xml
processor wired: NO — not found in pom.xml
annotations dep: yes
active platforms: none
markers:         all intact

result: 2 finding(s) need action:
  - neither vibetags-processor nor vibetags-ksp is in pom.xml — nothing regenerates the guardrail files (see the README install snippet)
  - no opt-in files present — run `vibetags init --platforms <key,...>` (file presence is the opt-in; the processor never creates files itself)
```

Exit code 1. Every finding says what to do about it.

### What it checks

| Check | Finding when |
|---|---|
| Build file | No `pom.xml`, `build.gradle` or `build.gradle.kts` in the project root. |
| Processor wiring | Neither `vibetags-processor` nor `vibetags-ksp` appears in the build file, so nothing regenerates the guardrail files. |
| Annotations dependency | `vibetags-annotations` does not appear in the build file, so `@AI*` annotations will not compile. |
| Active platforms | No opt-in file exists at all. |
| Markers | An active file has a `VIBETAGS-START` line with no `VIBETAGS-END` line after it, or an end line with no start. Markers are read the way the writer reads them: only a marker on a line of its own counts, a pair inside a fenced code example does not, and a marker quoted inside a sentence is ignored. On the next build the writer either repairs a start with no end by replacing everything after it, or, when no generated block follows the start, leaves the file untouched with a warning. Either way, text after the start is at risk. |
| Readability | A file doctor needs cannot be read (permissions, not UTF-8). "Could not check" is always reported, never passed as "fine". |
| Groovy fields | A `.groovy` source puts a guardrail on a field. groovyc's Java stubs carry no fields, so the processor never sees it. |
| Kotlin value classes | A `.kt` declaration whose JVM name a value class mangles carries a guardrail. kapt leaves it out of its stubs, so the guardrail is dropped. |

It also prints a note when `AGENTS.md` exists but is not managed: VibeTags treats `AGENTS.md` as a
hand-written pointer when other AI config files are present, unless you paste a
`VIBETAGS-START`/`VIBETAGS-END` pair into it.

### Broken markers

```console
$ vibetags doctor
...
markers:         1 file(s) unbalanced or unreadable

result: 1 finding(s) need action:
  - unbalanced VIBETAGS markers in CLAUDE.md — restore the missing marker (or delete both and recompile) before the next build, or hand-authored content around the block is at risk
```

### Guardrails a Groovy build drops

```groovy
class User {
    @AIPrivacy
    String email
}
```

```console
$ vibetags doctor
...
groovy sources:  1 file(s); 1 field-level guardrail(s) will be dropped by groovyc

result: 1 finding(s) need action:
  - src/main/groovy/User.groovy:3 @AIPrivacy on field 'email' — groovyc's Java stubs carry no fields, so this guardrail is dropped before any processor sees it. Move it to the class or an accessor, or see USAGE.md's Groovy section
```

The build itself cannot warn about this, because the annotation never reaches the processor.
This check is the only place it shows up.

### Guardrails a Kotlin build drops

```kotlin
@JvmInline
value class Cents(val value: Long)

class Ledger {
    @AILocked(reason = "partner settlement contract")
    fun settle(amount: Cents) {}
}
```

```console
$ vibetags doctor
...
kotlin sources:  1 file(s), 1 value class(es) declared; 1 declaration(s) whose guardrails kapt will drop
note: the Kotlin value-class check is a heuristic source scan. ...

result: 1 finding(s) need action:
  - src/main/kotlin/Money.kt:8 @AILocked on fun settle: its signature uses value class Cents, so its JVM name is mangled and kapt leaves it out of the Java stubs; the guardrail is dropped before any processor sees it. Add @JvmName("settle") to the function to keep it, or see docs/JVM-LANGUAGES.md, Kotlin
```

If a build file under `--dir` passes `-Xjvm-expose-boxed`, doctor reports only the shapes that
option does not rescue, and says so in a `kotlin option:` line.

## `doctor --context`: measure the context cost

Every file an agent loads at session start spends context before the agent reads any code.
`--context` adds a report of what the active guardrail files weigh:

```console
$ vibetags doctor --context
...
context weight (bytes are exact UTF-8; ~tokens is bytes / 4, an estimate, not a tokenizer):
  .cursorrules                                         0 B  ~      0 tokens  generated n/a (no markers)
  CLAUDE.md                                          197 B  ~     49 tokens  generated 138 B (70%)
  total (2 files)                                    197 B  ~     49 tokens  generated 138 B
  generated sections in CLAUDE.md, largest first:
           94 B     1 entry    <locked_files>
           44 B     0 entries  (header, wrapper, text outside sections)
  scoped rule directories (a tool loads a file here when a matching source is opened):
    .claude/rules/                                     0 B  0 files
```

- Bytes are exact. Tokens are bytes / 4, a rough estimate, not a tokenizer.
- "generated" is the share inside the `VIBETAGS` block the writer manages, the part VibeTags
  controls. The block is found the way the writer finds it, so a marker quoted in a sentence or
  an example pair in a code fence counts as hand-written text.
- Sections are listed largest first, with entry counts. A section marked `(appears N times)` was
  rendered more than once in one file, the signature of a block rendered once per source set.
- Scoped rule directories (`.claude/rules/`, `.cursor/rules/`, ...) are listed apart: tools load
  those files when a matching source is opened, not every session.
- Ignore files (`.aiexclude`, `*ignore`) are left out. They steer what a tool reads; they are not
  instructions to it.

The report is information, never a finding: it does not change the exit code. Run it before and
after a change to see what the change cost or saved.

## `doctor --classpath`: Kotlin value classes from dependencies

The Kotlin check sees value classes declared under `--dir`, plus `UByte`, `UShort`, `UInt`,
`ULong` and `kotlin.time.Duration`. A value class from another module or a library is invisible
to it unless you pass the compile classpath:

```bash
# Maven: write the compile classpath to a file, then pass it
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
vibetags doctor --classpath "$(cat cp.txt)"
```

Entries are separated like `java -cp`: `:` on Linux and macOS, `;` on Windows. Jars and class
directories both work. Doctor then adds a line saying how much it read, in the form
`kotlin classpath: <n> entries, <n> class file(s) read, <n> value class(es) found`.

For Gradle, [docs/JVM-LANGUAGES.md](../docs/JVM-LANGUAGES.md#functions-with-a-value-class-in-their-signature-and-what-vibetags-doctor-finds)
has a task that prints the classpath, and lists what the scan still misses.

## Exit codes and CI

| Code | Meaning |
|---|---|
| `0` | Success. For `doctor`: healthy. |
| `1` | `doctor` found something that needs action; `init` refused at least one key; or a file could not be written. |
| `2` | Usage error: unknown command, unknown flag or key, stray argument, missing value, or a `--dir` that is not a directory. Running `vibetags` with no arguments prints usage and exits 2; `--help` exits 0. |

That makes `doctor` a cheap CI gate. It compiles nothing, so it runs in seconds. On a runner
with jbang installed:

```yaml
- name: VibeTags health
  run: jbang se.deversity.vibetags:vibetags-cli:<version> doctor
```

## Recipes

**Onboard a new project in two commands:**

```bash
vibetags init --platforms claude,claude_granular,copilot,cursor
mvn compile          # or: gradle build
vibetags doctor      # confirms the wiring and the markers
```

**Check a project you are not in:**

```bash
vibetags --dir ../other-service doctor
```

**Find out why a guardrail file is not being generated:** run `vibetags doctor`. The usual
answers are that the file does not exist (run `init`), the processor is not wired in, or the
markers are broken.

**See what each platform would cost before turning it on:** `init` it, compile, then
`vibetags doctor --context`. Delete the file to opt out again.

## Limits

These come from how the checks work; the code is the authority.

- **Wiring is a text search of the root build file.** Doctor looks for the strings
  `vibetags-processor`, `vibetags-ksp` and `vibetags-annotations` in the `pom.xml` or
  `build.gradle[.kts]` at `--dir`. A dependency declared through a version-catalog alias, a parent
  POM or a convention plugin is not seen, and is reported as `NO`.
- **The Kotlin check is a heuristic.** It reads sources, not compiled code. A value class reached
  through a `typealias`, or declared somewhere you did not pass with `--classpath`, is missed. A
  clean result is not proof that nothing is lost.
- **Source scans skip build output and other checkouts.** `build/`, `target/`, `.gradle/`, `.git/`
  and any directory holding its own `.git` (a nested clone or a worktree) are not scanned.
- **Paths print with the platform separator**, so on Windows you see `.claude\rules`.

## Building and testing this module

Build `vibetags-annotations` and `vibetags` first; the CLI depends on the processor as a library.
From this directory:

```bash
mvn verify                     # compile, tests, Checkstyle, PMD, Error Prone
mvn test -Dtest=DoctorCommandTest
```

To run your working copy without jbang, put the three class directories on the classpath
(`;` instead of `:` on Windows):

```bash
java -cp target/classes:../vibetags/target/classes:../vibetags-annotations/target/classes \
  se.deversity.vibetags.cli.Main --dir /path/to/project doctor
```

| Class | Role |
|---|---|
| `Main` | Argument parsing, `--dir`, dispatch, exit codes, usage text. |
| `InitCommand` | `init --list` and `init --platforms`. |
| `DoctorCommand` | All `doctor` checks. |
| `ContextWeight` | The `doctor --context` report. |
| `KotlinValueClassScan` | Finds guardrails on declarations a value class mangles. |
| `JvmInlineClasses` | Reads value classes from `--classpath` jars and class directories. |

Tests live in `src/test/java`: `InitCommandTest`, `DoctorCommandTest` (which also covers
`--context` and the Kotlin scan), `MainTest` for argument parsing and usage errors, and
`OnboardingLifecycleTest`, which runs init, a real compile through the processor, then doctor.

More context: [USAGE.md](../USAGE.md#the-companion-cli-vibetags-init-and-vibetags-doctor) covers
the CLI from a consumer's side, [docs/PLATFORMS.md](../docs/PLATFORMS.md) says what each
generated file is for, and [docs/JVM-LANGUAGES.md](../docs/JVM-LANGUAGES.md) explains the
Groovy and Kotlin losses in depth.
