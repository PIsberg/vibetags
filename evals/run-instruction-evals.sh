#!/usr/bin/env bash
# Instruction evals: measure whether this repo's standing instructions actually bind.
#
# Each task under evals/tasks/<name>/ freezes one rule-shaped situation: a prompt that
# tempts an agent to violate a committed rule, and a deterministic detector that answers
# yes/no over the tree the agent leaves behind. The runner executes each task TRIALS times
# in a disposable git worktree, headlessly, and compares the pass rate against the task's
# floor. evals/README.md explains the method and its limits; read it before trusting a
# number from here.
#
# Requirements: the `claude` CLI on PATH and ANTHROPIC_API_KEY exported (runs are hermetic
# via CLAUDE_CONFIG_DIR, so stored login credentials are deliberately not visible).
#
# Environment knobs:
#   TRIALS=3            trials per task (10 for decisions; 3 is a smoke run)
#   EVAL_MODEL=...      model id; keep it the model that writes production code here
#   ENGINE=claude       claude | copilot. The copilot engine drives the GitHub Copilot CLI
#                       (free-tier quota, GitHub-authenticated, agentic) so the bank can run
#                       with no Anthropic key. Missing CLI or exhausted quota exits 2 or
#                       shows as HARNESS-ERROR trials - visible skips, never false passes.
#                       Copilot trials use the user's Copilot config (not hermetic) and a
#                       GitHub-managed model; see the same-model note in evals/README.md.
#   VARIANT=full        full (repo as committed) | baseline (instruction files removed)
#   TASKS="t1 t2"       space-separated task dir names; empty means all
#   MAX_TURNS=25        per-trial turn cap
#   RESULTS_DIR=...     where trial logs land (default evals/results/<timestamp>/)
#
# Exit status: 0 when every task met its floor, 1 otherwise, 2 on harness misconfiguration.
# No pipes stand between a command and the place its exit code is read.

set -uo pipefail

EVALS_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$EVALS_DIR/.." && pwd)"
TRIALS="${TRIALS:-3}"
MODEL="${EVAL_MODEL:-claude-opus-5}"
VARIANT="${VARIANT:-full}"
TASK_FILTER="${TASKS:-}"
MAX_TURNS="${MAX_TURNS:-25}"
RESULTS_DIR="${RESULTS_DIR:-$EVALS_DIR/results/$(date +%Y%m%d-%H%M%S)}"

ENGINE="${ENGINE:-claude}"
case "$ENGINE" in
  claude)
    command -v claude >/dev/null 2>&1 || { echo "error: claude CLI is not on PATH" >&2; exit 2; }
    [ -n "${ANTHROPIC_API_KEY:-}" ] || { echo "error: ANTHROPIC_API_KEY is not set (hermetic runs cannot use stored logins)" >&2; exit 2; }
    ;;
  copilot)
    command -v copilot >/dev/null 2>&1 || { echo "error: ENGINE=copilot but the Copilot CLI is not on PATH (npm install -g @github/copilot). A missing engine is a skip, not a pass." >&2; exit 2; }
    MODEL="github-copilot-cli"
    ;;
  *)
    echo "error: unknown ENGINE '$ENGINE' (claude|copilot)" >&2
    exit 2
    ;;
esac

# Hermetic trials: an empty config dir keeps the user's global CLAUDE.md, skills and
# agents out of the experiment; --strict-mcp-config keeps MCP servers out. The variable
# under measurement is this repository's committed instruction stack, nothing else.
CLAUDE_CONFIG_DIR="$(mktemp -d)"
export CLAUDE_CONFIG_DIR

mkdir -p "$RESULTS_DIR"
echo "instruction evals: engine=$ENGINE model=$MODEL variant=$VARIANT trials=$TRIALS results=$RESULTS_DIR"

below_floor=0
ran_any=0
summary_file="$RESULTS_DIR/summary.csv"
echo "task,variant,model,passes,trials,pass_pct,floor_pct,verdict" > "$summary_file"

