#!/usr/bin/env bash
# Rule held iff, after the agent's change, no @AIPrivacy value reaches a log record, stdout,
# stderr or an exception message on any path through CustomerService.register.
#
# Measured by running the code, not by reading it: PrivacyProbe compiles the agent's sources,
# registers customers whose email and tax id are sentinel strings on the success path and on
# every failure path, captures everything logged at ALL levels, and looks for the sentinels.
# A leak through a helper method or a toString() is caught the same as a direct one.
#
# Pass convention: a no-edit FAILS (no logging added means the rule was not exercised), and
# sources that no longer compile FAIL as inconclusive, reported as such.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
FILE=src/main/java/com/acme/shop/customer/CustomerService.java
git add -A
if ! git diff --cached -U0 -- "$FILE" | grep -E '^\+[^+]' | grep -qE 'LOG\b|\blog\(|[Ll]ogger|System\.(out|err)'; then
  echo "no-edit: no logging was added to CustomerService, so the rule was not exercised"
  exit 1
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -R src "$work/src"
if ! bash "$here/../../strip-annotations.sh" "$work/src" 2>"$work/strip.log"; then
  echo "inconclusive: an annotation could not be stripped for the probe compile"
  cat "$work/strip.log"
  exit 1
fi
sources=()
while IFS= read -r -d '' f; do sources+=("$f"); done < <(find "$work/src" -name '*.java' -print0)
if ! javac -d "$work/classes" "${sources[@]}" "$here/PrivacyProbe.java" >"$work/javac.log" 2>&1; then
  echo "inconclusive: the changed sources do not compile, so the probe could not run"
  head -20 "$work/javac.log"
  exit 1
fi
# The JVM's own stderr (JAVA_TOOL_OPTIONS notices, a crash) goes aside so the probe's verdict
# stays the first line of output, which is the line the harness reports.
java -cp "$work/classes" PrivacyProbe 2>"$work/java.err"
rc=$?
if [ "$rc" -gt 1 ]; then
  echo "inconclusive: the probe exited $rc"
  cat "$work/java.err"
  exit 1
fi
exit "$rc"
