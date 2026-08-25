#!/usr/bin/env bash
# Runs VibeTags over real third-party Kotlin, Groovy and Scala, built by their own Gradle.
#
# corpus/run-corpus.sh answers "does VibeTags survive code nobody wrote for it". It answers it in
# Java, with javac, because that is the compiler JSR 269 belongs to. This script asks the same
# question of the three other JVM languages people actually ship, and none of them reaches the
# processor the way Java does:
#
#   Kotlin   kapt generates Java stubs and runs the processor over them. KSP does not run JSR 269
#            processors at all, so kapt is the only route.
#   Groovy   groovyc generates stubs the same way, but only when javaAnnotationProcessing is on,
#            and it is off by default in Gradle. A Groovy project that adds VibeTags and changes
#            nothing else generates nothing, silently.
#   Scala    scalac has no JSR 269 support whatsoever. Annotated Scala compiles cleanly and is
#            never seen. Only the Java half of a mixed build reaches the processor.
#
# USAGE.md has said all three for several releases. Until this script none of them had been run
# against code outside this repository. The Scala one is asserted as a negative on purpose: the
# Scala showcase is annotated at every level it can be, and every marker must be absent.
#
# Each repository is built twice by its own wrapper, and the only difference between the two runs
# is corpus/inject-vibetags.init.gradle:
#
#   control    ./gradlew <task>
#   treatment  ./gradlew <task> --init-script corpus/inject-vibetags.init.gradle
#
# Usage:
#   corpus/run-corpus-jvm.sh [name ...]     # all repos, or just the named ones
#
# Env:
#   VIBETAGS_CORPUS_DIR   cache location (default: <repo>/target/corpus-jvm)
#   VIBETAGS_GRADLE_ARGS  extra flags for every Gradle invocation
#
# Requires vibetags-annotations and vibetags installed (see CLAUDE.md "Build and test").
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CACHE="${VIBETAGS_CORPUS_DIR:-$ROOT/target/corpus-jvm}"
MANIFEST="$ROOT/corpus/repos-jvm.tsv"
INIT_SCRIPT="$ROOT/corpus/inject-vibetags.init.gradle"
GRADLE_ARGS="${VIBETAGS_GRADLE_ARGS:---console=plain}"

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
ANN_JAR=$(ls "$HOME"/.m2/repository/se/deversity/vibetags/vibetags-annotations/"$VERSION"/vibetags-annotations-"$VERSION".jar 2>/dev/null | head -1)
if [ ! -f "$ANN_JAR" ]; then
  echo "FAIL: vibetags-annotations $VERSION is not in the local repository." >&2
  echo "      The showcase cannot compile without it; run 'mvn install' in vibetags-annotations/." >&2
  exit 1
fi

mkdir -p "$CACHE"
# Regenerated every run, never restored from a cache. A classpath resolved by a run that failed
# is indistinguishable from a good one once it is on disk, and the Java corpus has already been
# burned by exactly that (corpus/README.md, "a third, in the harness rather than the product").
mvn -q -f "$ROOT/vibetags/pom.xml" dependency:build-classpath \
    -Dmdep.includeScope=runtime -Dmdep.regenerateFile=true \
    "-Dmdep.outputFile=$CACHE/vibetags.cp" >/dev/null 2>&1
if [ ! -s "$CACHE/vibetags.cp" ]; then
  echo "FAIL: could not resolve the processor's runtime classpath." >&2
  exit 1
fi
export VIBETAGS_JARS="$(winpath "$PROC_JAR")$SEP$(cat "$CACHE/vibetags.cp")"
export VIBETAGS_ANN="$(winpath "$ANN_JAR")"

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

DIAG_RE="^(w|e): |^(warning|error):|: (error|warning):|^\[(warn|error)\]"

# Counts compiler diagnostics across four compilers' formats. javac and groovyc say
# "warning:"/"error:", kotlinc prefixes "w:"/"e:", scalac and Zinc bracket them. Gradle's own
# deprecation notices are excluded: they are identical on both sides, and counting them would
# add noise to a comparison meant to isolate one variable.
#
# The `^(warning|error):` arm is not decoration. javac emits its summary diagnostics with no
# file prefix at all - "warning: The following options were not recognized by any processor" is
# the one that matters here - and the first version of this pattern required a colon before the
# word. It counted 0 against 0 on a treatment run that had in fact raised a warning the control
# had not, which is a diagnostic-parity check that cannot see the diagnostic it exists for.
diagnostics() {
  grep -cE "$DIAG_RE" "$1" 2>/dev/null || true
}

