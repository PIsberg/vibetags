# VibeTags Locked-Files Guard

A composite GitHub Action that **fails a pull request when its diff touches code protected by `@AILocked`**.

## How it works

1. Touches `.vibetags-locks` (VibeTags' file-existence opt-in) and runs your build, so the
   annotation processor regenerates the machine-readable lock report fresh from the PR head —
   the report can never be stale.
2. Lists the changed files against the merge base with the PR base (`git diff --raw -z`, so
   every path is read verbatim) and diffs each relevant file on its own.
3. Reports a violation when:
   - a changed line range intersects a locked element's declaration range,
   - a removed line in a source file (`.java`, `.kt`, `.kts`, `.groovy`) contains the
     `@AILocked` annotation itself (lock stripping) -- generated guardrail files and docs
     merely mention the annotation and reflow on every regeneration, so they are exempt,
   - a deleted source file contained `@AILocked` at the base revision (a source renamed to
     another extension counts as deleted), or
   - a submodule holding a locked element changes.

   A change the guard cannot read fails the check rather than passing it.

The lock-stripping and deleted-file checks read each Java, Kotlin or Groovy source as it was at
the base revision and lex it there, so `@AILocked` text inside a string literal, text block, char
literal or comment is not an annotation: a test that builds fixture source in strings can be edited
freely. A real annotation still counts however the compiler allows it to be written:

- **Java:** spaced from the `@`, qualified, split by a comment, or spelled with a unicode escape.
- **Kotlin:** also with a use-site target (`@field:AILocked`), in a `@[...]` group, backtick-quoted,
  inside a `${...}` string template, under a name that an `import ... as` in the same file gives
  it, or under a `typealias` declared in any Kotlin source. Any other code token naming the lock or
  an alias of it, outside an import, counts too.
- **Groovy:** also inside a `${...}` string template, under an `import ... as` name, or under an
  `@AnnotationCollector` type, declared in any Groovy source, that collects the lock.

When the guard cannot tell code from text it fails rather than passes: a source that does not lex
(an unterminated or unbalanced literal, comment, template or brace) keeps a substring match:
`@AILocked` in a Java line, and in a Kotlin or Groovy line an `@` together with the lock's name or
an alias anywhere on it, which a use-site target and a qualified name also satisfy. So does a Kotlin source with a `$` outside a string, which is how a multi-dollar `$$"..."`
string starts, and a Groovy source with a `/` in code that does not start a comment, because a
slashy string (`/.../`, `$/.../$`) cannot be told from a division without parsing: a Groovy file
that divides gets the substring match. No directory is exempt, test sources included, because javac
runs the processor over test sources too.

### Aliases declared in another file

A Kotlin `typealias` and a Groovy `@AnnotationCollector` are declared once and used anywhere, so the
changed file alone cannot say that `@Frozen` is a lock. The processor does see such a lock, because
kapt and groovyc expand the alias into the stub it compiles, but the report's ranges point into that
stub, so no range check ever matches the source: the lock-stripping check is what catches a removed
use ([measured](../../docs/JVM-LANGUAGES.md#a-lock-written-through-an-alias)).

When the diff changes a Kotlin or Groovy source, the guard first reads every `.kt`, `.kts` and
`.groovy` file at the base revision, with one `git ls-tree -r -z` and one `git cat-file --batch`,
lexes the ones that contain `typealias` or `AnnotationCollector`, and collects the names that alias
the lock: a `typealias` to `AILocked` (qualified, nested in a class, with type parameters, or naming
another alias), and a collector type whose annotations include `@AILocked` or list `AILocked`
(including one applied under an import alias, and one collecting another collector). Aliases of
aliases are followed across files until nothing grows. Each alias then counts as the lock in every
source of its language, whichever package declares it. The guard does not resolve imports, so a
same-named class elsewhere makes a removal fail rather than pass.

A source that may declare an alias but does not lex cannot have its alias named: one that contains
`typealias` or `AnnotationCollector`, and also the lock's name or an alias already found. While such
a source exists, removing any annotation line from a source in that language fails, and the message
names it. Narrowing that to the unreadable source's package and its importers would mean reading
the package and imports out of text the lexer has already given up on, so the check fails wide
instead. A Groovy source that divides does not lex, so a collector declared next to a division
switches this on for every Groovy source; a collector in a file of its own does not.

Measured on 8418 Kotlin and Groovy files (16.7 MB) found on the development machine, committed to
one repository: reading them took 0.31 s and collecting aliases 0.28 s, because only the 26 that
contain `typealias` or `AnnotationCollector` were lexed (lexing every file takes 5.6 s). A whole
guard run removing an annotation from one of them took 0.74 to 0.82 s, against 0.15 to 0.24 s
without the pass. A diff that changes no Kotlin or Groovy source skips it.

Violations surface as inline GitHub error annotations on the offending file and line.

## Usage

```yaml
name: Locked files guard
on: pull_request

jobs:
  locked-files:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
        with:
          fetch-depth: 0          # the guard needs history up to the merge base
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: 21
      - uses: PIsberg/vibetags/action/locked-files@v1.3.6
        with:
          build-command: mvn -B -q compile
```

## Inputs

| Input | Default | Description |
|---|---|---|
| `base-ref` | PR base SHA | Ref or SHA to diff against |
| `build-command` | `mvn -B -q compile` | Command that compiles the project (regenerates `.vibetags-locks`) |
| `working-directory` | `.` | Directory to build and check from |
| `warn-only` | `false` | Emit warnings instead of failing the job |

If no `.vibetags-locks` report is found under `working-directory`, the job fails (or warns, with
`warn-only`). The report is opted in by committing the file at the project root, so its absence
means the action is looking in the wrong place or the project never opted in; a green check that
guarded nothing is the one outcome the action must not produce.

## Report format

`.vibetags-locks` is JSON Lines wrapped in `# VIBETAGS` hash markers. The first JSON record
declares the report's format version — consumers should skip records whose `type` they do not
recognise and may reject reports with a `version` they do not support:

```
{"type":"format","version":1}
{"type":"locked","element":"com.example.Foo.bar()","kind":"METHOD","file":"src/main/java/com/example/Foo.java","startLine":12,"endLine":18,"reason":"..."}
```

## Notes

- Line ranges come from the javac Compiler Tree API. Under non-javac compilers (e.g. ECJ)
  the report has no line info and the guard falls back to file-level matching.

## How a lock's file is matched

A `.vibetags-locks` records paths relative to **its own VibeTags root**, not to the repository.
The guard resolves each recorded path against the directory of the report that declared it, then
compares repo-relative paths exactly.

That matters in a repository with more than one project in it. The guard used to accept either
path as a suffix of the other, so two reactors with a module at the same relative path aliased
each other: one example's locks flagged another example's diff, measured at nine false violations.

## Locks the diff introduces are exempt

The same reasoning, at element granularity. Adding `@AILocked` to code that already exists is
itself a change to the lines the lock now covers, so the range check flagged the very commit that
introduced the lock, and the file-level exemption above did not help because the file was already
there. Measured: the PR that locked `GuardrailAnnotations.ALL` and
`TransitiveManifest.RESOURCE_PACKAGE` drew one violation each, for doing exactly what this project
tells its users to do.

A lock is compared by `(file, element)` against the base revision's `.vibetags-locks`, read through
`git show`. A lock already present there is enforced as before. Stripping a lock is unaffected:
that is the removed-`@AILocked`-line check, which reads the base side precisely because a stripped
lock is absent from the regenerated report.

## Files the diff creates are exempt

A file that did not exist at the base cannot have had its locked code touched, and the author of
the diff is the one declaring the lock. Flagging it meant a PR could never introduce an
`@AILocked` element, which discouraged adding guardrails to the codebase that ships them.

Renames stay in scope (git reports them as `R`, not `A`), so moving a locked file is still
checked, as is any later PR that edits it.
- Maven multi-module builds aggregate every module's locks into one report automatically
  (the report rides VibeTags' module-sidecar merge).
- The script is plain Python 3 + git and can run locally:
  `VIBETAGS_BASE_REF=origin/main python3 action/locked-files/check_locked_diff.py`
