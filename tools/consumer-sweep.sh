#!/usr/bin/env bash
# Build every downstream consumer of VibeTags against a chosen VibeTags version and report
# honestly which ones pass.
#
# Usage:
#   tools/consumer-sweep.sh <vibetags-version> [repo ...]
#
#   tools/consumer-sweep.sh <version>             # every known consumer
#   tools/consumer-sweep.sh <version> blindbean   # just one
#
# The examples deliberately say <version> rather than a real one: a literal release version
# here would be a second place the release has to be remembered, and ReleaseScriptCoverageTest
# fails the build for exactly that.
#
# What it does per repo: fetch, branch off origin/main (never off whatever happens to be
# checked out), rewrite the VibeTags version in every place that repo declares it, build,
# and report the build's real exit code plus any drift in generated guardrail files.
#
# What it deliberately does not do: commit, push, or open anything. It leaves each repo on
# its sweep branch with the bump uncommitted so a human can look before any of that.
#
# Traps this encodes, each of which cost a debugging round the first time:
#
#   * Use the repo's own ./mvnw or ./gradlew. blindbean's enforcer requires Maven >= 3.9.0
#     and the Maven on PATH here is 3.8.6, which fails before the build starts.
#   * Never let a pipe eat the exit code. Every build writes to a log and the status is read
#     immediately from $?, never from the tail of a pipeline.
#   * Every consumer is swept in a git worktree, not in its checkout. Switching the branch of
#     a checkout somebody is working in is the destructive move to avoid, and on a developer
#     machine a consumer checkout is dirty far more often than not.
#   * A version bump that changes generated guardrail files is a finding, not noise: it means
#     the new VibeTags renders differently and the consumer's committed files are now stale.
#   * A failing test is not a regression until it has been shown to pass on the base. Rerun
#     any failure on origin/main before blaming VibeTags; blindbean's FheAsyncConcurrencyTest
#     fails roughly one run in three on both.

set -u

ROOT="${VIBETAGS_CONSUMER_ROOT:-/c/dev/private}"
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: $0 <vibetags-version> [repo ...]" >&2
  exit 2
fi
shift || true

# repo : build tool : maven goals : gradle tasks [: required jdk, one major or a range like 21-25]
# Maven and Gradle need separate commands: "verify" is a Maven lifecycle phase and Gradle
# has no such task, which showed up as a spurious FAIL for common-license-lib.
CONSUMERS="
blindbean:maven:clean verify:
codekarta:both:clean verify:clean build:21-25
common-license-lib:both:clean verify:clean build
skill3:gradle::clean build
async-test-lib:both:clean verify:clean build
"

# Consumers swept in their checkout rather than a worktree. Empty, and meant to stay empty:
# the worktree is the default because it is the safe mode, not because a particular repo is
# contended. A name here opts that repo back into `checkout -B`, and back into being skipped
# whenever its tree is dirty.
#
# Overridable from the environment so the tests can still exercise the checkout path and its
# dirty guard, which no consumer reaches by default any more. A seam, not a setting: nothing in
# a real sweep sets it.
IN_PLACE_REPOS="${VIBETAGS_SWEEP_IN_PLACE:-}"
BRANCH="chore/vibetags-${VERSION}"
LOGDIR="${TMPDIR:-/tmp}/vibetags-sweep"
mkdir -p "$LOGDIR"

want() {
  local target="$1"; shift
  [ "$#" -eq 0 ] && return 0
  for w in "$@"; do [ "$w" = "$target" ] && return 0; done
  return 1
}

# Rewrite every place a repo declares the VibeTags version. Returns 1 only if the repo
# declares the version nowhere this understands, which means the repo's layout moved and this
# script needs updating rather than the sweep quietly testing the old version.
#
# Writes through a temp file and only replaces the original when the bytes actually change.
# `sed -i` rewrites unconditionally, and on Windows that silently converts a CRLF file to LF,
# which then shows up as phantom drift in files the bump never had any business touching.
bump() {
  local dir="$1" version="$2" declared=0
  for f in pom.xml build.gradle build.gradle.kts gradle.properties; do
    [ -f "$dir/$f" ] || continue
    grep -qE 'vibetags\.version>|vibetagsVersion' "$dir/$f" || continue
    declared=1
    sed -E \
      -e "s#(<vibetags\.version>)[^<]+(</vibetags\.version>)#\1${version}\2#" \
      -e "s#(val vibetagsVersion = \")[^\"]+(\")#\1${version}\2#" \
      -e "s#(ext\.vibetagsVersion = ')[^']+(')#\1${version}\2#" \
      -e "s#(vibetagsVersion=).*#\1${version}#" \
      "$dir/$f" > "$dir/$f.sweeptmp"
    if cmp -s "$dir/$f" "$dir/$f.sweeptmp"; then
      rm -f "$dir/$f.sweeptmp"          # already at this version, or nothing to change
    else
      mv "$dir/$f.sweeptmp" "$dir/$f"
    fi
  done
  return $((1 - declared))
}

