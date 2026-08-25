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
# Floor for assertion 6f: how many of the showcase's guardrails must reach a generated
# file. 15 is measured, not aspirational - it is every marker the showcase declares,
# across package, type, nested type, field, method and parameter levels, and it was
# read off a green run before being written down. Raise it when the showcase grows.
# Lowering it to make a run green is how this check stops meaning anything.
SHOWCASE_FLOOR="${VIBETAGS_CORPUS_SHOWCASE_FLOOR:-15}"

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
  #
  # mdep.regenerateFile is not optional. Without it dependency:build-classpath compares the
  # classpath it computed against the output file and reports "No changes found" - exiting 0
  # while writing nothing. On a warm machine the file is already there and everything looks
  # fine; on a cold CI runner it is not, and the compile silently ran with no classpath at all.
  # Resolved every run, never reused from the cache. The checkouts are what the cache is for -
  # they are the expensive part and are pinned to a SHA, so a hit is always the right source.
  # A resolved classpath is not: the CI cache is keyed on repos.tsv, so a run that resolved
  # badly leaves a .corpus-cp that a later run restores and, because it is non-empty, trusts.
  # That is exactly what happened - a poisoned cache from a failing run made jimfs compile
  # without guava on a branch where resolution itself was already fixed.
  rm -f "$dir/.corpus-cp"
  ( cd "$dir" && mvn -q -f "$pom" dependency:build-classpath \
      -Dmdep.regenerateFile=true \
      "-Dmdep.outputFile=$dir/.corpus-cp" >"$dir/.corpus-mvn.log" 2>&1 ) || true
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

  # Assertion 0, and the one that matters most for keeping the rest honest: the control has to
  # compile. Comparing the treatment against the control is what stops a broken repo being
  # blamed on VibeTags, but it also means a repo that cannot compile *at all* passes assertions
  # 1 and 2 trivially, both sides failing identically, while the model behind assertions 3 and 4
  # is full of error types. That is not a corpus member, it is a vacuous pass.
  #
  # This is not hypothetical: dependency:build-classpath exited 0 without writing a classpath on
  # a cold CI runner, jimfs compiled with no dependencies, and the run reported 100 diagnostics
  # on each side and carried on.
  if [ "$ctrl_exit" -ne 0 ]; then
    echo "FAIL: $name does not compile on its own, so nothing below it means anything." >&2
    echo "      Every assertion here is relative to this control. Fix the corpus member -" >&2
    echo "      classpath resolution, source root, or the pinned SHA - rather than the code." >&2
    echo "      resolved classpath: $(wc -c < "$dir/.corpus-cp" 2>/dev/null || echo 0) bytes" >&2
    tail -5 "$dir/.corpus-mvn.log" 2>/dev/null >&2
    grep -E ": error:" "$dir/.corpus-ctrl.log" | head -5 >&2
    status=1
    continue
  fi
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

  # ---------------------------------------------------------------------------------------
  # Opt-in phase. Everything above proves VibeTags stays out of the way, which is only half
  # the question: what does it actually produce on code nobody wrote for it? Two real elements
  # per repo are annotated in the clone, three platform files are created to opt in, and the
  # generated output is read back.
  #
  # The @Nullable-parameter target is the one that matters. Its element path is what #480
  # changed, and granularQName turns that path into a rule filename - so if a type-use
  # annotation ever leaks back into the identity, it shows up here as a filename containing
  # "org-jspecify-annotations-Nullable", which assertion 8 rejects by name.
  # ---------------------------------------------------------------------------------------
  optin="$dir/.corpus-optin-root"
  rm -rf "$optin" "$dir/.corpus-optin-classes"
  # Three platforms, aggregate and granular. Claude and Gemini both have a granular directory;
  # Codex has none, so it gets its aggregate only.
  mkdir -p "$optin/.claude/rules" "$optin/.gemini/rules" "$dir/.corpus-optin-classes"
  # File presence is the opt-in. Empty files, exactly as a consumer would create them.
  : > "$optin/CLAUDE.md"
  : > "$optin/GEMINI.md"
  : > "$optin/.vibetags-locks"
  # AGENTS.md is the exception, and the exception is invariant 4: it is written only as the sole
  # AI config file, or when it already carries a marker pair. Opting Codex in alongside Claude
  # and Gemini therefore means seeding the pair, which is exactly what a consumer does. Created
  # empty it would be dropped from the active set, silently, and the corpus would be asserting
  # that Codex works while never generating a byte of it.
  printf '%s\n%s\n' '<!-- VIBETAGS-START -->' '<!-- VIBETAGS-END -->' > "$optin/AGENTS.md"

  ( cd "$dir" && git checkout --quiet -- . 2>/dev/null )
  # tr -d: Python on Windows turns \n into \r\n on stdout, and a trailing CR in the element
  # name makes every grep for it miss.
  targets=$(python "$ROOT/corpus/annotate.py" "$dir/$src" 2>"$dir/.corpus-annotate.err" | tr -d '\r')
  if [ -z "$targets" ]; then
    echo "FAIL: could not annotate anything in $name, so the opt-in phase checks nothing." >&2
    cat "$dir/.corpus-annotate.err" >&2
    status=1
    ( cd "$dir" && git checkout --quiet -- . 2>/dev/null )
    continue
  fi

  # The showcase: every level a guardrail attaches to, in a package of its own under the repo's
  # source root, compiled with the repo's classpath and compiler settings. Annotating real
  # third-party elements (above) proves VibeTags handles their signatures; this proves it renders
  # the whole annotation surface, which no third-party repo would ever contain.
  showcase_pkg="vibetagscorpus"
  showcase_dir="$dir/$src/$showcase_pkg"
  mkdir -p "$showcase_dir"
  sed "s/__PACKAGE__/$showcase_pkg/g" "$ROOT/corpus/showcase/CorpusShowcase.java.tmpl" \
    > "$showcase_dir/CorpusShowcase.java"
  sed "s/__PACKAGE__/$showcase_pkg/g" "$ROOT/corpus/showcase/package-info.java.tmpl" \
    > "$showcase_dir/package-info.java"

  ann_jar=$(ls "$HOME"/.m2/repository/se/deversity/vibetags/vibetags-annotations/"$VERSION"/vibetags-annotations-"$VERSION".jar 2>/dev/null | head -1)
  if [ ! -f "$ann_jar" ]; then
    echo "FAIL: vibetags-annotations $VERSION is not in the local repository." >&2
    echo "      The showcase cannot compile without it; run 'mvn install' in vibetags-annotations/." >&2
    status=1
    rm -rf "$showcase_dir"
    continue
  fi
  optin_cp="$(winpath "$ann_jar")"
  [ -n "$dep_cp" ] && optin_cp="$optin_cp$SEP$dep_cp"

  ( cd "$dir" && find "$src" -name "*.java" ! -name "module-info.java" > .corpus-sources-optin \
    && javac -nowarn -encoding UTF-8 -cp "$optin_cp" \
      -processorpath "$FULL_CP" \
      -processor se.deversity.vibetags.processor.AIGuardrailProcessor \
      "-Avibetags.root=$(winpath "$optin")" \
      -d "$(winpath "$dir/.corpus-optin-classes")" @.corpus-sources-optin ) \
      > "$dir/.corpus-optin.log" 2>&1
  optin_exit=$?
  rm -rf "$showcase_dir"
  ( cd "$dir" && git checkout --quiet -- . 2>/dev/null )

  if [ "$optin_exit" -ne 0 ]; then
    echo "FAIL: $name did not compile once annotated, so nothing was generated to evaluate." >&2
    grep -E ": (error|warning):" "$dir/.corpus-optin.log" | head -10 >&2
    status=1
    continue
  fi

  claude_bytes=$(wc -c < "$optin/CLAUDE.md" | tr -d ' ')
  gemini_bytes=$(wc -c < "$optin/GEMINI.md" | tr -d ' ')
  agents_bytes=$(wc -c < "$optin/AGENTS.md" | tr -d ' ')
  locks_members=$(grep -c '"type":"locked"' "$optin/.vibetags-locks" 2>/dev/null || true)
  claude_rules=$(find "$optin/.claude/rules" -name "*.md" 2>/dev/null | wc -l | tr -d ' ')
  gemini_rules=$(find "$optin/.gemini/rules" -name "*.md" 2>/dev/null | wc -l | tr -d ' ')

  # Assertion 5: every opted-in platform produces content. Checked per platform rather than in
  # aggregate: a renderer can be dropped from the registry and the other two will happily cover
  # for it in any total.
  for pf in CLAUDE.md GEMINI.md AGENTS.md; do
    if [ ! -s "$optin/$pf" ]; then
      echo "FAIL: $name opted into $pf and it came back empty." >&2
      echo "      A platform file that exists on disk is the opt-in signal and must be written." >&2
      status=1
    fi
  done
  # Codex needs its own check: AGENTS.md was seeded with a marker pair, so "not empty" is true
  # before VibeTags runs at all. What matters is that something landed between the markers.
  if [ "$agents_bytes" -le 46 ]; then
    echo "FAIL: $name produced an AGENTS.md of $agents_bytes bytes, which is the seeded marker" >&2
    echo "      pair and nothing else. Codex was dropped from the active set rather than written." >&2
    status=1
  fi

  # Assertion 6: the marker pair is present in every aggregate. The markers are the whole of the
  # promise that hand-authored content outside them survives.
  for pf in CLAUDE.md GEMINI.md AGENTS.md; do
    if ! grep -q "VIBETAGS-START" "$optin/$pf" 2>/dev/null; then
      echo "FAIL: $name produced a $pf with no VIBETAGS-START marker." >&2
      status=1
    fi
  done

  # Assertion 6b: both granular directories were written. Opting a directory in and getting
  # nothing back is the failure mode that looks exactly like "this project has no rules".
  if [ "$claude_rules" -lt 1 ] || [ "$gemini_rules" -lt 1 ]; then
    echo "FAIL: $name opted into granular rules and got $claude_rules Claude / $gemini_rules Gemini files." >&2
    status=1
  fi

  # Assertion 6c: the tier split, which is invariant 6 checked on somebody else's code.
  #
  # With a granular directory opted in, the aggregate keeps the safety buckets inline and
  # replaces everything else with a pointer. So @AIPrivacy's reason must still be in CLAUDE.md,
  # and @AIContract's must not - it belongs in the rules directory now. Getting this backwards
  # in either direction is a real defect: safety guardrails that only load when the agent opens
  # the file have become comments, and verbose ones that stay inline are the context bloat the
  # tier model exists to prevent.
  if ! grep -q "FIELD-PRIVACY" "$optin/CLAUDE.md" 2>/dev/null; then
    echo "FAIL: $name dropped a safety-tier guardrail (@AIPrivacy) from the aggregate." >&2
    echo "      Safety buckets stay inline even when the granular directory is opted in." >&2
    status=1
  fi
  if grep -q "METHOD-CONTRACT" "$optin/CLAUDE.md" 2>/dev/null; then
    echo "FAIL: $name kept a non-safety guardrail (@AIContract) inline in the aggregate." >&2
    echo "      With granular opted in the aggregate collapses to a scoped-rules index." >&2
    status=1
  fi
  if ! grep -rq "METHOD-CONTRACT" "$optin/.claude/rules" 2>/dev/null; then
    echo "FAIL: $name moved @AIContract out of the aggregate but not into the rules directory." >&2
    echo "      That guardrail now reaches nobody at all, which is worse than leaving it inline." >&2
    status=1
  fi

  # Assertion 6d: the parameter level survived. It is the finest addressing VibeTags produces
  # and the only level a hand-written rules file cannot express, so it is also the one whose
  # loss would be least likely to be noticed.
  if ! grep -rq "PARAM-LOADBEARING" "$optin/.claude/rules" 2>/dev/null; then
    echo "FAIL: $name lost the parameter-level guardrail (@AILoadBearing on a parameter)." >&2
    status=1
  fi
  # Assertion 6e: the package level, which has no owning member to hang off.
  if ! grep -rq "PACKAGE-SECURE" "$optin/CLAUDE.md" 2>/dev/null; then
    echo "FAIL: $name lost the package-level guardrail (@AISecure on package-info.java)." >&2
    status=1
  fi

  # Assertion 6f: the richness floor. Everything above names one guardrail each, so a change
  # that quietly stopped rendering half the annotation surface would still pass them. This
  # counts how many of the showcase's markers reached any generated file and refuses to let
  # that number fall. Raise it when the showcase grows; never lower it to make a run green.
  showcase_hits=$(grep -rhoE "(PACKAGE|FIELD|METHOD|PARAM|NESTED|CONSTRUCTOR)-[A-Z]+" \
                    "$optin/CLAUDE.md" "$optin/GEMINI.md" "$optin/AGENTS.md" \
                    "$optin/.claude/rules" "$optin/.gemini/rules" 2>/dev/null \
                  | sort -u | wc -l | tr -d ' ')
  if [ "$showcase_hits" -lt "$SHOWCASE_FLOOR" ]; then
    echo "FAIL: $name rendered $showcase_hits of the showcase's guardrails, floor is $SHOWCASE_FLOOR." >&2
    echo "      Something stopped reaching the generated files. Missing:" >&2
    comm -23 <(grep -ohE "(PACKAGE|FIELD|METHOD|PARAM|NESTED|CONSTRUCTOR)-[A-Z]+" \
                 "$ROOT/corpus/showcase/CorpusShowcase.java.tmpl" \
                 "$ROOT/corpus/showcase/package-info.java.tmpl" | sort -u) \
              <(grep -rhoE "(PACKAGE|FIELD|METHOD|PARAM|NESTED|CONSTRUCTOR)-[A-Z]+" \
                 "$optin/CLAUDE.md" "$optin/GEMINI.md" "$optin/AGENTS.md" \
                 "$optin/.claude/rules" "$optin/.gemini/rules" 2>/dev/null | sort -u) >&2
    status=1
  fi
  # Assertion 7: every annotated element reaches the file. Keyed on the unique reason string
  # rather than a predicted element path: predicting the path means reimplementing the thing
  # under test, and the first version of this got it wrong for a method on a nested class.
  while read -r _ kind reason; do
    [ -z "${reason:-}" ] && continue
    if ! grep -q "$reason" "$optin/CLAUDE.md" 2>/dev/null; then
      echo "FAIL: $name annotated a $kind ($reason) but it is absent from the generated CLAUDE.md." >&2
      status=1
    fi
  done <<EOF
