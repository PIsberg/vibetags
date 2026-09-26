#!/usr/bin/env bash
# Regenerates this repository's own guardrail files and fails if that changed anything.
#
# One script, two callers, so the local run cannot drift from the CI one:
#   - build.yml, step "Verify VibeTags' Own Guardrails Are Current (self-check)", JDK 21 only
#   - .pre-commit-config.yaml, hook "vibetags-self-check", on any commit touching vibetags/src/,
#     vibetags-annotations/src/ or README.md, and on every `pre-commit run --all-files`
#
# Why it runs locally at all: until this script, the check lived only in build.yml, so drift was
# found one CI round-trip after the push. It was paid for in PRs #687, #793, #797 and #862, each
# time because a line added above a locked method moved its recorded range in .vibetags-locks,
# and in #861 because the regenerated .claude/rules/ changed the line count README.md quotes.
#
# Run from anywhere inside the repository:  sh tools/self-check.sh
# MVN overrides the Maven launcher (default: mvn.cmd when present, since Maven dies under Git
# Bash on Windows, else mvn).
set -euo pipefail

root=$(git rev-parse --show-toplevel)
cd "$root"

mvn=mvn
if command -v mvn.cmd >/dev/null 2>&1; then
  mvn=mvn.cmd
fi
mvn=${MVN:-$mvn}

# CI starts from a clean clone. These two are gitignored and live at the repository root, so
# `mvn clean` leaves them, and a leftover module sidecar merges into the regeneration: the check
# then passes locally and fails in CI on the same commit (#794).
rm -f .vibetags-mod-* .vibetags-cache

# Compare against the tree as it stood before regenerating, not against HEAD, so uncommitted work
# is not reported as drift. Under pre-commit the unstaged half is stashed, so this is the staged
# tree; in CI it is the checkout.
before=$(mktemp)
after=$(mktemp)
trap 'rm -f "$before" "$after"' EXIT
snapshot() {
  git status --porcelain=v1 --untracked-files=all
  git diff --binary
}
snapshot > "$before"

# test-compile, not compile: TESTING.md routing only happens in a test round, so a main-only
# regeneration would drop every test guardrail from the committed files.
# ProjectFactsConsistencyTest runs after the regeneration because it pins README.md's line counts
# for the generated CLAUDE.md block and .claude/rules/, which the regeneration can move.
(cd vibetags && "$mvn" -B clean test -Pself-annotate \
  -Dtest=ProjectFactsConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false)

snapshot > "$after"
if ! cmp -s "$before" "$after"; then
  echo "::error::VibeTags' own guardrail files are out of date with its annotations."
  echo "Run 'sh tools/self-check.sh' locally; it leaves the regenerated files in the working tree"
  echo "for you to review and commit."
  git --no-pager status --porcelain=v1 --untracked-files=all
  git --no-pager diff
  exit 1
fi
echo "Committed guardrail files match a full regeneration."
