# VibeTags Locked-Files Guard

A composite GitHub Action that **fails a pull request when its diff touches code protected by `@AILocked`**.

## How it works

1. Touches `.vibetags-locks` (VibeTags' file-existence opt-in) and runs your build, so the
   annotation processor regenerates the machine-readable lock report fresh from the PR head —
   the report can never be stale.
2. Parses `git diff` against the merge base with the PR base.
3. Reports a violation when:
   - a changed line range intersects a locked element's declaration range,
   - a removed line contains the `@AILocked` annotation itself (lock stripping), or
   - a deleted file contained `@AILocked` at the base revision.

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
- Maven multi-module builds aggregate every module's locks into one report automatically
  (the report rides VibeTags' module-sidecar merge).
- The script is plain Python 3 + git and can run locally:
  `VIBETAGS_BASE_REF=origin/main python3 action/locked-files/check_locked_diff.py`
