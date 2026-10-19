#!/usr/bin/env bash
# Removes every VibeTags annotation and its import from the .java files under <dir>, in place.
# Shared by prepare-subject.sh (the baseline variant) and the privacy-logging detector (which
# compiles the agent's code with no VibeTags jar on the classpath), so both strip identically.
#
# Handles the single-line form the fixture uses; a multi-line annotation an agent adds is left
# alone and reported, so the caller fails loudly rather than compiling something half-stripped.
#
# Usage: strip-annotations.sh <dir>
# Exit status: 0 when nothing VibeTags-shaped is left, 2 otherwise.

set -euo pipefail

dir="${1:?usage: strip-annotations.sh <dir>}"

find "$dir" -name '*.java' -print0 | while IFS= read -r -d '' f; do
  perl -0pi -e '
    s{^[ \t]*\@AI\w+(?:\([^\n]*\))?[ \t]*\n}{}mg;
    s{^import se\.deversity\.vibetags\.annotations\.\w+;\n}{}mg;
    s{\n{3,}}{\n\n}g;
  ' "$f"
done

if grep -rlE '@AI[A-Z]|se\.deversity' --include='*.java' "$dir" >/dev/null; then
  echo "error: a VibeTags annotation or import survived the strip in:" >&2
  grep -rlE '@AI[A-Z]|se\.deversity' --include='*.java' "$dir" >&2
  exit 2
fi
