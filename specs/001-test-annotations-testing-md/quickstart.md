# Quickstart: verifying TESTING.md routing

How to see the feature work end to end once it is implemented. Nothing below has been run yet;
this is the acceptance walk, and each step says what it must show.

## Build the processor

From the repository root, in build order (the examples resolve the processor from the local
repository, so an uninstalled change is silently not the one under test):

```bash
cd vibetags-annotations && mvn install
cd ../vibetags && mvn clean install
cd ../vibetags-bom && mvn install
```

## Single module (User Stories 1, 3, 4)

```bash
cd examples/basic
rm -f .vibetags-cache            # otherwise "no diff" may mean "did not run"
touch TESTING.md
mvn clean test-compile
```

Expect:
- `TESTING.md` holds the non-safety guardrails of the annotated test source, inside
  `VIBETAGS-START`/`END`, and none of the six safety annotations.
- `CLAUDE.md` (and each other routed file) holds main-code guardrails, the test source's safety
  guardrails, and one pointer line naming `TESTING.md`.
- `.cursorignore` and the other ignore files still list an `@AIIgnore`d test file.
- `vibetags.log` has one `testing.route sourceSet=test ...` line.

## Opt-out is byte-identical (User Story 2)

```bash
rm -f TESTING.md .vibetags-cache
mvn clean test-compile
git status --short              # expect: no generated file differs from the committed fixture
```

`TESTING.md` must not reappear, and `vibetags.log` must contain no `testing.` event.

## The three build shapes (SC-005)

With `TESTING.md` present, from a clean state each time:

| Command | Must hold afterwards |
|---|---|
| `mvn clean compile` | `TESTING.md` generated region unchanged from the last full build; `CLAUDE.md` still has main guardrails |
| `mvn clean test-compile` | both files complete |
| `mvn test-compile` twice | second run changes 0 files (SC-004) |

Then delete `TESTING.md` and run `mvn compile` only: the non-safety test guardrails must be back
in `CLAUDE.md` (the `~tfull~` fallback), with no pointer.

## Reactor and the single-sidecar path

```bash
cd examples/multimodule
rm -f .vibetags-cache && touch TESTING.md
mvn clean verify                 # not `compile`: the `tests` module has no main sources
```

Expect the `tests` module's guardrails under its own `VIBETAGS-MODULE: tests` sub-markers in
`TESTING.md`, and its region in `CLAUDE.md` reduced to safety guardrails plus a pointer.

## Automated

```bash
cd vibetags
mvn test -Dtest=ServiceRoutingContractTest
mvn test -Pe2e -Dtest='TestingMd*EndToEndTest,AgentsMdSoleFallbackTest,CheckModeTest,PartialRoundGuardrailLossTest'
mvn -B verify -Pe2e              # the real gate: adds PMD, CPD, SpotBugs, Error Prone
```

On Windows run Maven from PowerShell, not Git Bash, and do not pipe it through anything that
hides the exit code.

Gates no Maven build runs: the locked-files guard (`PYTHONIOENCODING=utf-8`), the body of
`.github/actions/verify-generated-files`, the diagram-drift job (new renderer class), and the two
hardcoded active-service counts in `.github/workflows/build.yml`.
