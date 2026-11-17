#!/usr/bin/env bash
#
# Count what the organization published to Maven Central in one calendar month, against Central's
# monthly limits of 7 releases, 1,167 files and 78 MB (#863).
#
# The limits are per organization, not per repository: async-test-lib and every other group under
# se.deversity draw on the same allowance, so counting this repository's tags missed most of it.
# This reads the month from repo1's public directory listings instead, which need no login.
#
# One release is one group and version. Central counts each published deployment as a release, and
# vibetags (since publish.yml deploys the release reactor) and async-test-lib both publish a version
# as one bundle per group. A version that took two deployments, such as a resumed partial publish,
# is counted once, so the count is a floor; a rejected deployment publishes nothing and is not a
# release either. Central's own figure is on https://central.sonatype.com/publishing/usage?org=deversity
# (owner login). Bytes are compared with 78 MB as 78,000,000, the stricter reading.
#
# Usage: central-month-usage.sh [YYYY-MM]     (default: the current month, UTC)
# Environment:
#   CENTRAL_BASE_URL    repository root to read (default https://repo1.maven.org/maven2)
#   CENTRAL_NAMESPACE   group path to count (default se/deversity)
#   CENTRAL_INDEX_FILE  appended to each directory URL; the tests' fixture trees use index.html
# Exit: 0 when every count is under its limit, 1 when one is at or over it (a release this month
# goes over), 2 when the month cannot be read: a listing that failed is never reported as zero.
set -u

MONTH="${1:-$(date -u +%Y-%m)}"
BASE="${CENTRAL_BASE_URL:-https://repo1.maven.org/maven2}"
NAMESPACE="${CENTRAL_NAMESPACE:-se/deversity}"
INDEX="${CENTRAL_INDEX_FILE:-}"

MAX_RELEASES=7
MAX_FILES=1167
MAX_BYTES=78000000
# Groups nest (se/deversity/common/...), but not deeply; this bounds the walk if a listing loops.
MAX_DEPTH=4

if ! [[ "$MONTH" =~ ^[0-9]{4}-(0[1-9]|1[0-2])$ ]]; then
  echo "central-month-usage: the month must be YYYY-MM, got '$MONTH'" >&2
  exit 2
fi

RECORDS="$(mktemp)"
trap 'rm -f "$RECORDS"' EXIT

# listing <path>: one line per entry of a repo1 directory listing, name<TAB>date<TAB>size. A
# directory's name ends in /, and a row repo1 renders without a date or size has - for each.
listing() {
  local body
  if ! body="$(curl -fsS --retry 2 -m 60 "$BASE/$1$INDEX")"; then
    echo "central-month-usage: could not read $BASE/$1$INDEX" >&2
    return 1
  fi
  printf '%s\n' "$body" | awk '
    /^<a href="/ {
      name = $0
      sub(/^<a href="/, "", name)
      sub(/".*/, "", name)
      if (name == "../") next
      rest = $0
      sub(/^.*<\/a>/, "", rest)
      n = split(rest, field, " ")
      printf "%s\t%s\t%s\n", name, (n >= 3 ? field[1] : "-"), (n >= 1 ? field[n] : "-")
    }'
}

# is_artifact <entries>: an artifact directory has maven-metadata.xml and version directories. A
# Maven plugin's group directory has the metadata too, but its subdirectories are artifacts.
is_artifact() {
  printf '%s\n' "$1" | grep -q $'^maven-metadata\\.xml\t' \
    && printf '%s\n' "$1" | grep -q $'^[0-9][^\t]*/\t'
}

# count_version <group> <version> <path>: records the version's file count and total bytes
count_version() {
  local entries
  entries="$(listing "$3")" || return 1
  printf '%s\n' "$entries" | awk -F '\t' -v group="$1" -v version="$2" '
    $1 !~ /\/$/ && $1 != "" { files++; bytes += $3 }
    END { printf "%s\t%s\t%d\t%d\n", group, version, files, bytes }' >>"$RECORDS"
}

# walk <path> <depth>: descends from <path> to every artifact directory and counts its versions
# published in MONTH; the listing gives a version directory the time it was published
walk() {
  local path="$1" depth="$2" entries name date size
  entries="$(listing "$path")" || return 1
  if is_artifact "$entries"; then
    while IFS=$'\t' read -r name date size; do
      case "$name" in [0-9]*/) ;; *) continue ;; esac
      case "$date" in "$MONTH"-*) ;; *) continue ;; esac
      count_version "${path%/}" "${name%/}" "$path$name" || return 1
    done <<<"$entries"
    return 0
  fi
  [ "$depth" -lt "$MAX_DEPTH" ] || return 0
  while IFS=$'\t' read -r name date size; do
    case "$name" in */) walk "$path$name" $((depth + 1)) || return 1 ;; esac
  done <<<"$entries"
}

walk "$NAMESPACE/" 0 || exit 2

# One release per group and version, summed across that group's artifacts.
awk -F '\t' -v month="$MONTH" -v root="$NAMESPACE" \
    -v max_releases="$MAX_RELEASES" -v max_files="$MAX_FILES" -v max_bytes="$MAX_BYTES" '
  {
    group = $1
    sub(/\/[^\/]*$/, "", group)
    key = group " " $2
    if (!(key in files)) order[++releases] = key
    files[key] += $3
    bytes[key] += $4
    total_files += $3
    total_bytes += $4
  }
  END {
    printf "Maven Central usage for %s in %s, from repo1 listings:\n", root, month
    printf "releases %3d of %d\n", releases, max_releases
    printf "files    %3d of %d\n", total_files, max_files
    printf "bytes    %d of %d\n", total_bytes, max_bytes
    for (i = 1; i <= releases; i++) {
      printf "%s: %d files, %d bytes\n", order[i], files[order[i]], bytes[order[i]]
    }
    if (releases >= max_releases || total_files >= max_files || total_bytes >= max_bytes) {
      print "At or over a monthly limit: another release this month goes over it."
      exit 1
    }
  }' "$RECORDS"