$targets
EOF

  # Assertion 8: no type-use annotation anywhere in a generated element identity, in a path
  # attribute or in a filename.
  #
  # This is the assertion the parity auditor structurally cannot make. The auditor compares
  # VibeTags against javac, so when both render "@org.jspecify.annotations.Nullable A" they
  # agree and nothing fires - which is exactly what happened: DeclaredType parameters were
  # stripped through asElement(), but a *type variable* fell through to toString() and kept its
  # annotation. Only generating output and reading it back showed that. Checked in the identity
  # itself, not just in filenames, because .vibetags-locks is matched by the shipped action.
  leaked_paths=$(grep -c 'path="[^"]*@' "$optin/CLAUDE.md" "$optin/GEMINI.md" 2>/dev/null \
                 | awk -F: '{s+=$2} END {print s+0}')
  leaked_locks=$(grep -c '"element":"[^"]*@' "$optin/.vibetags-locks" 2>/dev/null || true)
  leaked_names=$(find "$optin" -name "*@*" -o -name "*Nullable*" 2>/dev/null | wc -l | tr -d ' ')
  leaked=$((leaked_paths + leaked_locks + leaked_names))
  if [ "$leaked" -ne 0 ]; then
    echo "FAIL: $name put a type-use annotation into an element identity" >&2
    echo "      ($leaked_paths path attributes, $leaked_locks lock entries, $leaked_names filenames)." >&2
    grep -h 'path="[^"]*@' "$optin/CLAUDE.md" "$optin/GEMINI.md" 2>/dev/null | head -3 >&2
    grep -h '"element":"[^"]*@' "$optin/.vibetags-locks" 2>/dev/null | head -2 >&2
    echo "      Adding or removing that annotation would rename a committed rule file and stop" >&2
    echo "      every lock and scoped-rules pointer matching, without the signature changing." >&2
    status=1
  fi

  printf "%-16s %-7s %-18s %-9s %-8s %-9s %s\n" \
    "  └ opt-in" "" "exit=$optin_exit" "CLAUDE=$claude_bytes" "locks=$locks_members" \
    "rules=$claude_rules/$gemini_rules" "$(printf '%s' "$targets" | wc -l | tr -d ' ') annotated"
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
