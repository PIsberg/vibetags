#!/bin/sh
# Regenerate the code-karta architecture diagrams under docs/diagrams/codekarta/.
#
# code-karta parses Java source and emits SVG. It is resolved from Maven Central rather
# than vendored, so this needs a network on first run and nothing afterwards — the shaded
# CLI lands in the local repository like any other artifact.
#
# Usage:  sh tools/generate-architecture-diagrams.sh
#
# The diagrams are committed. Regenerate them when the package structure changes, not on
# every commit: they describe shape, and shape changes rarely. A diff here means the
# architecture moved, which is exactly when someone should look.
set -eu

CK_VERSION="0.1.0"
CK_COORDS="se.deversity.codekarta:code-karta-cli:${CK_VERSION}:jar:all"
OUT_DIR="docs/diagrams/codekarta"

# --layout elk is deliberate, not taste. The default 'simple' engine lays every node of
# one BFS depth into a single unbounded row, and this processor has ~120 types at depth 0,
# which renders about 19500px wide. ELK's layered algorithm draws the same graph in about
# 2300px. code-karta after 0.1.0 also wraps rows in the simple engine, at which point this
# flag stops being load-bearing — but pinning it keeps output stable across CLI versions.
LAYOUT="elk"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

command -v mvn  >/dev/null 2>&1 || { echo "error: mvn is not on PATH"  >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "error: java is not on PATH" >&2; exit 1; }

echo "Resolving ${CK_COORDS} ..."
mvn -B -q dependency:get -Dartifact="${CK_COORDS}" \
  || { echo "error: could not resolve code-karta ${CK_VERSION} from Maven Central" >&2; exit 1; }

M2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
CK_JAR="$M2/se/deversity/codekarta/code-karta-cli/${CK_VERSION}/code-karta-cli-${CK_VERSION}-all.jar"
[ -f "$CK_JAR" ] || { echo "error: code-karta CLI jar not found at $CK_JAR" >&2; exit 1; }

mkdir -p "$OUT_DIR"

# Two class diagrams, each scoped to a package that answers one question.
#
# There is deliberately no whole-package stitched call graph here. Running --sequence-only
# over processor.internal yields 986 nodes across roughly 36000x43700 pixels: technically a
# diagram, practically a data dump. Breadth is the problem, not depth, so --max-depth does
# not rescue it either. Scope beats settings.

echo "Class diagram: the processor as a whole ..."
java -jar "$CK_JAR" \
  --input  "vibetags/src/main/java/se/deversity/vibetags/processor" \
  --output "$OUT_DIR" \
  --layout "$LAYOUT"

echo "Class diagram: the compiler-free model ..."
java -jar "$CK_JAR" \
  --input  "vibetags/src/main/java/se/deversity/vibetags/processor/model" \
  --output "$OUT_DIR/model" \
  --layout "$LAYOUT"

echo
echo "Done. Generated under $OUT_DIR:"
find "$OUT_DIR" -name '*.svg' -print | sort
