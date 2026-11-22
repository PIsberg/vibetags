#!/usr/bin/env bash
# The new-code quality gate (tools/quality-gate/README.md).
#
#   tools/quality-gate/run.sh <base-ref>
#
# Run from anywhere in the repository after the processor's full suite has written
# vibetags/target/site/jacoco/jacoco.xml (mvn test -Pe2e). It writes the CPD report and two PMD
# complexity reports, one for the working tree and one for the same module at <base-ref> (in a
# temporary git worktree, so the working tree is never touched), then hands everything to
# quality_gate.py.
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: $0 <base-ref>" >&2
  exit 2
fi
base=$(git rev-parse --verify "$1^{commit}")
repo=$(git rev-parse --show-toplevel)
cd "$repo"

work=$(mktemp -d)
cleanup() {
  git worktree remove --force "$work/base" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# The change.
(cd vibetags && mvn -B -q pmd:pmd@complexity pmd:cpd@duplication)
cp vibetags/target/pmd-complexity/pmd.xml "$work/pmd-head.xml"

# The base: its sources, measured with this tree's build files and ruleset, which the base may
# predate. Only the sources differ between the two reports.
git worktree add --detach --quiet "$work/base" "$base"
cp vibetags-parent/pom.xml "$work/base/vibetags-parent/pom.xml"
cp vibetags/pom.xml "$work/base/vibetags/pom.xml"
mkdir -p "$work/base/tools/quality-gate"
cp tools/quality-gate/complexity-ruleset.xml "$work/base/tools/quality-gate/complexity-ruleset.xml"
(cd "$work/base/vibetags" && mvn -B -q pmd:pmd@complexity)
cp "$work/base/vibetags/target/pmd-complexity/pmd.xml" "$work/pmd-base.xml"

python3 tools/quality-gate/quality_gate.py \
  --base "$base" \
  --repo-root "$repo" \
  --jacoco vibetags/target/site/jacoco/jacoco.xml \
  --cpd vibetags/target/cpd-new-code/cpd.xml \
  --pmd-head "$work/pmd-head.xml" \
  --pmd-base "$work/pmd-base.xml"
