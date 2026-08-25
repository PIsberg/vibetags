#!/usr/bin/env bash
# Checks the documented claim that VibeTags degrades, rather than fails, under a compiler
# that does not expose javac's Tree API.
#
#   docs/PROCESSOR.md : "under genuinely non-javac compilers (ECJ) entries omit position fields"
#   USAGE.md          : "under other compilers (ECJ) entries omit positions and tools fall
#                        back to file-level matching"
#
# Nothing verified either sentence. The paths behind them - SourcePositionResolver's whole
# no-Tree-API branch and the Trees.instance guards around it - are unreachable from a JUnit
# test running under javac, and reaching them from one would mean adding a seam to
# production code purely so a test could fail it (issue #475, option 2). Compiling a real
# fixture under a real ECJ needs no seam and checks the behaviour honestly.
#
# What it asserts, and why each one matters:
#
#   1. ECJ compiles the fixture with the processor on its processor path and exits 0.
#      The promise the whole design rests on is that VibeTags never fails a consumer's
#      build. A compiler it cannot fully serve must still compile.
#   2. Both compilers report the same @AILocked elements. Degrading must cost positions,
#      not guardrails - a lock that silently vanishes under ECJ is a lock nobody enforces.
#   3. Every javac entry carries a position and no ECJ entry does. The javac half is the
#      control: without it the ECJ assertion passes just as well on a broken report that
#      contains nothing at all.
#   4. The generated CLAUDE.md guardrail region is byte-identical between the two. This is
#      the sentence "degrades" is standing on: only @AILocked positions differ, and those
#      live in .vibetags-locks, not here. This assertion was narrower for one commit, while
#      issue #480 - which this check found - was open; the comment above it has the story.
#
# Usage: scripts/ecj-degradation-check.sh
# Requires: a JDK, mvn on PATH, and vibetags-annotations + vibetags installed
#           (see CLAUDE.md "Build and test" for the order).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE="$ROOT/examples/basic"

# Windows shells hand java POSIX paths it cannot read, and want ';' between classpath entries.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ; winpath() { cygpath -w "$1"; } ;;
  *)                    SEP=":" ; winpath() { printf "%s" "$1"; } ;;
esac

read_pom_property() {
  sed -n "s|.*<$1>\(.*\)</$1>.*|\1|p" "$ROOT/vibetags-parent/pom.xml"
}

# One home for the version: the parent POM, like every other third-party pin (invariant 14).
ECJ_VERSION=$(read_pom_property "ecj.version")
if [ -z "$ECJ_VERSION" ]; then
  echo "FAIL: no <ecj.version> in vibetags-parent/pom.xml - the pin this script reads is gone" >&2
  exit 1
fi
VERSION=$(read_pom_property "revision")
echo "ECJ $ECJ_VERSION against VibeTags $VERSION"

PROC_JAR="$ROOT/vibetags/target/vibetags-processor-$VERSION.jar"
if [ ! -f "$PROC_JAR" ]; then
  echo "FAIL: $PROC_JAR not found - run 'mvn clean install' in vibetags/ first" >&2
  exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mvn -q -f "$ROOT/vibetags/pom.xml" dependency:get "-Dartifact=org.eclipse.jdt:ecj:$ECJ_VERSION"
mvn -q -f "$ROOT/vibetags/pom.xml" dependency:build-classpath \
    -Dmdep.includeScope=runtime "-Dmdep.outputFile=$WORK/cp.txt"

REPO=$(mvn -q -f "$ROOT/vibetags/pom.xml" help:evaluate \
       -Dexpression=settings.localRepository -DforceStdout)
ECJ_JAR="$REPO/org/eclipse/jdt/ecj/$ECJ_VERSION/ecj-$ECJ_VERSION.jar"
if [ ! -f "$ECJ_JAR" ]; then
  echo "FAIL: ECJ jar not at $ECJ_JAR after dependency:get" >&2
  exit 1
fi

CP="$(winpath "$PROC_JAR")$SEP$(cat "$WORK/cp.txt")"

# Seeds a compile directory. The fixture's committed generated files are the opt-in: file
# presence is the only way a platform turns on (tier-1 invariant 1), so a bare source tree
# would produce nothing at all and this would compare two empty reports. .vibetags-locks is
# not committed in the fixture, so it is created empty here to opt that report in.
seed() {
  local target="$WORK/$1"
  mkdir -p "$target"
  cp -r "$FIXTURE/." "$target/"
  rm -rf "$target/target" "$target/build" "$target/.gradle" \
         "$target/.vibetags-cache" "$target/.vibetags-mod-_root_" "$target/vibetags.log"
  : > "$target/.vibetags-locks"
  mkdir -p "$target/classes" "$target/gen"
  ( cd "$target" && find src -name "*.java" > sources.txt )
}

seed javac-run
seed ecj-run

echo "== compiling the fixture with javac (the control) =="
( cd "$WORK/javac-run" && javac -proc:full -nowarn \
    -classpath "$CP" -processorpath "$CP" -d classes -s gen \
    "-Avibetags.root=$(winpath "$WORK/javac-run")" \
    @sources.txt )

echo "== compiling the same fixture with ECJ $ECJ_VERSION =="
# Assertion 1. Deliberately not piped into anything: a pipeline reports the last stage's
# status, and this line exists to catch the case where VibeTags breaks somebody's build.
( cd "$WORK/ecj-run" && java -jar "$(winpath "$ECJ_JAR")" \
    -source 21 -target 21 -nowarn \
    -classpath "$CP" -processorpath "$CP" -d classes -s gen \
    "-Avibetags.root=$(winpath "$WORK/ecj-run")" \
    @sources.txt )