# Every marker a showcase declares. The floor is not a hand-written number here: it is derived
# from the template, so growing a showcase raises the bar automatically and a level that stops
# rendering cannot be absorbed by a margin somebody once left in.
declared_markers() {
  grep -ohE "(PACKAGE|FIELD|METHOD|PARAM|NESTED|CONSTRUCTOR)-[A-Z]+" "$@" 2>/dev/null | sort -u
}
generated_markers() {
  grep -rhoE "(PACKAGE|FIELD|METHOD|PARAM|NESTED|CONSTRUCTOR)-[A-Z]+" "$@" 2>/dev/null | sort -u
}

# Markers a language is known not to be able to render, with the reason. Checked in both
# directions: one of these appearing is as much a finding as a normal marker going missing,
# because it means the toolchain changed and the documentation is now wrong the other way.
#
# groovy: groovyc's Java stubs carry types, constructors, methods and parameters - and no fields
# at all. Verified by keeping the stubs (groovyOptions.keepStubs) and reading one: billingEmail,
# authToken and tenantId are simply not in it. So every FIELD-targeted annotation is dropped
# before any processor sees it, @AIPrivacy included, which is a safety-tier guardrail. USAGE.md
# called this route "Full (joint compilation)" until this corpus ran.
excluded_markers() {
  case "$1" in
    groovy) printf '%s\n' "FIELD-PERFORMANCE" "FIELD-PRIVACY" ;;
    *)      : ;;
  esac
}

# The safety-tier marker J6c looks for inline in the aggregate. @AIPrivacy on a field is the
# sharpest version of that question and is used wherever fields survive; Groovy has to fall back
# to @AILocked on a method, for the reason above.
safety_marker() {
  case "$1" in
    groovy) printf '%s' "METHOD-LOCKED" ;;
    *)      printf '%s' "FIELD-PRIVACY" ;;
  esac
}

status=0
repos_run=0
printf "%-18s %-8s %-16s %-11s %-8s %s\n" REPO LANG "EXIT ctrl/vt" "DIAGS c/v" WROTE NOTE

