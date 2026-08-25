#!/usr/bin/env bash
# Runs VibeTags over real third-party Java and checks it changed nothing it should not.
#
# The examples in this repository were written by people who knew what VibeTags does. Real
# libraries were not, and they are where the assumptions break: deeply nested types, generic
# signatures nobody would invent for a fixture, package-info files, records, a module whose own
# annotation processor is already running. corpus/repos.tsv pins six of them.
#
# Each repo is compiled twice, and the comparison is the point:
#
#   control    javac with no annotation processors at all
#   treatment  the same javac, same sources, same classpath, plus VibeTags
#
# Four assertions, each of which fails loudly:
#
#   1. The treatment exits exactly as the control did. This is the promise the whole design
#      rests on: adding VibeTags to somebody's build must not fail it. Comparing against the
#      control rather than against zero is deliberate, so a repo that does not compile on its
#      own is reported as such instead of being blamed on VibeTags.
#   2. The treatment raises no error or warning the control did not. A processor that turns a
#      clean build noisy has broken the same promise more quietly.
#   3. Nothing is written to the VibeTags root. File presence is the only opt-in (tier-1
#      invariant 1), and none of these repos opted in. vibetags.log is the documented exception
#      (USAGE.md: vibetags.log.path, disable with OFF).
#   4. ElementNaming renders every member exactly as javac does. ElementNamingFormatParityTest
#      asserts this over a 26-member fixture; here it runs over thousands of members nobody
#      chose. The count is asserted too: a run that visits nothing proves nothing.
#
# Nothing is vendored. Sources are cloned at the pinned SHA into a cache directory and never
# committed, so no third-party code enters this repository.
#
# Usage:
#   corpus/run-corpus.sh [name ...]     # all repos, or just the named ones
#
# Env:
#   VIBETAGS_CORPUS_DIR   cache location (default: <repo>/target/corpus)
#
# Requires vibetags-annotations and vibetags installed (see CLAUDE.md "Build and test").
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CACHE="${VIBETAGS_CORPUS_DIR:-$ROOT/target/corpus}"
MANIFEST="$ROOT/corpus/repos.tsv"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ; winpath() { cygpath -w "$1"; } ;;
  *)                    SEP=":" ; winpath() { printf "%s" "$1"; } ;;
esac

VERSION=$(sed -n "s|.*<revision>\(.*\)</revision>.*|\1|p" "$ROOT/vibetags-parent/pom.xml")
PROC_JAR="$ROOT/vibetags/target/vibetags-processor-$VERSION.jar"
if [ ! -f "$PROC_JAR" ]; then
  echo "FAIL: $PROC_JAR not found. Run 'mvn install' in vibetags/ first." >&2
  exit 1
fi

mkdir -p "$CACHE"
mvn -q -f "$ROOT/vibetags/pom.xml" dependency:build-classpath \
    -Dmdep.includeScope=runtime "-Dmdep.outputFile=$CACHE/vibetags.cp" >/dev/null 2>&1
VT_CP="$(winpath "$PROC_JAR")$SEP$(cat "$CACHE/vibetags.cp")"

# The auditor compiles against the processor jar and runs alongside VibeTags.
AUDITOR_OUT="$CACHE/auditor-classes"
mkdir -p "$AUDITOR_OUT"
( cd "$ROOT/corpus" && javac -nowarn -cp "$VT_CP" -d "$(winpath "$AUDITOR_OUT")" \
    ElementNamingAuditor.java ) || { echo "FAIL: the corpus auditor did not compile" >&2; exit 1; }
FULL_CP="$(winpath "$AUDITOR_OUT")$SEP$VT_CP"

# Fetches exactly the pinned commit. Shallow, so a large history costs nothing, and idempotent,
# so a warm cache does no network at all.
fetch_repo() {
  name="$1"; url="$2"; sha="$3"
  dir="$CACHE/$name"
  if [ -f "$dir/.corpus-sha" ] && [ "$(cat "$dir/.corpus-sha")" = "$sha" ]; then
    return 0
  fi
  rm -rf "$dir"
  mkdir -p "$dir"
  ( cd "$dir" \
    && git init --quiet \
    && git remote add origin "$url" \
    && git fetch --quiet --depth 1 origin "$sha" \
    && git checkout --quiet FETCH_HEAD ) || return 1
  printf '%s' "$sha" > "$dir/.corpus-sha"
}

# Counts diagnostics javac actually raised. Note: lines are excluded on purpose - they are how
# processors report ordinary information and are not a broken build.
diagnostics() {
  grep -cE "^[^ ].*: (error|warning):" "$1" 2>/dev/null || true
}

status=0
repos_run=0
total_visited=0
total_annotated=0
printf "%-16s %-7s %-18s %-9s %-8s %-9s %s\n" \
  REPO FILES "EXIT ctrl/vt" "DIAGS c/v" WROTE MEMBERS TYPE-ANN

