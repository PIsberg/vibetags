#!/usr/bin/env bash
# Build every downstream consumer of VibeTags against a chosen VibeTags version and report
# honestly which ones pass.
#
# Usage:
#   scripts/consumer-sweep.sh <vibetags-version> [repo ...]
#
#   scripts/consumer-sweep.sh <version>             # every known consumer
#   scripts/consumer-sweep.sh <version> blindbean   # just one
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
#   * async-test-lib gets a git worktree, not a checkout. Another agent works in that tree;
#     switching its branch underneath them is the destructive move to avoid.
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

# repo : build tool : maven goals : gradle tasks
# Maven and Gradle need separate commands: "verify" is a Maven lifecycle phase and Gradle
# has no such task, which showed up as a spurious FAIL for common-license-lib.
CONSUMERS="
blindbean:maven:clean verify:
codekarta:both:clean verify:clean build
common-license-lib:both:clean verify:clean build
skill3:gradle::clean build
async-test-lib:both:clean verify:clean build
"

WORKTREE_REPOS="async-test-lib"
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

printf '%-22s %-8s %-9s %s\n' REPO RESULT EXIT NOTES
printf '%s\n' "----------------------------------------------------------------------"

# IFS=: rather than word-splitting $CONSUMERS: the build command contains a space, and
# "for entry in $CONSUMERS" would split "clean verify" into two entries.
while IFS=: read -r repo tool mvncmd gradlecmd; do
  [ -z "$repo" ] && continue

  want "$repo" "$@" || continue
  [ -d "$ROOT/$repo/.git" ] || { printf '%-22s %-8s %-9s %s\n' "$repo" SKIP - "not a git repo under $ROOT"; continue; }

  # Refuse to sweep a repo with uncommitted work. Branching off origin/main under someone's
  # edits either fails or drags them along, and neither is this script's call to make.
  if [ -n "$(git -C "$ROOT/$repo" status --porcelain)" ]; then
    printf '%-22s %-8s %-9s %s\n' "$repo" SKIP - "working tree dirty; commit or stash first"
    continue
  fi

  git -C "$ROOT/$repo" fetch -q origin || true

  # A contended repo is swept in a detached worktree so its checkout is never touched.
  work="$ROOT/$repo"
  wt=""
  case " $WORKTREE_REPOS " in
    *" $repo "*)
      wt="$LOGDIR/wt-$repo"
      rm -rf "$wt"
      git -C "$ROOT/$repo" worktree prune
      git -C "$ROOT/$repo" worktree add -q -b "$BRANCH" "$wt" origin/main || {
        printf '%-22s %-8s %-9s %s\n' "$repo" ERROR - "worktree add failed"; continue; }
      work="$wt"
      ;;
    *)
      git -C "$work" checkout -q -B "$BRANCH" origin/main || {
        printf '%-22s %-8s %-9s %s\n' "$repo" ERROR - "checkout failed"; continue; }
      ;;
  esac

  if ! bump "$work" "$VERSION"; then
    printf '%-22s %-8s %-9s %s\n' "$repo" ERROR - "no version declaration matched; update this script"
    continue
  fi

  # Prefer the repo's wrapper; only blindbean ships mvnw, and its enforcer needs Maven
  # >= 3.9.0, which the mvn on PATH is not. Repos without a wrapper get the system tool.
  if [ -x "$work/mvnw" ]; then MVN=./mvnw; else MVN=mvn; fi
  if [ -x "$work/gradlew" ]; then GRADLE=./gradlew; else GRADLE=gradle; fi

  log="$LOGDIR/$repo.log"
  status=0
  case "$tool" in
    maven) (cd "$work" && $MVN -q $mvncmd) > "$log" 2>&1 || status=$? ;;
    gradle) (cd "$work" && $GRADLE -q $gradlecmd) > "$log" 2>&1 || status=$? ;;
    both)
      (cd "$work" && $MVN -q $mvncmd) > "$log" 2>&1 || status=$?
      if [ "$status" -eq 0 ]; then
        (cd "$work" && $GRADLE -q $gradlecmd) > "$log.gradle" 2>&1 || status=$?
      fi
      ;;
  esac

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

  if [ "$status" -eq 0 ]; then
    result=PASS
  else
    result=FAIL
  fi
  notes="log: $log"
  [ "$eolonly" -gt 0 ] && notes="${eolonly} file(s) touched; $notes"
  [ "$drift" -gt 0 ] && notes="GUARDRAIL DRIFT in $drift file(s); $notes"
  printf '%-22s %-8s %-9s %s\n' "$repo" "$result" "$status" "$notes"
done <<EOF
$CONSUMERS
EOF

printf '\n%s\n' "Nothing was committed, pushed or opened. Review each repo, then decide."