# Is the version under test actually published? This decides whether a Gradle consumer that
# declares only mavenCentral() can resolve it at all, and it is worth answering once up front
# rather than diagnosing a resolution failure per repo. A missing curl is not fatal: assume
# published, which is the behaviour this script had before.
UNPUBLISHED=0
GRADLE_INIT=""
if command -v curl >/dev/null 2>&1; then
  if ! curl -sfI --max-time 20 \
       "https://repo1.maven.org/maven2/se/deversity/vibetags/vibetags-processor/${VERSION}/vibetags-processor-${VERSION}.pom" \
       >/dev/null 2>&1; then
    UNPUBLISHED=1
    echo "note: ${VERSION} is not on Maven Central. Gradle builds get a temporary mavenLocal()"
    echo "      through an init script so they can resolve it; no consumer file is edited."
    echo "      Install the processor locally first or the sweep tests nothing."
    echo

    # An init script rather than a rewrite of the consumer's build file. The rewrite this
    # replaced skipped any file that already contained the string mavenLocal(), and codekarta
    # has one behind `if (project.hasProperty("useMavenLocal"))` — present in the text, off in
    # the build. The sweep therefore injected nothing and reported codekarta FAIL for a
    # resolution error that says nothing about VibeTags.
    #
    # pluginManagement is deliberately left alone: declaring even one repository there removes
    # Gradle's implicit gradlePluginPortal(), and codekarta's shadow plugin then resolves
    # nowhere. Only dependency repositories need the local one.
    GRADLE_INIT="$LOGDIR/mavenlocal-init.gradle"
    cat > "$GRADLE_INIT" <<'INITEOF'
beforeSettings { settings ->
    settings.dependencyResolutionManagement.repositories.mavenLocal()
}
allprojects {
    repositories {
        mavenLocal()
    }
}
INITEOF
  fi
fi
# Every row goes through row(), so the summary and the exit status are counted from the same
# place the table is printed and cannot drift from it. The loop below is fed by a heredoc rather
# than a pipe, so it runs in this shell and these counters survive it.
attempted=0
failures=0
skips=0

row() {
  printf '%-22s %-8s %-9s %s\n' "$1" "$2" "$3" "$4"
  case "$2" in
    PASS)       attempted=$((attempted + 1)) ;;
    FAIL|ERROR) attempted=$((attempted + 1)); failures=$((failures + 1)) ;;
    SKIP)       skips=$((skips + 1)) ;;
  esac
}

printf '%-22s %-8s %-9s %s\n' REPO RESULT EXIT NOTES
printf '%s\n' "----------------------------------------------------------------------"