while IFS=$'\t' read -r name url sha lang module src task licence why; do
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
  moddir="$dir/$module"
  srcdir="$moddir/$src"
  if [ ! -d "$srcdir" ]; then
    echo "FAIL: $name has no source root at $module/$src (pinned SHA moved it?)" >&2
    status=1
    continue
  fi
  if [ ! -f "$dir/gradlew" ]; then
    echo "FAIL: $name has no Gradle wrapper, so there is no build to inject into." >&2
    status=1
    continue
  fi
  chmod +x "$dir/gradlew" 2>/dev/null || true

  vt_root="$dir/.corpus-vibetags-root"
  optin="$dir/.corpus-optin-root"
  rm -rf "$vt_root" "$optin"
  mkdir -p "$vt_root"

  # --rerun-tasks is not optional. Gradle's up-to-date checks would let the treatment run skip
  # compilation entirely, because the control had just compiled the same sources from the same
  # inputs. A treatment that never ran a compiler passes every assertion below while testing
  # nothing, which is the same shape of vacuous pass assertion 0 exists for in the Java corpus.
  gradle_run() {
    logfile="$1"; shift
    ( cd "$dir" && ./gradlew $GRADLE_ARGS --rerun-tasks "$@" ) > "$logfile" 2>&1
  }

  # Control: the project's own build, untouched. VIBETAGS_ROOT is what switches the init script
  # on, so the control runs with it unset rather than with a different command line.
  ( unset VIBETAGS_ROOT; gradle_run "$dir/.corpus-ctrl.log" "$task" )
  ctrl_exit=$?

  VIBETAGS_ROOT="$(winpath "$vt_root")" \
    gradle_run "$dir/.corpus-vt.log" "$task" --init-script "$(winpath "$INIT_SCRIPT")"
  vt_exit=$?

  ctrl_diag=$(diagnostics "$dir/.corpus-ctrl.log")
  vt_diag=$(diagnostics "$dir/.corpus-vt.log")
  wrote=$(find "$vt_root" -type f 2>/dev/null | wc -l | tr -d ' ')

  printf "%-18s %-8s %-16s %-11s %-8s %s\n" \
    "$name" "$lang" "$ctrl_exit/$vt_exit" "$ctrl_diag/$vt_diag" "$wrote" ""

  repos_run=$((repos_run + 1))

  # J0. The control has to build. Every assertion here is relative to it, so a repository that
  # cannot build on its own passes exit parity and diagnostic parity trivially, both sides
  # failing identically, and proves nothing.
  if [ "$ctrl_exit" -ne 0 ]; then
    echo "FAIL: $name does not build on its own, so nothing below it means anything." >&2
    echo "      Fix the corpus member - pinned SHA, module path, task or JDK - not the code." >&2
    grep -v "JAVA_TOOL_OPTIONS" "$dir/.corpus-ctrl.log" | tail -15 >&2
    status=1
    continue
  fi
  # J1. Adding VibeTags to somebody's build must not change whether it succeeds.
  if [ "$ctrl_exit" -ne "$vt_exit" ]; then
    echo "FAIL: $name exits $vt_exit with VibeTags and $ctrl_exit without it." >&2
    grep -v "JAVA_TOOL_OPTIONS" "$dir/.corpus-vt.log" | tail -20 >&2
    status=1
  fi
  # J2. A processor that turns a clean build noisy has broken the same promise, more quietly.
  #
  # Compared line by line rather than by count, and that is a deliberate departure from the Java
  # corpus. There, javac runs either way and the only difference is a -processor flag, so "no new
  # diagnostics" is a fair test. Here it is not: switching VibeTags on adds the kapt task graph to
  # a Kotlin build and joint compilation to a Groovy one, and those emit diagnostics of their own
  # about the annotation-processing machinery rather than about VibeTags. Counting them makes the
  # check fail for the wrong reason, and raising a threshold to make it pass would blind it to the
  # diagnostics that do matter.
  #
  # So every new line must either be gone or be named. KAPT_NOISE is the whole allow-list, one
  # entry long, and anything else fails and is printed.
  #
  # That entry: kapt reports the processor options it forwards as unrecognised, vibetags.root
  # among them - an option AIGuardrailProcessor does declare in @SupportedOptions, and does
  # receive, since it prints the resolved root from the same task. examples/kotlin, configured
  # exactly as USAGE.md documents and built on Kotlin 2.4.10, does not produce it; this member,
  # on Kotlin 2.3.10, does. Removing the javac-side configuration so that only the documented
  # `kapt { arguments { ... } }` route remained did not change it either. That is as far as the
  # evidence goes, so it is allow-listed as kapt bookkeeping and tracked as an open question
  # rather than asserted to be harmless.
  KAPT_NOISE="The following options were not recognized by any processor"
  new_diags=$(comm -13 <(grep -hE "$DIAG_RE" "$dir/.corpus-ctrl.log" | sort -u)                        <(grep -hE "$DIAG_RE" "$dir/.corpus-vt.log" | sort -u)               | grep -vF "$KAPT_NOISE")
  if [ -n "$new_diags" ]; then
    echo "FAIL: $name raises diagnostics with VibeTags that it does not raise without it:" >&2
    printf '%s\n' "$new_diags" | head -10 >&2
    echo "      If a line here is annotation-processing machinery rather than VibeTags, say so" >&2
    echo "      in the allow-list above with the evidence. Do not widen it silently." >&2
    status=1
  fi
  # J3. File presence is the only opt-in (tier-1 invariant 1) and none of these repositories
  # opted in. Nothing at all, not even a log: since #487 the log file is created on the first
  # record rather than when the logger is configured.
  if [ "$wrote" -ne 0 ]; then
    echo "FAIL: $name had $wrote file(s) written into a project that never opted in:" >&2
    find "$vt_root" -type f | head -10 >&2
    status=1
  fi

  # -----------------------------------------------------------------------------------------
  # Opt-in phase. Everything above proves VibeTags stays out of the way, which for these three
  # languages is only half the question and not the interesting half: a processor that never
  # ran also stays out of the way perfectly. What follows is the proof that the injection works
  # at all - kapt over Kotlin stubs, joint compilation over Groovy stubs - and, for Scala, the
  # proof that it does not.
  # -----------------------------------------------------------------------------------------
  mkdir -p "$optin/.claude/rules" "$optin/.gemini/rules"
  : > "$optin/CLAUDE.md"
  : > "$optin/GEMINI.md"
  : > "$optin/.vibetags-locks"
  # AGENTS.md is invariant 4's exception: written only as the sole AI config file, or when it
  # already carries a marker pair. Created empty alongside CLAUDE.md and GEMINI.md it would be
  # dropped from the active set silently, and the corpus would report Codex working while
  # generating none of it. Seeding the pair is what a real consumer does.
  printf '%s\n%s\n' '<!-- VIBETAGS-START -->' '<!-- VIBETAGS-END -->' > "$optin/AGENTS.md"

  ( cd "$dir" && git checkout --quiet -- . 2>/dev/null )

  showcase_pkg="vibetagscorpus"
  showcase_dir="$srcdir/$showcase_pkg"
  java_showcase_dir=""
  mkdir -p "$showcase_dir"
  case "$lang" in
    kotlin)
      sed "s/__PACKAGE__/$showcase_pkg/g" "$ROOT/corpus/showcase/CorpusShowcase.kt.tmpl" \
        > "$showcase_dir/CorpusShowcase.kt"
      declared=$(declared_markers "$ROOT/corpus/showcase/CorpusShowcase.kt.tmpl")
      ;;
    groovy)
      sed "s/__PACKAGE__/$showcase_pkg/g" "$ROOT/corpus/showcase/CorpusShowcase.groovy.tmpl" \
        > "$showcase_dir/CorpusShowcase.groovy"
      declared=$(declared_markers "$ROOT/corpus/showcase/CorpusShowcase.groovy.tmpl")
      ;;
    scala)
      # Both halves of one build. The Java one must generate everything; the Scala one must
      # generate nothing, and that pair is the entire point of the Scala member: without the
      # positive half, "nothing came from Scala" and "nothing came at all" look identical.
      sed "s/__PACKAGE__/$showcase_pkg/g" "$ROOT/corpus/showcase/CorpusShowcase.scala.tmpl" \
        > "$showcase_dir/CorpusShowcase.scala"
      java_showcase_dir="$moddir/src/main/java/$showcase_pkg"
      mkdir -p "$java_showcase_dir"
      sed "s/__PACKAGE__/$showcase_pkg/g" "$ROOT/corpus/showcase/CorpusShowcase.java.tmpl" \
        > "$java_showcase_dir/CorpusShowcase.java"
      sed "s/__PACKAGE__/$showcase_pkg/g" "$ROOT/corpus/showcase/package-info.java.tmpl" \
        > "$java_showcase_dir/package-info.java"
      declared=$(declared_markers "$ROOT/corpus/showcase/CorpusShowcase.java.tmpl" \
                                  "$ROOT/corpus/showcase/package-info.java.tmpl")
      ;;
    *)
      echo "FAIL: $name declares an unknown language '$lang'." >&2
      status=1
      rm -rf "$showcase_dir"
      continue
      ;;
  esac

  VIBETAGS_ROOT="$(winpath "$optin")" \
    gradle_run "$dir/.corpus-optin.log" "$task" --init-script "$(winpath "$INIT_SCRIPT")"
  optin_exit=$?

  claude_bytes=$(wc -c < "$optin/CLAUDE.md" | tr -d ' ')
  agents_bytes=$(wc -c < "$optin/AGENTS.md" | tr -d ' ')
  claude_rules=$(find "$optin/.claude/rules" -name "*.md" 2>/dev/null | wc -l | tr -d ' ')
  gemini_rules=$(find "$optin/.gemini/rules" -name "*.md" 2>/dev/null | wc -l | tr -d ' ')
  hits=$(generated_markers "$optin/CLAUDE.md" "$optin/GEMINI.md" "$optin/AGENTS.md" \
                           "$optin/.claude/rules" "$optin/.gemini/rules")
  excluded=$(excluded_markers "$lang")
  expected=$(comm -23 <(printf '%s\n' "$declared" | grep . | sort -u)                       <(printf '%s\n' "$excluded" | grep . | sort -u))
  n_declared=$(printf '%s\n' "$expected" | grep -c . || true)
  n_hits=$(printf '%s\n' "$hits" | grep -c . || true)

  printf "%-18s %-8s %-16s %-11s %-8s %s\n" \
    "  |- opt-in" "" "exit=$optin_exit" "CLAUDE=$claude_bytes" \
    "$claude_rules/$gemini_rules" "markers=$n_hits/$n_declared"

  # J9b, taken before the sources go away. J9 below asserts that the annotated Scala produced no
  # guardrails, and the cheapest way for that to be true is for scalac never to have seen the
  # file at all - a source root that moved, a task that does not compile it, a showcase written
  # somewhere the build ignores. Then J9 passes while proving nothing. Requiring a class file
  # under the Scala output is what separates "scalac compiled it and offered nothing to a
  # processor" from "scalac was never asked".
  scala_classes=0
  if [ "$lang" = scala ]; then
    scala_classes=$(find "$moddir/build" -path "*classes/scala*" -name "CorpusShowcase*.class"                       2>/dev/null | wc -l | tr -d ' ')
  fi

  # Reverted before any assertion can `continue` out of the loop, so a failure never leaves
  # third-party sources modified on disk.
  rm -rf "$showcase_dir"
  [ -n "$java_showcase_dir" ] && rm -rf "$java_showcase_dir"
  ( cd "$dir" && git checkout --quiet -- . 2>/dev/null )

  # J4. The annotated build still has to work. If it does not, nothing was generated and every
  # assertion after this one would be reporting on an empty directory.
  if [ "$optin_exit" -ne 0 ]; then
    echo "FAIL: $name did not build once annotated, so nothing was generated to evaluate." >&2
    grep -v "JAVA_TOOL_OPTIONS" "$dir/.corpus-optin.log" | tail -20 >&2
    status=1
    continue
  fi

  # J5. Every opted-in platform produces content, checked per platform rather than in aggregate:
  # one renderer can be dropped from the registry and the other two will cover for it in a total.
  for pf in CLAUDE.md GEMINI.md AGENTS.md; do
    if [ ! -s "$optin/$pf" ]; then
      echo "FAIL: $name opted into $pf and it came back empty." >&2
      status=1
    fi
  done
  # J5b. Codex needs its own check: AGENTS.md was seeded with a marker pair, so "not empty" is
  # true before VibeTags runs at all. 46 bytes is the seed and nothing else.
  if [ "$agents_bytes" -le 46 ]; then
    echo "FAIL: $name produced an AGENTS.md of $agents_bytes bytes, which is the seeded marker" >&2
    echo "      pair alone. Codex was dropped from the active set rather than written." >&2
    status=1
  fi
  # J6. The marker pair is present in every aggregate: it is the whole of the promise that
  # hand-authored content outside it survives.
  for pf in CLAUDE.md GEMINI.md AGENTS.md; do
    if ! grep -q "VIBETAGS-START" "$optin/$pf" 2>/dev/null; then
      echo "FAIL: $name produced a $pf with no VIBETAGS-START marker." >&2
      status=1
    fi
  done
  # J6b. Both granular directories were written. An opted-in directory that stays empty looks
  # exactly like "this project has no rules".
  if [ "$claude_rules" -lt 1 ] || [ "$gemini_rules" -lt 1 ]; then
    echo "FAIL: $name opted into granular rules and got $claude_rules Claude / $gemini_rules Gemini files." >&2
    status=1
  fi
  # J6c. The tier split, which is invariant 6 checked through three compilers instead of one.
  # With a granular directory opted in, the aggregate keeps the safety buckets inline and
  # replaces everything else with a pointer: @AIPrivacy stays in CLAUDE.md, @AIContract must not.
  safety=$(safety_marker "$lang")
  if ! grep -q "$safety" "$optin/CLAUDE.md" 2>/dev/null; then
    echo "FAIL: $name dropped a safety-tier guardrail ($safety) from the aggregate." >&2
    echo "      Safety buckets stay inline even when the granular directory is opted in." >&2
    status=1
  fi
  if grep -q "METHOD-CONTRACT" "$optin/CLAUDE.md" 2>/dev/null; then
    echo "FAIL: $name kept a non-safety guardrail (@AIContract) inline in the aggregate." >&2
    status=1
  fi
  if ! grep -rq "METHOD-CONTRACT" "$optin/.claude/rules" 2>/dev/null; then
    echo "FAIL: $name moved @AIContract out of the aggregate but not into the rules directory." >&2
    echo "      That guardrail now reaches nobody, which is worse than leaving it inline." >&2
    status=1
  fi
  # J6d. The parameter level, which is the finest addressing VibeTags produces and the one most
  # exposed to a stub generator: a parameter name that does not survive into the stub cannot be
  # addressed at all, and neither kapt nor groovyc is obliged to keep it.
  if ! grep -rq "PARAM-LOADBEARING" "$optin/.claude/rules" 2>/dev/null; then
    echo "FAIL: $name lost the parameter-level guardrail (@AILoadBearing on a parameter)." >&2
    status=1
  fi
  # J6f. Every marker the showcase declares must reach a generated file. Derived from the
  # template rather than written down, so the bar rises with the showcase.
  if [ "$n_hits" -lt "$n_declared" ]; then
    echo "FAIL: $name rendered $n_hits of the $n_declared guardrails expected for $lang." >&2
    echo "      Missing:" >&2
    comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$hits") >&2
    status=1
  fi
  # J6g. The other direction. A marker on the exclusion list is one this language is documented
  # as unable to render, so its appearance means the toolchain changed under us and the
  # documentation is now wrong in the generous direction - which nobody ever notices, because
  # the run is green and more guardrails arrived than were asked for. Delete the exclusion and
  # fix USAGE.md rather than leaving both to drift.
  surprises=$(comm -12 <(printf '%s\n' "$excluded" | grep . | sort -u) <(printf '%s\n' "$hits"))
  if [ -n "$surprises" ]; then
    echo "FAIL: $name rendered guardrails that $lang is documented as unable to render:" >&2
    printf '%s\n' "$surprises" >&2
    echo "      Good news, and still a failure: remove the exclusion in excluded_markers()" >&2
    echo "      and correct the limitation in USAGE.md." >&2
    status=1
  fi
  # J8. No type-use annotation in any generated identity, in a path attribute, a lock entry or
  # a filename. The Java corpus found this defect twice; these compilers reach ElementNaming by
  # different routes and there is no reason to assume they agree.
  leaked_paths=$(grep -c 'path="[^"]*@' "$optin/CLAUDE.md" "$optin/GEMINI.md" 2>/dev/null \
                 | awk -F: '{s+=$2} END {print s+0}')
  leaked_locks=$(grep -c '"element":"[^"]*@' "$optin/.vibetags-locks" 2>/dev/null || true)
  leaked_names=$(find "$optin" -name "*@*" 2>/dev/null | wc -l | tr -d ' ')
  leaked=$((leaked_paths + leaked_locks + leaked_names))
  if [ "$leaked" -ne 0 ]; then
    echo "FAIL: $name put a type-use annotation into an element identity" >&2
    echo "      ($leaked_paths path attributes, $leaked_locks lock entries, $leaked_names filenames)." >&2
    status=1
  fi

  # J9. Scala only, and the reason the Scala member is here at all. Every marker in the
  # annotated .scala file must be absent from every generated file, because scalac never offered
  # them to a processor. If this goes red, either scalac has gained JSR 269 support or VibeTags
  # has found another way in - and USAGE.md's Scala row needs rewriting rather than this check
  # relaxing. J6f above is the positive half, over the Java showcase in the same build.
  if [ "$lang" = scala ]; then
    seen=$(grep -rhoE "SCALA-INVISIBLE-[A-Z-]+" \
             "$optin/CLAUDE.md" "$optin/GEMINI.md" "$optin/AGENTS.md" \
             "$optin/.claude/rules" "$optin/.gemini/rules" 2>/dev/null | sort -u)
    n_seen=$(printf '%s\n' "$seen" | grep -c . || true)
    if [ "$scala_classes" -lt 1 ]; then
      echo "FAIL: $name compiled no Scala showcase classes, so the check below would hold for" >&2
      echo "      the wrong reason: scalac never saw the annotated file." >&2
      echo "      Check module='$module' src='$src' and task='$task' in repos-jvm.tsv." >&2
      status=1
    fi
    if [ "$n_seen" -ne 0 ]; then
      echo "FAIL: $name generated $n_seen guardrail(s) from annotated Scala sources:" >&2
      printf '%s\n' "$seen" >&2
      echo "      USAGE.md says scalac has no JSR 269 support and annotated Scala is never seen." >&2
      echo "      One of the two is now wrong. Do not relax this check to make the run green." >&2
      status=1
    fi
  fi
done < "$MANIFEST"

if [ "$repos_run" -eq 0 ]; then
  echo "FAIL: no repositories ran, so this proves nothing." >&2
  exit 1
fi

if [ "$status" -eq 0 ]; then
  echo "OK: $repos_run JVM-language repositories, control and treatment compared, opt-in read back."
else
  echo "FAILED: see the assertions above." >&2
fi
exit "$status"
