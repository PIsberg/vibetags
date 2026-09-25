#!/usr/bin/env bash
# Extract one release's notes from docs/CHANGELOG.md, for `gh release create --notes-file`.
#
# Usage: tools/release-notes.sh <version>            # writes the section to stdout
#
# This exists because the extraction used to be an inline awk range in the release skill and in
# docs/RELEASING.md:
#
#     awk "/^## \[$V\]/,/^## \[/{...}" docs/CHANGELOG.md
#
# An awk range tests its end pattern against the very line that opened it, and `^## \[` matches
# the section heading itself, so the range opened and closed on one line. On GNU awk 5.4.1 that
# emitted the heading and nothing else: 1 line where the section was 84 (#619).
#
# It failed in the direction that looks correct. The output was not empty and not an error, it
# was a file containing exactly the right release heading with the whole body missing, and
# `gh release create --notes-file` accepts that without complaint. By the time anyone reads the
# release page the tag exists and the Maven Central deploy has already fired, and a tag that
# publishes cannot be re-cut. Hence the guard below: this script fails loudly rather than
# handing a truncated file to an irreversible step.
#
# Deliberately no release version appears anywhere in this file. ReleaseScriptCoverageTest fails
# the build for a version literal in a tracked file that tools/set-version.sh does not rewrite.
set -eu

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: $0 <version>" >&2
  exit 2
fi

CHANGELOG="${CHANGELOG:-docs/CHANGELOG.md}"
if [ ! -f "$CHANGELOG" ]; then
  echo "release-notes.sh: no changelog at $CHANGELOG (run from the repository root)" >&2
  exit 2
fi

# index($0, want) == 1 rather than a regex, so the [ and ] in the heading need no escaping.
# The heading is printed, matching every release body from 1.2.x on, and `next` moves past it
# so the stop rule below cannot fire on the very line that started the section.
notes=$(awk -v want="## [$VERSION]" '
  index($0, want) == 1 { on = 1; print; next }
  on && /^## \[/       { exit }
  on                   { print }
' "$CHANGELOG")

# Drop leading blank lines so the release page opens on the first heading. Command substitution
# has already stripped the trailing ones.
notes=$(printf '%s
' "$notes" | sed -e '/./,$!d')

# A section that is present but nearly empty is as dangerous as a missing one, so the guard is a
# line count and not a null check. Five lines is well below any real entry and well above the
# one-line truncation this is here to catch.
count=$(printf '%s\n' "$notes" | grep -c '' || true)
if [ "$count" -lt 5 ]; then
  echo "release-notes.sh: extracted only $count line(s) for $VERSION from $CHANGELOG." >&2
  echo "  Either the heading '## [$VERSION]' is absent, or the section is empty. Refusing to" >&2
  echo "  emit notes for a release, because the publish step it feeds cannot be undone." >&2
  exit 1
fi

# GitHub resolves a relative link on a release page from the repository root, while the same link
# in the CHANGELOG resolves from docs/. So every relative target is resolved against docs/ and
# pinned to the tag: raw/ for an image, blob/ for anything else. Absolute URLs and in-page anchors
# are left alone. This used to rewrite only `changelog-assets/<version>/`, so a section embedding
# plots as `../load-tests/...` would have shipped three 404s to the release page (#849).
#
# A target that still climbs above the root after resolving (`../../x`) has no tag URL, so the
# script refuses and emits nothing, for the same reason as the line-count guard above.
printf '%s\n' "$notes" | awk -v repo="https://github.com/PIsberg/vibetags" -v tag="v$VERSION" '
  function lastindex(s, c,    i) {
    for (i = length(s); i > 0; i--) if (substr(s, i, 1) == c) return i
    return 0
  }
  function rewrite(line,    out, pre, target, lb, kind, resolved) {
    out = ""
    while (match(line, /\]\([^) ]+\)/)) {
      pre = substr(line, 1, RSTART)
      target = substr(line, RSTART + 2, RLENGTH - 3)
      line = substr(line, RSTART + RLENGTH)
      if (target ~ /^(#|[A-Za-z][A-Za-z0-9+.-]*:)/) {
        out = out pre "(" target ")"
        continue
      }
      lb = lastindex(pre, "[")
      kind = (lb > 1 && substr(pre, lb - 1, 1) == "!") ? "raw" : "blob"
      if (target ~ /^\//)          resolved = substr(target, 2)
      else if (target ~ /^\.\.\//) resolved = substr(target, 4)
      else                         resolved = "docs/" target
      if (resolved ~ /^\.\.\// || resolved ~ /\/\.\.\//) escaped = escaped "  " target "\n"
      out = out pre "(" repo "/" kind "/" tag "/" resolved ")"
    }
    return out line
  }
  { buf = buf rewrite($0) "\n" }
  END {
    if (escaped != "") {
      printf "release-notes.sh: these links resolve outside the repository, so no tag URL can serve them:\n%s", escaped > "/dev/stderr"
      printf "  Refusing to emit notes for a release, because the publish step it feeds cannot be undone.\n" > "/dev/stderr"
      exit 1
    }
    printf "%s", buf
  }
'
