#!/usr/bin/env bash
#
# Exercise check-codex-rules.sh against rules files of known shape, with the real Codex CLI.
#
# The case that matters is the #969 file: rules that pass `pattern` as a string, which Codex
# refuses to load. VibeTags' own tests accepted that file for every release that shipped it; this
# suite is what says the checker rejects it. The last case runs with no CLI at all, which must
# fail rather than skip.
#
# Usage: check-codex-rules.test.sh [path-to-check-codex-rules.sh]   (CODEX as for the checker)
set -u

SCRIPT="${1:-$(cd "$(dirname "$0")" && pwd)/check-codex-rules.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

# expect <0|nonzero> <name> <rules-file>... : run the checker and compare its exit status
expect() {
  local want="$1" name="$2"
  shift 2
  bash "$SCRIPT" "$@" > "$TMP/out" 2>&1
  local status=$?
  if { [ "$want" = 0 ] && [ "$status" -eq 0 ]; } || { [ "$want" = nonzero ] && [ "$status" -ne 0 ]; }; then
    echo "ok   $name (exit $status)"
    pass=$((pass + 1))
  else
    echo "FAIL $name: wanted exit $want, got $status"
    sed 's/^/     /' "$TMP/out"
    fail=$((fail + 1))
  fi
}

# rules <file> <call-format>: the seven rules VibeTags writes, each rendered through printf
rules() {
  local file="$1" fmt="$2"
  {
    echo '# VIBETAGS-START'
    for pair in ls:allow cat:allow grep:allow mvn:prompt npm:prompt git:prompt rm:prompt; do
      # shellcheck disable=SC2059
      printf "$fmt\n" "${pair%%:*}" "${pair##*:}"
    done
    echo '# VIBETAGS-END'
  } > "$file"
}

# 1. the shape VibeTags writes since #969
rules "$TMP/list.rules" 'prefix_rule(pattern=["%s"], decision="%s")'
expect 0 "list pattern, as generated" "$TMP/list.rules"

# 2. the shape VibeTags wrote before #969, which Codex refuses to parse
rules "$TMP/string.rules" 'prefix_rule("%s", "%s")'
expect nonzero "string pattern (#969)" "$TMP/string.rules"

# 3. a file that parses but gives a command the wrong decision
sed 's/\["rm"\], decision="prompt"/["rm"], decision="allow"/' "$TMP/list.rules" > "$TMP/wrong.rules"
expect nonzero "rm allowed instead of prompted" "$TMP/wrong.rules"

# 4. a file that parses but lost a rule: `git` matches nothing and gets no decision
grep -v '"git"' "$TMP/list.rules" > "$TMP/missing.rules"
expect nonzero "git rule missing" "$TMP/missing.rules"

# 5. one bad file among good ones fails the run
expect nonzero "good and bad file together" "$TMP/list.rules" "$TMP/string.rules"

# 6. a rules file that does not exist
expect nonzero "missing rules file" "$TMP/absent.rules"

# 7. no Codex CLI: a check that cannot run must not pass
saved="${CODEX-}"
export CODEX="$TMP/no-such-codex"
expect nonzero "no Codex CLI" "$TMP/list.rules"
if [ -n "$saved" ]; then CODEX="$saved"; else unset CODEX; fi

echo "check-codex-rules.test: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