while IFS=$'\t' read -r name url sha src pom licence why; do
  case "$name" in ''|'#'*) continue ;; esac
  if [ "$#" -gt 0 ]; then
    case " $* " in *" $name "*) ;; *) continue ;; esac
  fi

  if ! fetch_repo "$name" "$url" "$sha"; then
    echo "FAIL: could not fetch $name at $sha" >&2
    status=1
    continue
  fi

  dir="$CACHE/$name"
  if [ ! -d "$dir/$src" ]; then
    echo "FAIL: $name has no source root at $src (pinned SHA moved it?)" >&2
    status=1
    continue
  fi

  # The repo's own dependencies, resolved by its own build, so its sources actually attribute.
  # Without this the model is full of error types and every assertion below weakens.
  if [ ! -s "$dir/.corpus-cp" ]; then
    ( cd "$dir" && mvn -q -f "$pom" dependency:build-classpath \
        "-Dmdep.outputFile=$dir/.corpus-cp" >"$dir/.corpus-mvn.log" 2>&1 ) || true
  fi
  dep_cp=$(cat "$dir/.corpus-cp" 2>/dev/null || printf '')

  # module-info.java is excluded: it needs modules that are not on this path, and the corpus is
  # about ordinary compilation units rather than JPMS resolution.
  ( cd "$dir" && find "$src" -name "*.java" ! -name "module-info.java" > .corpus-sources )
  files=$(wc -l < "$dir/.corpus-sources" | tr -d ' ')

  vt_root="$dir/.corpus-vibetags-root"
  rm -rf "$vt_root" "$dir/.corpus-ctrl" "$dir/.corpus-vt"
  mkdir -p "$vt_root" "$dir/.corpus-ctrl" "$dir/.corpus-vt"

  cp_arg=""
  [ -n "$dep_cp" ] && cp_arg="-cp $dep_cp"

  # Control. Not piped: a pipeline reports the last stage's status, and this exit code is half
  # of assertion 1.
  ( cd "$dir" && javac -proc:none -nowarn -encoding UTF-8 $cp_arg \
      -d "$(winpath "$dir/.corpus-ctrl")" @.corpus-sources ) > "$dir/.corpus-ctrl.log" 2>&1
  ctrl_exit=$?

  # Treatment: identical, plus VibeTags and the auditor.
  ( cd "$dir" && javac -nowarn -encoding UTF-8 $cp_arg \
      -processorpath "$FULL_CP" \
      -processor se.deversity.vibetags.processor.AIGuardrailProcessor,corpus.ElementNamingAuditor \
      "-Avibetags.root=$(winpath "$vt_root")" \
      "-Acorpus.report=$(winpath "$dir/.corpus-report")" \
      -d "$(winpath "$dir/.corpus-vt")" @.corpus-sources ) > "$dir/.corpus-vt.log" 2>&1
  vt_exit=$?

  ctrl_diag=$(diagnostics "$dir/.corpus-ctrl.log")
  vt_diag=$(diagnostics "$dir/.corpus-vt.log")

  # Assertion 3. vibetags.log is documented and expected; anything else is a file created in a
  # project that never opted in.
  wrote=$(find "$vt_root" -type f ! -name "vibetags.log" | wc -l | tr -d ' ')

  visited=$(sed -n 's/^VISITED\t//p' "$dir/.corpus-report" 2>/dev/null | head -1)
  mismatches=$(sed -n 's/^MISMATCHES\t//p' "$dir/.corpus-report" 2>/dev/null | head -1)
  annotated=$(sed -n 's/^ANNOTATED\t//p' "$dir/.corpus-report" 2>/dev/null | head -1)
  visited=${visited:-0}
  mismatches=${mismatches:-0}
  annotated=${annotated:-0}
  total_annotated=$((total_annotated + annotated))

  printf "%-16s %-7s %-18s %-9s %-8s %-9s %s\n" \
    "$name" "$files" "$ctrl_exit/$vt_exit" "$ctrl_diag/$vt_diag" "$wrote" "$visited" "$annotated"

  repos_run=$((repos_run + 1))
  total_visited=$((total_visited + visited))

  if [ "$ctrl_exit" -ne "$vt_exit" ]; then
    echo "FAIL: $name exits $vt_exit with VibeTags and $ctrl_exit without it." >&2
    echo "      Adding VibeTags to a build must not change whether it succeeds." >&2
    grep -E ": (error|warning):" "$dir/.corpus-vt.log" | head -10 >&2
    status=1
  fi
  if [ "$vt_diag" -gt "$ctrl_diag" ]; then
    echo "FAIL: $name raises $vt_diag diagnostics with VibeTags against $ctrl_diag without." >&2
    echo "      A processor that turns a clean build noisy has broken the same promise." >&2
    diff <(grep -E ": (error|warning):" "$dir/.corpus-ctrl.log" | sort) \
         <(grep -E ": (error|warning):" "$dir/.corpus-vt.log" | sort) | head -10 >&2
    status=1
  fi
  if [ "$wrote" -ne 0 ]; then
    echo "FAIL: $name had $wrote file(s) written into a project that never opted in:" >&2
    find "$vt_root" -type f ! -name "vibetags.log" | head -10 >&2
    status=1
  fi
  if [ "$visited" -lt 1 ]; then
    echo "FAIL: the auditor visited no members in $name, so its result means nothing." >&2
    status=1
  fi
  if [ "$mismatches" -ne 0 ]; then
    echo "FAIL: $name has $mismatches member(s) whose ElementNaming path differs from javac's." >&2
    echo "      That string is the element identity in .vibetags-locks and in rule filenames." >&2
    grep "^MISMATCH" "$dir/.corpus-report" | head -10 >&2
    status=1
  fi
done < "$MANIFEST"

echo
if [ "$repos_run" -eq 0 ]; then
  echo "FAIL: no corpus repo ran. An empty corpus reports success while checking nothing." >&2
  exit 1
fi
if [ "$status" -eq 0 ]; then
  echo "OK: $repos_run repositories, $total_visited members audited."
  echo "    VibeTags changed no exit code, added no diagnostic, and wrote no file."
  echo "    $total_annotated member(s) differ from javac by a type-use annotation only, which is"
  echo "    deliberate (ElementNaming.typeString): the identity is the signature, not its"
  echo "    annotations. Every other member renders exactly as javac renders it."
fi
exit "$status"