# IFS=: rather than word-splitting $CONSUMERS: the build command contains a space, and
# "for entry in $CONSUMERS" would split "clean verify" into two entries.
while IFS=: read -r repo tool mvncmd gradlecmd reqjdk; do
  [ -z "$repo" ] && continue

  want "$repo" "$@" || continue
  [ -d "$ROOT/$repo/.git" ] || { row "$repo" SKIP - "not a git repo under $ROOT"; continue; }

  # Is this repo swept in a worktree? Answered before the dirty check, because the answer
  # decides whether that check applies at all.
  contended=1
  case " $IN_PLACE_REPOS " in
    *" $repo "*) contended=0 ;;
  esac

  # Refuse to sweep a repo with uncommitted work. `checkout -B` switches the branch of the
  # very checkout those edits live in, so it either fails or drags them along, and neither
  # is this script's call to make.
  #
  # A worktree repo is exempt, and that exemption is the point rather than a loophole:
  # `git worktree add` builds a separate directory from origin/main and never touches the
  # checkout or its index, so a dirty checkout is simply not its business. That is why every
  # consumer is swept that way now.
  #
  # The guard has produced a partial sweep that read like a complete one twice. In #617 it
  # skipped async-test-lib on every run, the only consumer that commits its .vibetags-mod-*
  # sidecars and the file class #590 was found through. On 2026-09-22 it skipped the four
  # repos that were not yet worktree-swept, leaving one result of five (#790). The footer
  # prints identically either way, which is what makes the failure quiet.
  if [ "$contended" -eq 0 ] && [ -n "$(git -C "$ROOT/$repo" status --porcelain)" ]; then
    row "$repo" SKIP - "working tree dirty; commit or stash first"
    continue
  fi

  # Consumers that pin a JDK (codekarta's enforcer allows [21,26)) need a JDK in that range, or
  # the build fails before VibeTags runs. The column is one major ("21") or an inclusive range
  # ("21-25"). JDK<low>_HOME, when set, is always used, so no machine path is committed. When it
  # is unset, the default java is built on if its major is in range and skipped by name
  # otherwise. Matching only the exact low major skipped codekarta on a default JDK 25 it builds
  # on fine (#737, #743).
  repo_java_home=""
  jdk_low=""
  if [ -n "${reqjdk:-}" ]; then
    jdk_low="${reqjdk%%-*}"
    jdk_high="${reqjdk##*-}"
    jdk_var="JDK${jdk_low}_HOME"
    eval "repo_java_home=\"\${$jdk_var:-}\""
    if [ -z "$repo_java_home" ]; then
      cur_jdk=""
      if command -v java >/dev/null 2>&1; then
        cur_jdk=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
      fi
      in_range=0
      case "$cur_jdk" in
        ''|*[!0-9]*) ;;
        *) [ "$cur_jdk" -ge "$jdk_low" ] && [ "$cur_jdk" -le "$jdk_high" ] && in_range=1 ;;
      esac
      if [ "$in_range" -eq 0 ]; then
        row "$repo" SKIP - "requires JDK $reqjdk, default is ${cur_jdk:-none} (set $jdk_var)"
        continue
      fi
    elif [ ! -d "$repo_java_home" ]; then
      row "$repo" SKIP - "$jdk_var ($repo_java_home) not found"
      continue
    fi
  fi

  git -C "$ROOT/$repo" fetch -q origin || true

  # A contended repo is swept in a detached worktree so its checkout is never touched.
  work="$ROOT/$repo"
  wt=""
  detached=0
  if [ "$contended" -eq 1 ]; then
    # Look up any existing worktree that currently holds $BRANCH
    existing_wt=$(git -C "$ROOT/$repo" worktree list --porcelain | awk -v branch="refs/heads/$BRANCH" '
      $1 == "worktree" { wt = substr($0, 10) }
      $1 == "branch" && $2 == branch { print wt }
    ')
    if [ -n "$existing_wt" ]; then
      wt_name="${existing_wt##*[/\\]}"
      if [ "$wt_name" = "wt-$repo" ]; then
        git -C "$ROOT/$repo" worktree remove --force "$existing_wt" 2>/dev/null || true
        rm -rf "$existing_wt"
        git -C "$ROOT/$repo" worktree prune
      else
        # The branch is checked out somewhere else, which on this machine means the consumer
        # checkout is parked on a previous sweep's branch with that sweep's bump still
        # uncommitted. `git worktree add -B` cannot take a branch another worktree holds, and
        # skipping here threw away the measurement over a name. Measure detached instead: the
        # branch only ever existed so a result could become a consumer PR, and there is no
        # result to turn into one if the repo is skipped.
        detached=1
      fi
    fi

    wt="$LOGDIR/wt-$repo"
    rm -rf "$wt"
    git -C "$ROOT/$repo" worktree prune
    # -B, not -b: the branch survives the worktree being removed, so a second sweep of the
    # same version died with "a branch named ... already exists" and reported ERROR for a
    # repo whose build was never attempted. The non-worktree path already used checkout -B
    # for exactly this reason.
    if [ "$detached" -eq 1 ]; then
      git -C "$ROOT/$repo" worktree add -q --detach "$wt" origin/main || {
        row "$repo" ERROR - "detached worktree add failed"; continue; }
    else
      git -C "$ROOT/$repo" worktree add -q -B "$BRANCH" "$wt" origin/main || {
        row "$repo" ERROR - "worktree add failed"; continue; }
    fi
    work="$wt"
  else
    git -C "$work" checkout -q -B "$BRANCH" origin/main || {
      row "$repo" ERROR - "checkout failed"; continue; }
  fi

  if ! bump "$work" "$VERSION"; then
    row "$repo" ERROR - "no version declaration matched; update this script"
    continue
  fi

  # Prefer the repo's wrapper; only blindbean ships mvnw, and its enforcer needs Maven
  # >= 3.9.0, which the mvn on PATH is not. Repos without a wrapper get the system tool.
  if [ -x "$work/mvnw" ]; then MVN=./mvnw; else MVN=mvn; fi
  if [ -x "$work/gradlew" ]; then GRADLE=./gradlew; else GRADLE=gradle; fi

  # Gradle resolves only from the repositories a build declares, and no consumer declares the
  # local one for real. That is correct for them and fatal here: sweeping a version that has
  # not been published yet means the artifact exists only in the local repository, so those
  # builds die at dependency resolution without compiling a line. Reporting that as FAIL sends
  # someone hunting a regression that cannot exist. $GRADLE_INIT supplies mavenLocal() from
  # outside the build, so nothing the consumer owns is edited and nothing has to be restored.
  ginit=""
  [ -n "$GRADLE_INIT" ] && ginit="--init-script $GRADLE_INIT"

  log="$LOGDIR/$repo.log"
  status=0
  # </dev/null on every build. Gradle reads stdin, and stdin here is the heredoc feeding the
  # `while read` loop below — so one successful Gradle build consumed the remaining repo lines
  # and the sweep stopped early, having printed its "nothing was committed" footer as though it
  # had finished. A sweep that silently covers two repos of five is worse than one that fails.
  saved_java_home="${JAVA_HOME:-}"
  saved_path="$PATH"
  if [ -n "$repo_java_home" ]; then
    export JAVA_HOME="$repo_java_home"
    export PATH="$repo_java_home/bin:$PATH"
  fi

  case "$tool" in
    maven) (cd "$work" && $MVN -q $mvncmd) > "$log" 2>&1 </dev/null || status=$? ;;
    gradle) (cd "$work" && $GRADLE -q $ginit $gradlecmd) > "$log" 2>&1 </dev/null || status=$? ;;
    both)
      (cd "$work" && $MVN -q $mvncmd) > "$log" 2>&1 </dev/null || status=$?
      if [ "$status" -eq 0 ]; then
        (cd "$work" && $GRADLE -q $ginit $gradlecmd) > "$log.gradle" 2>&1 </dev/null || status=$?
      fi
      ;;
  esac

  if [ -n "$saved_java_home" ]; then
    export JAVA_HOME="$saved_java_home"
  else
    unset JAVA_HOME
  fi
  export PATH="$saved_path"

  # Generated-guardrail drift, counted from `git diff --numstat` rather than `git status`.
  # status lists a file whose line endings changed even when its text did not, and on Windows
  # that is most of them; numstat compares after git's own EOL normalisation, so a non-empty
  # numstat means the new VibeTags genuinely renders something different and the consumer's
  # committed files are stale. EOL-only churn is reported separately rather than as drift.
  #
  # dependency-reduced-pom.xml is excluded for the same reason as pom.xml: maven-shade
  # regenerates it from the POM on every build, so it echoes the version this script just
  # bumped. Counting it reported codekarta as "GUARDRAIL DRIFT in 1 file(s)" for a one-line
  # version diff in a generated pom, which is the bump working, not the processor rendering
  # something new.
  drift=$(git -C "$work" diff --numstat -- . \
            ':(exclude)pom.xml' ':(exclude)build.gradle' \
            ':(exclude)build.gradle.kts' ':(exclude)gradle.properties' \
            ':(exclude)**/dependency-reduced-pom.xml' ':(exclude)dependency-reduced-pom.xml' \
            | wc -l | tr -d ' ')
  eolonly=$(git -C "$work" status --porcelain | wc -l | tr -d ' ')

  toolchain_err=0
  if grep -q "RequireJavaVersion" "$log" 2>/dev/null || ([ -f "$log.gradle" ] && grep -q "RequireJavaVersion" "$log.gradle" 2>/dev/null); then
    toolchain_err=1
  fi

  if [ "$status" -eq 0 ]; then
    result=PASS
  elif [ "$toolchain_err" -eq 1 ]; then
    result=ERROR
  else
    result=FAIL
  fi
  notes="log: $log"
  [ "$toolchain_err" -eq 1 ] && notes="toolchain: RequireJavaVersion failed; $notes"
  [ -n "$repo_java_home" ] && notes="JDK $jdk_low via $jdk_var; $notes"
  [ -n "$ginit" ] && [ "$tool" != maven ] && notes="mavenLocal() via init script; $notes"
  [ "$eolonly" -gt 0 ] && notes="${eolonly} file(s) touched; $notes"
  [ "$detached" -eq 1 ] && notes="detached (branch held by the checkout, no PR branch); $notes"
  [ "$drift" -gt 0 ] && notes="GUARDRAIL DRIFT in $drift file(s); $notes"
  row "$repo" "$result" "$status" "$notes"
done <<EOF
$CONSUMERS
EOF

total=$((attempted + skips))
printf '\n%s\n' "Built $attempted of $total consumer(s): $failures failed, $skips skipped."
printf '%s\n' "Nothing was committed, pushed or opened. Review each repo, then decide."

# The status says what happened, because the caller is usually an agent following the
# consumer-regression-suite skill, and one that checks the exit code and reports "sweep passed"
# is behaving correctly. Ending on the printf above made that report wrong for both bad
# outcomes: every consumer failing, and every consumer being skipped so nothing was built at
# all, were each indistinguishable from a clean pass (#806).
#
# A skip is not a milder failure, it is the absence of a measurement, so it gets its own code:
# the caller can tell "a consumer is broken" from "nothing is known about that consumer".
if [ "$failures" -gt 0 ]; then
  exit 1
fi
if [ "$skips" -gt 0 ]; then
  exit 2
fi
exit 0
