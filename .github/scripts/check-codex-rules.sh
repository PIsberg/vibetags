#!/usr/bin/env bash
#
# Load each generated .codex/rules/vibetags.rules with the Codex CLI and check the decision it
# gives for every command VibeTags writes a rule for (#970).
#
# Every other check on this file reads the bytes VibeTags itself wrote, so the tests agreed with
# the generator while Codex refused the whole file (#969): `prefix_rule("ls", "allow")` passes a
# string where Codex requires a list. Only Codex can say whether it loads, so this asks it.
#
# `codex execpolicy check --rules <file> <command...>` exits non-zero when the file does not parse
# and otherwise prints JSON whose top-level "decision" is the strictest matching rule's. A command
# no rule matches prints no decision, so a rule that went missing fails here as well.
#
# Usage: check-codex-rules.sh <rules-file>...
#   CODEX=<path>   the Codex CLI to run, default `codex` on PATH. A missing CLI fails the check:
#                  a skipped check would read as a passed one.
set -uo pipefail

CODEX="${CODEX:-codex}"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <rules-file>..." >&2
  exit 2
fi
if ! version="$("$CODEX" --version 2>&1)"; then
  echo "::error::cannot run the Codex CLI ($CODEX): $version"
  exit 1
fi
echo "codex.rules.check cli=\"$version\" files=$#"

# command -> the decision VibeTags' rules file means it to get
expected="
ls allow
cat allow
grep allow
mvn prompt
npm prompt
git prompt
rm prompt
"

failures=0
for rules in "$@"; do
  if [ ! -f "$rules" ]; then
    echo "::error file=$rules::no such rules file"
    failures=$((failures + 1))
    continue
  fi
  while read -r command decision; do
    [ -z "$command" ] && continue
    if ! out="$("$CODEX" execpolicy check --rules "$rules" "$command" 2>&1)"; then
      echo "::error file=$rules::Codex did not load the rules file: $out"
      failures=$((failures + 1))
      break
    fi
    got="$(printf '%s\n' "$out" | sed -n 's/.*"decision":"\([a-z]*\)"}[[:space:]]*$/\1/p' | tail -n1)"
    if [ "$got" != "$decision" ]; then
      echo "::error file=$rules::\`$command\` got decision '${got:-none}', expected '$decision': $out"
      failures=$((failures + 1))
    else
      echo "codex.rules.ok file=$rules command=$command decision=$got"
    fi
  done <<< "$expected"
done

if [ "$failures" -ne 0 ]; then
  echo "codex.rules.fail failures=$failures"
  exit 1
fi
echo "codex.rules.pass files=$#"