JAVAC_LOCKS="$WORK/javac-run/.vibetags-locks"
ECJ_LOCKS="$WORK/ecj-run/.vibetags-locks"
status=0

# Both reports must exist before anything counts them. Without this the counts below come
# back as empty strings, every `[ "$x" -lt 1 ]` fails with "integer expected" rather than
# taking its branch, and - because those tests sit in `if` conditions, where set -e does not
# reach - the script printed OK and exited 0. A check that reports success when its subject
# is missing is worse than no check, so the absence is named here and fails immediately.
for report in "$JAVAC_LOCKS" "$ECJ_LOCKS"; do
  if [ ! -f "$report" ]; then
    echo "FAIL: $report was not produced." >&2
    echo "      .vibetags-locks is opt-in by file presence, so the seed step must create it" >&2
    echo "      and the compile must then write it. One of those did not happen." >&2
    exit 1
  fi
done

# grep -c prints its count and THEN exits 1 when the count is zero, which set -e would treat
# as an error. `|| true` is the whole fix: an `|| echo 0` here appends a second line, the
# variable becomes "0\n0", and every `[ "$x" -ne "$y" ]` below dies with "integer expected"
# inside an `if` condition where set -e cannot see it - so the script printed OK and exited
# 0 with its central assertion never evaluated. The file-existence guard above covers the
# case this was reaching for, where grep prints nothing at all.
count_matches() { grep -c "$1" "$2" || true; }

LOCKED_ENTRY='"type":"locked"'
POSITION_FIELD='"startLine"'

javac_total=$(count_matches "$LOCKED_ENTRY" "$JAVAC_LOCKS")
ecj_total=$(count_matches "$LOCKED_ENTRY" "$ECJ_LOCKS")
javac_positioned=$(count_matches "$POSITION_FIELD" "$JAVAC_LOCKS")
ecj_positioned=$(count_matches "$POSITION_FIELD" "$ECJ_LOCKS")

echo "javac: $javac_total locked entries, $javac_positioned with positions"
echo "ECJ:   $ecj_total locked entries, $ecj_positioned with positions"

# The control. Without it, every assertion below passes on an empty report.
if [ "$javac_total" -lt 1 ]; then
  echo "FAIL: javac produced no @AILocked entries, so this run compares nothing." >&2
  echo "      Either the fixture stopped carrying @AILocked, or the processor did not run." >&2
  status=1
fi
if [ "$javac_positioned" -ne "$javac_total" ]; then
  echo "FAIL: javac reported $javac_positioned of $javac_total entries with a position." >&2
  echo "      Positions come from the Tree API and javac has one, so this is a regression" >&2
  echo "      in SourcePositionResolver rather than anything to do with ECJ." >&2
  status=1
fi

# Assertion 2: degrading costs positions, not guardrails.
if [ "$ecj_total" -ne "$javac_total" ]; then
  echo "FAIL: ECJ reported $ecj_total locked entries against javac's $javac_total." >&2
  echo "      Under a compiler with no Tree API the report must lose positions and nothing" >&2
  echo "      else; a missing entry is a lock that nobody enforces." >&2
  status=1
fi

# Assertion 3: the degradation is the documented one.
if [ "$ecj_positioned" -ne 0 ]; then
  echo "FAIL: ECJ reported $ecj_positioned entries carrying a position field." >&2
  echo "      docs/PROCESSOR.md and USAGE.md both say entries omit positions under ECJ." >&2
  echo "      Either ECJ grew a Tree API or a position is being fabricated." >&2
  status=1
fi

# Assertion 4: the whole marker region is byte-identical. Positions are the ONLY thing that
# may differ between the compilers, and positions do not appear in the aggregate files.
#
# This started life narrower - guardrail prose and type-level paths only - because a full
# diff failed: member element paths rendered differently under the two compilers, since
# ElementNaming built them from Element.toString(), whose format javax.lang.model leaves to
# the implementation.
#
#     javac : com.example.security.SecurityConfig.getKeyRotationHours()
#     ECJ   : com.example.security.SecurityConfig.public int getKeyRotationHours()
#
# That was issue #480, found by this check on its first run and since fixed: ElementNaming
# now derives the signature structurally and targets javac's rendering, so ECJ converges on
# it. The assertion is back to what it should always have been, which is also what keeps
# #480 fixed - a regression there shows up here as a diff, in the one place that compiles
# under both compilers.
region() { sed -n "/VIBETAGS-START/,/VIBETAGS-END/p" "$1"; }
region "$WORK/javac-run/CLAUDE.md" > "$WORK/javac.region"
region "$WORK/ecj-run/CLAUDE.md" > "$WORK/ecj.region"

if [ ! -s "$WORK/javac.region" ]; then
  echo "FAIL: no VIBETAGS marker region in the javac CLAUDE.md - nothing to compare." >&2
  status=1
elif ! diff -u "$WORK/javac.region" "$WORK/ecj.region" > "$WORK/region.diff"; then
  echo "FAIL: the generated guardrail region differs between javac and ECJ." >&2
  echo "      Only @AILocked positions may degrade, and they do not appear in this file." >&2
  echo "      A member path in the diff below means ElementNaming has gone back to" >&2
  echo "      Element.toString() somewhere - that is issue #480 regressing." >&2
  head -60 "$WORK/region.diff" >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "OK: the processor degrades under ECJ exactly as documented -"
  echo "    $ecj_total locked entries preserved, 0 positions,"
  echo "    and $(wc -l < "$WORK/javac.region") lines of guardrail region byte-identical"
  echo "    to the javac control."
fi
exit "$status"
