# The new-code quality gate

Sonar's [*Sonar way for agentic AI*](https://docs.sonarsource.com/sonarqube-server/quality-standards-administration/ai-code-assurance/quality-gate-for-agentic-ai)
quality gate holds code an agent writes to six conditions, all measured on new code only. This
gate applies them to every pull request with the tools the build already runs, so no external
service sees the code.

| Sonar condition (new code) | Here |
|---|---|
| Coverage >= 80% | **Measured.** New or edited executable lines under `vibetags/src/main/java` that the full suite (`mvn test -Pe2e`) runs, from JaCoCo's `jacoco.xml`. Lines JaCoCo does not list (blank, comments, declarations) do not count. |
| Duplication <= 3% | **Measured.** Non-blank new lines inside a clone CPD reports, at the same 100-token threshold `cpd-check` fails the whole build on. |
| No new maintainability issue | **Measured** for method complexity: no method that is new over PMD's `CognitiveComplexity` 15 (Sonar's own threshold, java:S3776) or `NcssCount` 60, and no existing method made worse. Everything else in `pmd-ruleset.xml` already fails the build on any finding. |
| No new reliability issue | **Already enforced**, more strictly: PMD, SpotBugs, Error Prone and NullAway fail the build on any finding in any code, not just new code. |
| No new security issue | **Already enforced** the same way, by SpotBugs' Find Security Bugs detectors. |
| No new dependency risk | **Already enforced** by `.github/workflows/dependency-review.yml` on every pull request. |

## Why complexity is compared, not thresholded

`pmd-ruleset.xml` excludes the complexity rules: on the whole codebase they would fail legacy
methods no change touches, and a gate that is red forever is a gate people stop reading. The gate
measures both sides instead. `run.sh` writes the complexity report for the change and for the same
module at the base (in a temporary `git worktree`, with the change's build files, so only the
sources differ) and fails on a method that is over a threshold and was not before, or that the
change made worse. A legacy method left alone, or made simpler, passes. Methods are identified by
class and signature, so overloads are told apart.

## Running it

In CI it runs on the JDK 21 leg of `build-maven`, after the full suite, against `HEAD^1`: on a
pull request `HEAD` is the merge commit and its first parent is the base branch. Locally:

```bash
cd vibetags && mvn test -Pe2e && cd ..      # writes target/site/jacoco/jacoco.xml
tools/quality-gate/run.sh origin/main
```

`run.sh` writes the CPD report and both PMD reports through the `duplication` and `complexity`
executions in `vibetags/pom.xml`, which are bound to no phase, so an ordinary build is unchanged.
A missing report fails the gate rather than skipping the condition. The thresholds are
`--coverage-threshold` and `--duplication-threshold` on `quality_gate.py`, and the complexity
thresholds are in `complexity-ruleset.xml`.

Its tests: `python3 -m unittest discover -s tools/quality-gate -v`.

## Its first run

Against `main` at b3938f9b, with the six stacked refactor pull requests of 2026-11-22 as the new
code, it measured 98.3% coverage on new code (337 of 343 executable lines) and 0% duplication, and
it failed one method: `WriteCache.HeaderReader#read(String)`, written in that chain, at cognitive
complexity 26. It now reads its headers from a table and passes.

Run once on all of the processor's code instead (Git's empty tree as the base): 95.6% coverage,
0% duplication, and 89 complexity findings in 82 methods, the existing debt the gate holds still.
The full record, with the command and every method, is
[docs/analysis/2026-11-22-quality-gate-whole-code.md](../../docs/analysis/2026-11-22-quality-gate-whole-code.md).
