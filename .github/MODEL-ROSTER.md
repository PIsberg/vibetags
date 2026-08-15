# Model roster

The versioned record of which model runs where in this repository, and the ceremony for
changing any of it. A model is part of an enforcer; swapping one silently is the same class
of change as editing a CI workflow without review.

## Routing, by decision density

Work routes on how much judgment a wrong call destroys, not on task size:

| Lane | Model / engine | Pin | Why this lane gets this model |
|---|---|---|---|
| Inquisitor (`inquisitor.yml`) | `claude-opus-5` | Exact ID in the workflow's `claude_args` | Adversarial structural review is the canonical judgment role; a wrong call there cascades into everything the review was supposed to catch. Strongest roster entry, per decision density. |
| Instruction evals (`evals/`) | `claude-opus-5` (`EVAL_MODEL` default) | Runner default + workflow env | Same-model rule: evals measure adherence of the model that writes production code here; a cheaper model's adherence is a different experiment. |
| Copilot review lane (`copilot-review.yml`) | GitHub-managed | Not pinnable | Advisory second opinion on free quota. GitHub routes the underlying model; that unpinnability is documented here as a known property of the lane, and is acceptable exactly because the lane is advisory and skip-on-quota, never a gate. |
| Mechanical CI (build, tests, analysis, diagrams, locks) | none | n/a | Deterministic gates hold still while models move; that separation is the point. |
| Maintainer sessions | maintainer's choice | unpinned by design | Personal harness config, outside the repository's authority. |

## The upgrade ceremony

Re-pinning a model in any lane above is a reviewed commit that edits this file and the
workflow pin together, and the PR that does it must run the golden-prompt replay first:

```bash
TRIALS=10 EVAL_MODEL=<candidate-model-id> bash evals/run-instruction-evals.sh
```

The eval bank is the replay set: frozen rule-shaped tasks with deterministic detectors. The
candidate inherits the pin only if every task still meets its floor; raw output is never
diffed (sampling makes token diffs meaningless), invariants are what must survive the swap.

## Escalation

The cheap lane's stop rule: when a Copilot review and the Inquisitor disagree, or an eval
task's floor result is ambiguous, the resolution is human judgment plus, usually, a new
committed rule; never a silent re-route to a different model.
