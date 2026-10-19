#!/usr/bin/env bash
# Builds one trial subject for the consumer suite: a copy of evals/consumer/fixture with the
# VibeTags parts the variant takes away removed.
#
#   full              the fixture as committed: annotations in the source, generated block in
#                     CLAUDE.md
#   annotations-only  annotations kept, the generated VIBETAGS-START/END block removed from
#                     CLAUDE.md; what an agent learns from reading the annotated file alone
#   baseline          no VibeTags at all: the block removed and every @AI annotation and its
#                     import stripped from the sources
#
# The hand-written part of CLAUDE.md stays in every variant, so the only difference between two
# variants is what VibeTags contributes.
#
# Usage: prepare-subject.sh <variant> <dest-dir>   (dest-dir must not exist yet)
# Exit status: 0 on success, 2 on a bad variant, an existing destination, or a strip that missed.

set -euo pipefail

variant="${1:-}"
dest="${2:-}"
here="$(cd "$(dirname "$0")" && pwd)"
fixture="$here/fixture"

case "$variant" in
  full|annotations-only|baseline) ;;
  *) echo "error: unknown consumer variant '$variant' (full|annotations-only|baseline)" >&2; exit 2 ;;
esac
if [ -z "$dest" ] || [ -e "$dest" ]; then
  echo "error: destination '$dest' is empty or already exists" >&2
  exit 2
fi

mkdir -p "$dest"
cp -R "$fixture"/. "$dest"/

if [ "$variant" != "full" ]; then
  # The block and the blank line that separates it from the hand-written text.
  perl -0pi -e 's{\n?<!-- VIBETAGS-START -->.*?<!-- VIBETAGS-END -->\n?}{}s' "$dest/CLAUDE.md"
  if grep -q 'VIBETAGS-' "$dest/CLAUDE.md"; then
    echo "error: variant $variant left a VIBETAGS marker in CLAUDE.md" >&2
    exit 2
  fi
fi

if [ "$variant" = "baseline" ]; then
  bash "$here/strip-annotations.sh" "$dest/src"
fi
