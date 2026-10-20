#!/usr/bin/env bash
# The consumer proof: runs the consumer suite under all three variants with the same model, trial
# count and tasks, and writes one table answering "does VibeTags change what an agent does?".
#
#   binding power      full minus baseline: what VibeTags as a whole buys
#   generated share    full minus annotations-only: what the generated files add over an
#                      annotation the agent may or may not open
#
# Everything the harness accepts passes through (EVAL_MODEL, ENGINE, TRIALS, TASKS, MAX_TURNS).
# Run TRIALS=10 before quoting a number; consumer/README.md says why.
#
# Usage: bash evals/consumer/run-proof.sh            (writes $PROOF_DIR/REPORT.md)
# Exit status: 0 when all three variants ran, 2 when the harness could not run one. A variant
# below its floors is a finding for the report, not a failure of the run.

set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
evals="$(cd "$here/.." && pwd)"
PROOF_DIR="${PROOF_DIR:-$evals/results/consumer-proof-$(date +%Y%m%d-%H%M%S)}"
VARIANTS="baseline annotations-only full"
mkdir -p "$PROOF_DIR"

for v in $VARIANTS; do
  SUITE=consumer VARIANT="$v" RESULTS_DIR="$PROOF_DIR/$v" bash "$evals/run-instruction-evals.sh"
  rc=$?
  if [ "$rc" -eq 2 ] || [ ! -f "$PROOF_DIR/$v/summary.csv" ]; then
    echo "error: variant $v did not run (harness exit $rc); no report written" >&2
    exit 2
  fi
done

# pct <variant> <task>: the pass percentage the harness recorded, or "-" when the task did not run.
pct() {
  awk -F, -v t="$2" 'NR > 1 && $2 == t { print $7; found = 1 } END { if (!found) print "-" }' "$PROOF_DIR/$1/summary.csv"
}
cell() {
  awk -F, -v t="$2" 'NR > 1 && $2 == t { printf "%s/%s (%s%%)", $5, $6, $7 }' "$PROOF_DIR/$1/summary.csv"
}
delta() {
  if [ "$1" = "-" ] || [ "$2" = "-" ]; then echo "-"; else echo $(( $1 - $2 )); fi
}

model="$(awk -F, 'NR == 2 { print $4 }' "$PROOF_DIR/full/summary.csv")"
trials="$(awk -F, 'NR == 2 { print $6 }' "$PROOF_DIR/full/summary.csv")"
cli="$(claude --version 2>/dev/null | head -1)"
sha="$(git -C "$evals/.." rev-parse --short HEAD 2>/dev/null)"
report="$PROOF_DIR/REPORT.md"
{
  echo "# Consumer proof"
  echo
  echo "Model \`$model\`, ${trials} trials per task and variant, CLI \`${cli:-unknown}\`, commit \`${sha:-unknown}\`, $(date -u +%Y-%m-%d)."
  echo
  echo "| task | baseline | annotations-only | full | binding power | generated share |"
  echo "|---|---|---|---|---|---|"
  for t in $(awk -F, 'NR > 1 { print $2 }' "$PROOF_DIR/full/summary.csv"); do
    b="$(pct baseline "$t")"; a="$(pct annotations-only "$t")"; f="$(pct full "$t")"
    echo "| $t | $(cell baseline "$t") | $(cell annotations-only "$t") | $(cell full "$t") | $(delta "$f" "$b") pp | $(delta "$f" "$a") pp |"
  done
  echo
  echo "Pass = the guardrail held, as decided by the task's deterministic detector. baseline has no"
  echo "VibeTags; annotations-only keeps the annotations but not the generated block; full is a"
  echo "project as VibeTags leaves it. Trial logs sit beside this file."
} > "$report"
cat "$report"
exit 0
