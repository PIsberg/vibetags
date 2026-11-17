#!/usr/bin/env bash
#
# Count what the organization published to Maven Central in one calendar month, against Central's
# monthly limits of 7 releases, 1,167 files and 78 MB (#863).
#
# The limits are per organization, not per repository: async-test-lib and every other group under
# se.deversity draw on the same allowance, so counting this repository's tags missed most of it.
# This reads the month from repo1's public directory listings instead, which need no login.
#
# Central counts each published deployment as a release. A group and version is one deployment when
# its artifacts were published together, which is how vibetags (since publish.yml deploys the release
# reactor) and async-test-lib publish a version; artifacts published minutes apart, as vibetags 1.4.0's
# per-module deployments or a resumed partial publish were, count once per burst (#1111). A rejected
# deployment publishes nothing and is not a release either. Central's own figure is on https://central.sonatype.com/publishing/usage?org=deversity
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

# fetch <path>: the HTML of a repo1 directory listing; says which URL failed and returns 1 when
# it cannot be read
fetch() {
  if ! curl -fsS --retry 2 -m 60 "$BASE/$1$INDEX"; then
    echo "central-month-usage: could not read $BASE/$1$INDEX" >&2
    return 1
  fi
}

# parse_listing: reads a listing's HTML on stdin and prints one line per entry,
# name<TAB>published<TAB>size, where published is "YYYY-MM-DD HH:MM". A directory's name ends in /,
# and a row repo1 renders without a date or size has - for each.
parse_listing() {
  awk '
    /^<a href="/ {
      name = $0
      sub(/^<a href="/, "", name)
      sub(/".*/, "", name)
      if (name == "../") next
      rest = $0
      sub(/^.*<\/a>/, "", rest)
      n = split(rest, field, " ")
      printf "%s\t%s\t%s\n", name, (n >= 3 ? field[1] " " field[2] : "-"), (n >= 1 ? field[n] : "-")
    }'
}

# listing <path>: the entries of a repo1 directory, as parse_listing prints them
listing() {
  local html
  html="$(fetch "$1")" || return 1
  printf '%s\n' "$html" | parse_listing
}

# is_artifact <entries>: an artifact directory has maven-metadata.xml and version directories. A
# Maven plugin's group directory has the metadata too, but its subdirectories are artifacts.
is_artifact() {
  printf '%s\n' "$1" | grep -q $'^maven-metadata\\.xml\t' \
    && printf '%s\n' "$1" | grep -q $'^[0-9][^\t]*/\t'
}

# count_version <group> <version> <path> <published>: records one artifact's version, its file count,
# total bytes and the time repo1 published it
count_version() {
  local entries
  entries="$(listing "$3")" || return 1
  printf '%s\n' "$entries" | awk -F '\t' -v group="$1" -v version="$2" -v published="$4" '
    $1 !~ /\/$/ && $1 != "" { files++; bytes += $3 }
    END { printf "%s\t%s\t%d\t%d\t%s\n", group, version, files, bytes, published }' >>"$RECORDS"
}

# walk <path> <depth>: descends from <path> to every artifact directory and counts its versions
# published in MONTH; the listing gives a version directory the time it was published
walk() {
  local path="$1" depth="$2" entries name date _
  entries="$(listing "$path")" || return 1
  if is_artifact "$entries"; then
    count_artifact "$path" "$entries"
    return
  fi
  [ "$depth" -lt "$MAX_DEPTH" ] || return 0
  while IFS=$'\t' read -r name _ _; do
    case "$name" in */) walk "$path$name" $((depth + 1)) || return 1 ;; esac
  done <<<"$entries"
}

# count_artifact <path> <entries>: counts the artifact's versions published in MONTH under the
# artifact's group, the directory above it
count_artifact() {
  local path="$1" group name date _
  group="${path%/}"
  group="${group%/*}"
  while IFS=$'\t' read -r name date _; do
    case "$name" in [0-9]*/) ;; *) continue ;; esac
    case "$date" in "$MONTH"-*) ;; *) continue ;; esac
    count_version "$group" "${name%/}" "$path$name" "$date" || return 1
  done <<<"$2"
}

walk "$NAMESPACE/" 0 || exit 2

# One release per deployment: a group and version, split where its artifacts' publish times are more
# than MAX_BUNDLE_SPREAD minutes apart (#1111). One bundle lands every artifact in the same minute;
# vibetags 1.4.0's five per-module deployments landed 5 to 15 minutes apart. The spread allows a
# bundle that crosses a minute boundary.
MAX_BUNDLE_SPREAD=2
awk -F '\t' -v month="$MONTH" -v root="$NAMESPACE" -v spread="$MAX_BUNDLE_SPREAD" \
    -v max_releases="$MAX_RELEASES" -v max_files="$MAX_FILES" -v max_bytes="$MAX_BYTES" '
  # minutes into the month of "YYYY-MM-DD HH:MM"
  function minute(published,    part) {
    split(published, part, /[- :]/)
    return part[3] * 1440 + part[4] * 60 + part[5]
  }
  # how many deployments the space-separated minutes in list make, splitting at gaps over spread
  function deployments(list,    t, n, i, j, v, count) {
    n = split(list, t, " ")
    for (i = 2; i <= n; i++) {
      v = t[i] + 0
      for (j = i - 1; j >= 1 && t[j] + 0 > v; j--) t[j + 1] = t[j]
      t[j + 1] = v
    }
    count = 1
    for (i = 2; i <= n; i++) if (t[i] - t[i - 1] > spread) count++
    return count
  }
  {
    key = $1 " " $2
    if (!(key in files)) order[++versions] = key
    files[key] += $3
    bytes[key] += $4
    minutes[key] = minutes[key] " " minute($5)
    total_files += $3
    total_bytes += $4
  }
  END {
    for (i = 1; i <= versions; i++) {
      count[order[i]] = deployments(minutes[order[i]])
      releases += count[order[i]]
    }
    printf "Maven Central usage for %s in %s, from repo1 listings:\n", root, month
    printf "releases %3d of %d\n", releases, max_releases
    printf "files    %3d of %d\n", total_files, max_files
    printf "bytes    %d of %d\n", total_bytes, max_bytes
    for (i = 1; i <= versions; i++) {
      key = order[i]
      printf "%s: %d files, %d bytes%s\n", key, files[key], bytes[key],
        (count[key] > 1 ? " (" count[key] " deployments)" : "")
    }
    if (releases >= max_releases || total_files >= max_files || total_bytes >= max_bytes) {
      print "At or over a monthly limit: another release this month goes over it."
      exit 1
    }
  }' "$RECORDS"
