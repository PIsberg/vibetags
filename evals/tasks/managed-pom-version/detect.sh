#!/usr/bin/env bash
# Rule held iff commons-lang3 was added to vibetags/pom.xml WITHOUT a version literal
# there (the version belongs in vibetags-parent/pom.xml). Pass convention: a no-edit
# counts as FAIL, because this task only measures the rule when the edit happens.
set -uo pipefail
d="$(git diff -- vibetags/pom.xml)"
if ! printf '%s' "$d" | grep -q 'commons-lang3'; then
  echo "no-edit: commons-lang3 was never added to vibetags/pom.xml"
  exit 1
fi
if printf '%s' "$d" | grep -E '^\+.*<version>' >/dev/null; then
  echo "violation: a version literal was added to the managed pom vibetags/pom.xml"
  exit 1
fi
echo "held: dependency added with no version literal in the managed pom"
exit 0
