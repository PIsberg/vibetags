#!/usr/bin/env bash
#
# Check that a Central Portal bundle holds one component, laid out the way Central accepts it.
#
# Central validates a bundle only once it is uploaded, and publish.yml uploads after the tag and the
# GitHub release exist. On 2026-10-08 Central rejected the first module of a release with "Bundle
# has content that does NOT have a .pom file: se/deversity/vibetags/vibetags-annotations": the
# runner image's Maven had moved to 3.10, whose resolver leaves maven-metadata-local.xml and
# _remote.repositories (and on Windows .locks/) in the staging directory, and
# central-publishing-maven-plugin 0.11.0 zips that directory whole. This applies the same layout
# rules before anything is uploaded:
#
#   - every file sits directly in <group path>/<artifactId>/<version>/, with '/' separators
#   - every file is named <artifactId>-<version>[-<classifier>].<extension>
#   - the component has its .pom, and a jar component its main, -sources and -javadoc jars
#   - every file that is not a checksum or a signature has its .md5 and .sha1
#
# Signatures are not checked: the bundles built for pull requests are unsigned, and publish.yml
# signs. Neither is the POM's content (name, licenses, scm), which Central also validates.
#
# Usage: check-central-bundle.sh <bundle.zip> <groupId> <artifactId> <version> <jar|pom>
# Exit:  0 the layout is valid, 1 it is not (every problem is listed), 2 usage or unreadable bundle
set -uo pipefail

if [ "$#" -ne 5 ]; then
  echo "usage: $0 <bundle.zip> <groupId> <artifactId> <version> <jar|pom>" >&2
  exit 2
fi
bundle="$1" group="$2" artifact="$3" version="$4" packaging="$5"
case "$packaging" in
  jar|pom) ;;
  *) echo "packaging must be jar or pom, not '$packaging'" >&2; exit 2 ;;
esac

# The JDK's jar rather than unzip: every machine that builds this project has a JDK, and not every
# one has unzip.
if ! entries="$(jar tf "$bundle")"; then
  echo "cannot list the entries of $bundle" >&2
  exit 2
fi

dir="$(printf '%s' "$group" | tr . /)/$artifact/$version"
stem="$artifact-$version"
problems=()
files=()

while IFS= read -r entry; do
  entry="${entry%$'\r'}"                       # jar on Windows ends its lines with CRLF
  case "$entry" in ''|*/) continue ;; esac    # directory entries carry no content
  case "$entry" in
    "$dir"/*/*) problems+=("$entry: in a subdirectory of $dir/") ;;
    "$dir"/*)
      name="${entry#"$dir"/}"
      case "$name" in
        "$stem".*|"$stem"-*) files+=("$name") ;;
        *) problems+=("$entry: not named $stem[-<classifier>].<extension>") ;;
      esac ;;
    *) problems+=("$entry: outside $dir/ (entry names must use '/')") ;;
  esac
done <<< "$entries"

has() {
  local f
  for f in "${files[@]}"; do
    [ "$f" = "$1" ] && return 0
  done
  return 1
}

required=("$stem.pom")
if [ "$packaging" = jar ]; then
  required+=("$stem.jar" "$stem-sources.jar" "$stem-javadoc.jar")
fi
for f in "${required[@]}"; do
  has "$f" || problems+=("$dir/$f: missing")
done

for f in "${files[@]}"; do
  case "$f" in *.md5|*.sha1|*.sha256|*.sha512|*.asc) continue ;; esac
  for sum in md5 sha1; do
    has "$f.$sum" || problems+=("$dir/$f: no .$sum beside it")
  done
done

if [ "${#problems[@]}" -gt 0 ]; then
  echo "FAIL $bundle is not a bundle Central accepts for $group:$artifact:$version:"
  printf '  %s\n' "${problems[@]}"
  exit 1
fi
echo "OK   $bundle: ${#files[@]} files, all in $dir/"
