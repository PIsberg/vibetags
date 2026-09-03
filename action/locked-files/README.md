# VibeTags Locked-Files Guard

A composite GitHub Action that **fails a pull request when its diff touches code protected by `@AILocked`**.

## How it works

1. Touches `.vibetags-locks` (VibeTags' file-existence opt-in) and runs your build, so the
   annotation processor regenerates the machine-readable lock report fresh from the PR head —
   the report can never be stale.
2. Parses `git diff` against the merge base with the PR base.
3. Reports a violation when:
   - a changed line range intersects a locked element's declaration range,
   - a removed line in a source file (`.java`, `.kt`, `.kts`, `.groovy`) contains the
     `@AILocked` annotation itself (lock stripping) -- generated guardrail files and docs
     merely mention the annotation and reflow on every regeneration, so they are exempt, or
   - a deleted source file contained `@AILocked` at the base revision.

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
      - uses: PIsberg/vibetags/action/locked-files@main
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
