#!/usr/bin/env bash
# Heat-map how often each test file has needed a change — a maintenance-cost signal,
# not a correctness one. ("Measure your tests' half-life", 50 Quick Ideas to Improve
# Your Tests, ch. 4.)
#
# Usage:
#   tools/test-half-life.sh [--since <git-log-date-spec>] [--top N]
#
#   tools/test-half-life.sh                       # last 6 months, top 15
#   tools/test-half-life.sh --since "1 year ago"   # wider window
#   tools/test-half-life.sh --top 30
#
# A high count here means one of three things, and the script cannot tell you which:
# the feature under test is still evolving (expected), the area it covers is genuinely
# fragile (a real signal), or the test itself is poorly isolated and breaks on unrelated
# changes (a test smell). Read the commits before concluding either way — this is a
# pointer to where to look, not a verdict.
#
# Renamed test files undercount here: git log's default doesn't follow renames across
# a pathspec-filtered walk. Good enough for a heat-map; not a precise history.

set -u

SINCE="6 months ago"
TOP=15
while [ $# -gt 0 ]; do
  case "$1" in
    --since) SINCE="$2"; shift 2 ;;
    --top) TOP="$2"; shift 2 ;;
    *) echo "usage: $0 [--since <date-spec>] [--top N]" >&2; exit 2 ;;
  esac
done

cd "$(git rev-parse --show-toplevel)" || exit 1

TESTROOT="vibetags/src/test/java"
if [ ! -d "$TESTROOT" ]; then
  echo "expected $TESTROOT from repo root" >&2
  exit 1
fi

TMPFILE="$(mktemp)"
trap 'rm -f "$TMPFILE"' EXIT

git log --since="$SINCE" --name-only --pretty=format: -- "$TESTROOT" \
  | grep -E '\.java$' \
  | sort \
  | uniq -c \
  | sort -rn > "$TMPFILE"

TOTAL_COMMITS=$(git log --since="$SINCE" --oneline -- "$TESTROOT" | wc -l | tr -d ' ')
TOTAL_FILES=$(wc -l < "$TMPFILE" | tr -d ' ')

echo "test half-life — commits touching a test file, since \"$SINCE\""
echo "window: $TOTAL_COMMITS commits touched $TOTAL_FILES distinct test files"
echo
printf '%6s  %-55s  %s\n' "count" "file" "last changed"
printf '%6s  %-55s  %s\n' "-----" "----" "------------"

head -n "$TOP" "$TMPFILE" | while read -r count path; do
  [ -z "$path" ] && continue
  last=$(git log -1 --format=%ad --date=short -- "$path")
  name="${path##*/}"
  printf '%6s  %-55s  %s\n' "$count" "$name" "$last"
done

echo
echo "top $TOP of $TOTAL_FILES changed test files in the window. A file here every run of" \
     "this script, release over release, is the one worth reading git log on."
