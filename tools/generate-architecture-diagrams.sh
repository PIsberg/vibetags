#!/bin/sh
# Regenerate the code-karta architecture diagrams under docs/diagrams/codekarta/.
#
# code-karta parses Java source and emits SVG. It is resolved from Maven Central rather
# than vendored, so this needs a network on first run and nothing afterwards — the shaded
# CLI lands in the local repository like any other artifact.
#
# Usage:  sh tools/generate-architecture-diagrams.sh
#
# The diagrams are committed, and CI regenerates them on every build and fails on
# structural drift (the `diagrams` job in .github/workflows/build.yml, comparing
# tools/diagram-structure.sh fingerprints). Structure, not bytes: regeneration is
# idempotent on one machine but the CLI's directory walk follows filesystem order, so
# node positions differ between the OS that committed and the OS that checks. A structural
# diff here means the architecture moved, which is exactly when someone should look - and
# the gate makes sure someone does.
#
# Every diagram this script emits is embedded in a doc. If you add one, link it from the
# doc whose question it answers, and add a row to the table in
# docs/ARCHITECTURE.md#parsed-diagrams-code-karta.
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

karta() {
  java -jar "$CK_JAR" --layout "$LAYOUT" "$@"
}

# Four class diagrams, each scoped to a package that answers one question, plus one
# sequence diagram over the orchestrator.
#
# There is deliberately no whole-package stitched call graph here. Running --sequence-only
# over processor.internal yields 986 nodes across roughly 36000x43700 pixels: technically a
# diagram, practically a data dump. Breadth is the problem, not depth, so --max-depth does
# not rescue it either. Scope beats settings.

echo "Class diagram: the processor as a whole ..."
karta --input  "vibetags/src/main/java/se/deversity/vibetags/processor" \
      --output "$OUT_DIR"

echo "Class diagram: the compiler-free model ..."
karta --input  "vibetags/src/main/java/se/deversity/vibetags/processor/model" \
      --output "$OUT_DIR/model"

echo "Class diagram: the rendering layer ..."
karta --input  "vibetags/src/main/java/se/deversity/vibetags/processor/internal/content" \
      --output "$OUT_DIR/content"

echo "Class diagram: the annotation surface ..."
karta --input  "vibetags-annotations/src/main/java/se/deversity/vibetags/annotations" \
      --output "$OUT_DIR/annotations"

# The one diagram in the set that shows order rather than shape. AIGuardrailProcessor sits
# under <locked_files> precisely because the step order in generateFiles() is load-bearing;
# this is that order, parsed rather than described.
echo "Sequence diagram: the orchestrator's call order ..."
karta --input  "vibetags/src/main/java/se/deversity/vibetags/processor/AIGuardrailProcessor.java" \
      --output "$OUT_DIR/sequence"

# Two of code-karta's modes were tried against this repository and do not fit. Both are
# recorded here so nobody spends an afternoon rediscovering it:
#
#   --modules-only   needs module-info.java. VibeTags ships no JPMS descriptors (the
#                    processor has to load on whatever classpath a consumer's javac gives
#                    it), so the parsed graph comes back empty and the CLI skips the file.
#   --state-machine  reads enum constants as states. The two enums here — content.Platform
#                    and model.ElementTag — are catalogues, not machines: the generated SVG
#                    is 60-odd boxes with zero transition edges. A table renders that
#                    better, and docs/PLATFORMS.md already has one.

echo
echo "Done. Generated under $OUT_DIR:"
find "$OUT_DIR" -name '*.svg' -print | sort