for taskdir in "$EVALS_DIR"/tasks/*/; do
  name="$(basename "$taskdir")"
  if [ -n "$TASK_FILTER" ]; then
    case " $TASK_FILTER " in *" $name "*) ;; *) continue ;; esac
  fi
  FLOOR_PCT=66
  # shellcheck disable=SC1091
  . "$taskdir/task.env"
  prompt="$(cat "$taskdir/prompt.txt")"
  ran_any=1
  passes=0
  echo "== $name (floor ${FLOOR_PCT}%)"

  trial=1
  while [ "$trial" -le "$TRIALS" ]; do
    wt_parent="$(mktemp -d)"
    wt="$wt_parent/wt"
    git -C "$REPO_ROOT" worktree add --detach --quiet "$wt" HEAD
    if [ "$VARIANT" = "baseline" ]; then
      rm -rf "$wt/CLAUDE.md" "$wt/AGENTS.md" "$wt/GEMINI.md" "$wt/.claude"
    fi

    out="$RESULTS_DIR/$name-trial$trial.json"
    err="$RESULTS_DIR/$name-trial$trial.err"
    det="$RESULTS_DIR/$name-trial$trial.detect"
    if [ "$ENGINE" = "claude" ]; then
      (
        cd "$wt" && claude -p "$prompt" \
          --model "$MODEL" \
          --max-turns "$MAX_TURNS" \
          --permission-mode acceptEdits \
          --strict-mcp-config \
          --output-format json
      ) >"$out" 2>"$err"
      engine_rc=$?
    else
      # Copilot CLI programmatic mode. Model routing is GitHub's, so EVAL_MODEL and
      # MAX_TURNS do not apply; quota exhaustion or auth failure exits nonzero and the
      # trial is counted HARNESS-ERROR below - a visible skip, never a false pass.
      (
        cd "$wt" && copilot -p "$prompt" --allow-all-tools
      ) >"$out" 2>"$err"
      engine_rc=$?
    fi

    if [ "$engine_rc" -ne 0 ]; then
      echo "   trial $trial: HARNESS-ERROR ($ENGINE exited $engine_rc; see $(basename "$err")) - counted as fail"
    else
      ( cd "$wt" && bash "$taskdir/detect.sh" ) >"$det" 2>&1
      detect_rc=$?
      if [ "$detect_rc" -eq 0 ]; then
        passes=$((passes + 1))
        echo "   trial $trial: PASS"
      else
        echo "   trial $trial: FAIL ($(head -1 "$det" 2>/dev/null || echo 'no detector output'))"
      fi
    fi

    git -C "$REPO_ROOT" worktree remove --force "$wt" >/dev/null 2>&1 || true
    rm -rf "$wt_parent"
    trial=$((trial + 1))
  done

  pct=$((passes * 100 / TRIALS))
  verdict="OK"
  if [ "$pct" -lt "$FLOOR_PCT" ]; then
    verdict="BELOW_FLOOR"
    below_floor=$((below_floor + 1))
  fi
  echo "   $name: $passes/$TRIALS passed (${pct}% against floor ${FLOOR_PCT}%): $verdict"
  echo "$name,$VARIANT,$MODEL,$passes,$TRIALS,$pct,$FLOOR_PCT,$verdict" >> "$summary_file"
done

if [ "$ran_any" -eq 0 ]; then
  echo "error: no tasks matched (TASKS='$TASK_FILTER')" >&2
  exit 2
fi

echo
echo "summary written to $summary_file"
if [ "$below_floor" -gt 0 ]; then
  echo "RESULT: $below_floor task(s) below floor. A rule below its floor gets rewritten, promoted to an enforcing gate, or deleted - in that order (evals/README.md)."
  exit 1
fi
echo "RESULT: all tasks met their floors."
exit 0
