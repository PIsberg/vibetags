#!/bin/sh
# Canonical structural fingerprint of the code-karta SVGs under a directory: per file, the
# sorted multiset of <title> elements (node identity plus kind, e.g. "WriteCache [CLASS]").
#
# Why structure and not bytes: code-karta 0.1.0's directory walk follows filesystem order,
# so the same sources render with nodes in different positions on NTFS and ext4, and member
# labels truncate at a font-metric-dependent width. Both make byte and label comparison
# environment-dependent, while the title set - which types exist, and as what kind - is
# deterministic, and is exactly what an architecture drift gate exists to pin. Edges and
# member signatures are NOT covered by this fingerprint; a change visible only there will
# not fail the gate. A code-karta release with a sorted walk would allow returning to a
# byte-level comparison; until then this is the honest gate.
#
# Usage: sh tools/diagram-structure.sh docs/diagrams/codekarta
set -eu
dir="$1"
find "$dir" -name '*.svg' | LC_ALL=C sort | while IFS= read -r f; do
  echo "== $f"
  # grep exits 1 on zero matches; an SVG with no titles is a valid (empty) contribution.
  grep -o '<title>[^<]*</title>' "$f" | LC_ALL=C sort || true
done
