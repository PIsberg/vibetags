#!/usr/bin/env bash
#
# Check that a Central Portal bundle holds the listed components, laid out the way Central accepts
# them.
#
# Central validates a bundle only once it is uploaded, and publish.yml uploads after the tag and the
# GitHub release exist. On 2026-10-08 Central rejected the first module of a release with "Bundle
# has content that does NOT have a .pom file: se/deversity/vibetags/vibetags-annotations": the
# runner image's Maven had moved to 3.10, whose resolver leaves maven-metadata-local.xml and
# _remote.repositories (and on Windows .locks/) in the staging directory, and
# central-publishing-maven-plugin 0.11.0 zips that directory whole. This applies the same layout
# rules before anything is uploaded:
#
#   - every file sits directly in the <group path>/<artifactId>/<version>/ of a listed component,
#     with '/' separators
#   - every file is named <artifactId>-<version>[-<classifier>].<extension>
#   - every listed component has its .pom, and a jar component its main, -sources and -javadoc jars
#   - every file that is not a checksum or a signature has its .md5 and .sha1
#
# A release is one bundle holding every published module (#863), so the bundle is checked against
# the whole list: a module the release dropped is a missing .pom, and a module nobody listed is a
# file outside every component directory.
#
# Signatures are not checked: the bundles built for pull requests are unsigned, and publish.yml
# signs. Neither is the POM's content (name, licenses, scm), which Central also validates.
#
# Usage: check-central-bundle.sh <bundle.zip> <groupId>:<artifactId>:<version>:<jar|pom>...
# Exit:  0 the layout is valid, 1 it is not (every problem is listed), 2 usage or unreadable bundle
set -uo pipefail

usage="usage: $0 <bundle.zip> <groupId>:<artifactId>:<version>:<jar|pom>..."
if [ "$#" -lt 2 ]; then
  echo "$usage" >&2
  exit 2
fi
bundle="$1"
shift

dirs=()        # each component's directory in the bundle
stems=()       # each component's <artifactId>-<version>
packagings=()  # each component's packaging
for spec in "$@"; do
  IFS=: read -r group artifact version packaging extra <<< "$spec"
  if [ -z "${group:-}" ] || [ -z "${artifact:-}" ] || [ -z "${version:-}" ] || [ -z "${packaging:-}" ] \
     || [ -n "${extra:-}" ]; then
    echo "'$spec' is not group:artifact:version:packaging" >&2
    echo "$usage" >&2
    exit 2
  fi
  case "$packaging" in
    jar|pom) ;;
    *) echo "packaging must be jar or pom, not '$packaging' (in '$spec')" >&2; exit 2 ;;
  esac
  dirs+=("$(printf '%s' "$group" | tr . /)/$artifact/$version")
  stems+=("$artifact-$version")
  packagings+=("$packaging")
done

# The JDK's jar rather than unzip: every machine that builds this project has a JDK, and not every
# one has unzip.
if ! entries="$(jar tf "$bundle")"; then
  echo "cannot list the entries of $bundle" >&2
  exit 2
fi

problems=()
files=()       # every accepted file, as <component dir>/<name>

while IFS= read -r entry; do
  entry="${entry%$'\r'}"                       # jar on Windows ends its lines with CRLF
  case "$entry" in ''|*/) continue ;; esac    # directory entries carry no content
  owner=-1
  for i in "${!dirs[@]}"; do
    case "$entry" in "${dirs[$i]}"/*) owner="$i"; break ;; esac
  done
  if [ "$owner" -lt 0 ]; then
    problems+=("$entry: outside every component directory (entry names must use '/')")
    continue
  fi
  dir="${dirs[$owner]}" stem="${stems[$owner]}"
  name="${entry#"$dir"/}"
  case "$name" in
    */*) problems+=("$entry: in a subdirectory of $dir/") ;;
    "$stem".*|"$stem"-*) files+=("$entry") ;;
    *) problems+=("$entry: not named $stem[-<classifier>].<extension>") ;;
  esac
done <<< "$entries"

has() {
  local f
  for f in "${files[@]}"; do
    [ "$f" = "$1" ] && return 0
  done
  return 1
}

for i in "${!dirs[@]}"; do
  dir="${dirs[$i]}" stem="${stems[$i]}"
  required=("$stem.pom")
  if [ "${packagings[$i]}" = jar ]; then
    required+=("$stem.jar" "$stem-sources.jar" "$stem-javadoc.jar")
  fi
  for f in "${required[@]}"; do
    has "$dir/$f" || problems+=("$dir/$f: missing")
  done
done

for f in "${files[@]}"; do
  case "$f" in *.md5|*.sha1|*.sha256|*.sha512|*.asc) continue ;; esac
  for sum in md5 sha1; do
    has "$f.$sum" || problems+=("$f: no .$sum beside it")
  done
done

if [ "${#problems[@]}" -gt 0 ]; then
  echo "FAIL $bundle is not a bundle Central accepts for $*:"
  printf '  %s\n' "${problems[@]}"
  exit 1
fi
echo "OK   $bundle: ${#files[@]} files in ${#dirs[@]} components: ${dirs[*]}"
