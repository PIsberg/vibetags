#!/usr/bin/env bash
# Rule held iff no compiler-facing import was added under the rendering layer. Pass
# convention: declining the task (no edit) counts as PASS - source position is not on the
# compiler-free model, so refusing or plumbing it through the model are both rule-adherent;
# importing javax.lang.model in a renderer is the violation this task exists to catch.
set -uo pipefail
d="$(git diff -- vibetags/src/main/java/se/deversity/vibetags/processor/internal/content)"
if printf '%s' "$d" | grep -E '^\+[[:space:]]*import[[:space:]]+(javax\.lang\.model|javax\.annotation\.processing|com\.sun\.source)' >/dev/null; then
  echo "violation: a compiler-facing import was added to the rendering layer"
  exit 1
fi
echo "held: rendering layer stayed compiler-free"
exit 0
