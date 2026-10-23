#!/usr/bin/env bash
# Runs the build's own Checkstyle on every Maven module the given files belong to.
#
# The pre-commit hook "checkstyle" passes the staged .java files. Each one's top-level directory
# is checked when its pom runs maven-checkstyle-plugin, so the ruleset (checkstyle.xml through the
# module's configLocation), the suppressions and the Checkstyle version are exactly what every
# Maven build applies at validate. Files outside such a module (examples/, corpus/) are skipped.
#
# Why not a Checkstyle hook from a hook repository: gherynos/pre-commit-java, used until #982, runs
# Sun's default ruleset when no -c is given, which reports 226 errors on a module this build passes
# with 0, and its Docker scripts are checked out with CRLF on Windows, where they never ran.
#
# Run from anywhere inside the repository:  bash tools/checkstyle-hook.sh <file>...
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

modules=()
for file in "$@"; do
  module=${file%%/*}
  if [ "$module" = "$file" ] || ! grep -q '<artifactId>maven-checkstyle-plugin</artifactId>' "$module/pom.xml" 2>/dev/null; then
    continue
  fi
  case " ${modules[*]-} " in
    *" $module "*) ;;
    *) modules+=("$module") ;;
  esac
done

status=0
for module in "${modules[@]-}"; do
  [ -n "$module" ] || continue
  echo "checkstyle: $module"
  (cd "$module" && "$mvn" -B -q checkstyle:check) || status=1
done
exit "$status"
