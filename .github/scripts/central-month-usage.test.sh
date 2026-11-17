#!/usr/bin/env bash
#
# Exercise central-month-usage.sh against directory listings written in repo1's own format.
#
# Central's monthly limits are per organization (#863): every group under se.deversity draws on the
# same 7 releases, 1,167 files and 78 MB, so counting this repository's tags missed async-test-lib
# entirely. The script reads the month from repo1's public listings instead. These cases pin what it
# counts as one release (a group and version, which is one bundle), what it leaves out (other months,
# a group-level plugin metadata directory), and that a listing it cannot read fails the run rather
# than reading as zero usage.
#
# Usage: central-month-usage.test.sh [path-to-central-month-usage.sh]
set -u

SCRIPT="${1:-$(cd "$(dirname "$0")" && pwd)/central-month-usage.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0

# url <dir>: a file:// URL curl accepts for a local directory, on Linux and in Git Bash alike
url() {
  if command -v cygpath >/dev/null 2>&1; then
    printf 'file:///%s' "$(cygpath -m "$1")"
  else
    printf 'file://%s' "$1"
  fi
}

# listing <root> <path> <entry>...: writes <root>/<path>/index.html as repo1 renders a directory.
# Each entry is name|date|size: a name ending in / is a directory, and a date of - is a group row.
listing() {
  local root="$1" path="$2" entry name date size
  shift 2
  mkdir -p "$root/$path"
  {
    printf '<html><body><main><pre id="contents">\n<a href="../">../</a>\n'
    for entry in "$@"; do
      IFS='|' read -r name date size <<<"$entry"
      printf '<a href="%s" title="%s">%s</a>%*s%s%*s%s      \n' \
        "$name" "$name" "$name" 20 '' "$date" 9 '' "$size"
    done
    printf '</pre></main></body></html>\n'
  } >"$root/$path/index.html"
}

# run <root> <args>...: runs the script against the tree at <root>; sets OUT and STATUS
run() {
  local root="$1"
  shift
  OUT="$(CENTRAL_BASE_URL="$(url "$root")" CENTRAL_INDEX_FILE=index.html bash "$SCRIPT" "$@" 2>&1)"
  STATUS=$?
}

# expect <name> <status> <pattern>...: the last run exited with <status> and printed every pattern
expect() {
  local name="$1" status="$2" pattern ok=1
  shift 2
  [ "$STATUS" -eq "$status" ] || ok=0
  for pattern in "$@"; do
    printf '%s\n' "$OUT" | grep -Eq -- "$pattern" || ok=0
  done
  if [ "$ok" -eq 1 ]; then
    pass=$((pass + 1))
    echo "ok   $name"
  else
    fail=$((fail + 1))
    echo "FAIL $name (exit $STATUS, wanted $status)"
    printf '%s\n' "$OUT" | sed 's/^/     /'
  fi
}

# refuse <name> <pattern>: the last run did not print <pattern>
refuse() {
  if printf '%s\n' "$OUT" | grep -Eq -- "$2"; then
    fail=$((fail + 1))
    echo "FAIL $1: printed $2"
    printf '%s\n' "$OUT" | sed 's/^/     /'
  else
    pass=$((pass + 1))
    echo "ok   $1"
  fi
}

# An organization with two groups. vibetags published 9.8.7 as one bundle of two artifacts in
# November and 9.8.6 in October. async-test-lib's group directory carries the plugin-prefix
# maven-metadata.xml a Maven plugin group has, beside an artifact that published 2.0.0 in November.
ORG="$TMP/org"
listing "$ORG" se/deversity "vibetags/|-|-" "async-test-lib/|-|-"
listing "$ORG" se/deversity/vibetags "lib-a/|2026-08-01 10:00|-" "lib-b/|2026-08-01 10:00|-"
listing "$ORG" se/deversity/vibetags/lib-a \
  "9.8.6/|2026-10-20 08:00|-" "9.8.7/|2026-11-14 15:19|-" "maven-metadata.xml|2026-11-14 15:23|900"
listing "$ORG" se/deversity/vibetags/lib-a/9.8.6 "lib-a-9.8.6.pom|2026-10-20 08:00|150"
listing "$ORG" se/deversity/vibetags/lib-a/9.8.7 \
  "lib-a-9.8.7.jar|2026-11-14 15:19|1000" "lib-a-9.8.7.pom|2026-11-14 15:19|200"
listing "$ORG" se/deversity/vibetags/lib-b \
  "9.8.7/|2026-11-14 15:19|-" "maven-metadata.xml|2026-11-14 15:23|700"
listing "$ORG" se/deversity/vibetags/lib-b/9.8.7 "lib-b-9.8.7.pom|2026-11-14 15:19|300"
listing "$ORG" se/deversity/async-test-lib \
  "agent/|2026-08-28 15:44|-" "maven-metadata.xml|2026-11-14 15:54|246"
listing "$ORG" se/deversity/async-test-lib/agent \
  "2.0.0/|2026-11-02 09:00|-" "maven-metadata.xml|2026-11-02 09:05|500"
listing "$ORG" se/deversity/async-test-lib/agent/2.0.0 "agent-2.0.0.jar|2026-11-02 09:00|500"

run "$ORG" 2026-11
expect "1: a group and version is one release, across artifacts and groups" 0 \
  '^releases +2 of 7$' '^files +4 of 1167$' '^bytes +2000 of 78000000$'
expect "2: each release is listed with its own files and bytes" 0 \
  '^se/deversity/vibetags 9\.8\.7: 3 files, 1500 bytes$' \
  '^se/deversity/async-test-lib 2\.0\.0: 1 files, 500 bytes$'
refuse "3: a version from another month is not counted" '9\.8\.6'

run "$ORG" 2026-10
expect "4: the month argument selects the month" 0 \
  '^releases +1 of 7$' '^files +1 of 1167$' '^se/deversity/vibetags 9\.8\.6: 1 files, 150 bytes$'

run "$ORG" 2026-09
expect "5: a month with nothing published is zero, not an error" 0 '^releases +0 of 7$' '^files +0 of 1167$'

# Seven versions in one month: the allowance is used up, so another release this month goes over.
FULL="$TMP/full"
listing "$FULL" se/deversity "lib/|-|-"
entries=()
for v in 1 2 3 4 5 6 7; do
  entries+=("9.0.$v/|2026-11-0$v 12:00|-")
  listing "$FULL" "se/deversity/lib/lib/9.0.$v" "lib-9.0.$v.pom|2026-11-0$v 12:00|100"
done
listing "$FULL" se/deversity/lib "lib/|2026-01-01 00:00|-"
listing "$FULL" se/deversity/lib/lib "${entries[@]}" "maven-metadata.xml|2026-11-07 12:05|900"

run "$FULL" 2026-11
expect "6: a month at the release limit exits 1 and says another release goes over" 1 \
  '^releases +7 of 7$' 'over'

run "$TMP/missing" 2026-11
expect "7: a listing that cannot be read fails the run" 2 'could not read'
refuse "8: an unreadable month is not reported as zero usage" '^releases'

run "$ORG" 2026-13
expect "9: a month that is not YYYY-MM is refused" 2 'YYYY-MM'

echo "${pass} passed, ${fail} failed"
[ "$fail" -eq 0 ]
