#!/usr/bin/env bash
#
# Exercise check-central-bundle.sh against bundles built to a known layout.
#
# The case that matters is maven310: the files central-publishing-maven-plugin 0.11.0 zipped beside
# the artifacts once the runner image's Maven was 3.10, one of each kind (maven-metadata-local.xml
# and _remote.repositories on Linux, plus .locks/ on Windows). Central rejected that bundle on
# 2026-10-08, after the release was tagged; this suite is what says the checker rejects it first.
#
# A release is one bundle holding every module (#863), so the cases from 10 on hold two components.
#
# Usage: check-central-bundle.test.sh [path-to-check-central-bundle.sh]
set -u

SCRIPT="${1:-$(cd "$(dirname "$0")" && pwd)/check-central-bundle.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

G=se.deversity.vibetags
A=vibetags-example
B=vibetags-other
V=9.8.7
DIR="se/deversity/vibetags/$A/$V"
DIRB="se/deversity/vibetags/$B/$V"
JAR="$G:$A:$V:jar"

pass=0
fail=0

# bundle <name> <path>...: a zip holding one small file at each path, built with the JDK's jar
bundle() {
  local name="$1" root="$TMP/$1" p
  shift
  for p in "$@"; do
    mkdir -p "$root/$(dirname "$p")"
    printf 'x' > "$root/$p"
  done
  (cd "$root" && jar --create --no-manifest --file "$TMP/$name.zip" .)
}

# component <dir> <file>...: each file of the component directory, with its .md5 and .sha1
component() {
  local dir="$1" f
  shift
  for f in "$@"; do
    printf '%s\n' "$dir/$f" "$dir/$f.md5" "$dir/$f.sha1"
  done
}

check() { # name expected_exit expected_text component_spec...
  local name="$1" want="$2" needle="$3" out rc
  shift 3
  out="$(bash "$SCRIPT" "$TMP/$name.zip" "$@" 2>&1)"; rc=$?
  if [ "$rc" -eq "$want" ] && printf '%s' "$out" | grep -qF -- "$needle"; then
    echo "PASS  $name: $needle (exit $rc)"
    pass=$((pass + 1))
  else
    echo "FAIL  $name: exit=$rc want=$want, expected the output to contain: $needle"
    printf '%s\n' "$out" | sed 's/^/        /'
    fail=$((fail + 1))
  fi
}

mapfile -t jar_component < <(component "$DIR" "$A-$V.jar" "$A-$V.pom" "$A-$V-sources.jar" \
  "$A-$V-javadoc.jar" "$A-$V-cyclonedx.json")
signatures=("$DIR/$A-$V.jar.asc" "$DIR/$A-$V.pom.asc")

# 1. the layout Maven 3.9 gives, signed as publish.yml signs it
bundle clean "${jar_component[@]}" "${signatures[@]}"
check clean 0 "OK" "$JAR"

# 2. a pom-packaged component, the BOM's shape
mapfile -t pom_component < <(component "$DIR" "$A-$V.pom")
bundle bom "${pom_component[@]}" "$DIR/$A-$V.pom.asc"
check bom 0 "OK" "$G:$A:$V:pom"

# 3. what Maven 3.10's resolver left in the staging directory, which Central rejected
bundle maven310 "${jar_component[@]}" \
  "se/deversity/vibetags/$A/maven-metadata-local.xml" \
  ".locks/artifact~$G~$A~jar~$V.lock" \
  "$DIR/_remote.repositories" "$DIR/_remote.repositories.md5" "$DIR/_remote.repositories.sha1"
check maven310 1 "se/deversity/vibetags/$A/maven-metadata-local.xml: outside every component directory" "$JAR"
check maven310 1 ".locks/artifact~$G~$A~jar~$V.lock: outside every component directory" "$JAR"
check maven310 1 "$DIR/_remote.repositories: not named $A-$V" "$JAR"

# 4. no .pom, the component Central's message names
mapfile -t no_pom < <(component "$DIR" "$A-$V.jar" "$A-$V-sources.jar" "$A-$V-javadoc.jar")
bundle no-pom "${no_pom[@]}"
check no-pom 1 "$DIR/$A-$V.pom: missing" "$JAR"

# 5. a jar component without its javadoc jar
mapfile -t no_javadoc < <(component "$DIR" "$A-$V.jar" "$A-$V.pom" "$A-$V-sources.jar")
bundle no-javadoc "${no_javadoc[@]}"
check no-javadoc 1 "$DIR/$A-$V-javadoc.jar: missing" "$JAR"

# 6. a file without its SHA-1
mapfile -t no_sha1 < <(component "$DIR" "$A-$V.pom" "$A-$V-sources.jar" "$A-$V-javadoc.jar")
bundle no-sha1 "${no_sha1[@]}" "$DIR/$A-$V.jar" "$DIR/$A-$V.jar.md5"
check no-sha1 1 "$DIR/$A-$V.jar: no .sha1 beside it" "$JAR"

# 7. the right files under another version's directory
bundle other-version "se/deversity/vibetags/$A/9.8.8/$A-$V.pom"
check other-version 1 "se/deversity/vibetags/$A/9.8.8/$A-$V.pom: outside every component directory" "$G:$A:$V:pom"

# 8. a file in a subdirectory of the component directory
bundle nested "${pom_component[@]}" "$DIR/extra/$A-$V.txt"
check nested 1 "$DIR/extra/$A-$V.txt: in a subdirectory of $DIR/" "$G:$A:$V:pom"

# 9. an unknown packaging is a usage error, not a verdict on the bundle
check clean 2 "packaging must be jar or pom" "$G:$A:$V:war"

# 10. a release bundle: a jar component and a pom component side by side
mapfile -t other_pom < <(component "$DIRB" "$B-$V.pom")
bundle release "${jar_component[@]}" "${other_pom[@]}"
check release 0 "OK" "$JAR" "$G:$B:$V:pom"
check release 0 "2 components" "$JAR" "$G:$B:$V:pom"

# 11. a component in the bundle that the release does not list
check release 1 "$DIRB/$B-$V.pom: outside every component directory" "$JAR"

# 12. a listed component with nothing in the bundle, a module the release silently dropped
bundle lone "${jar_component[@]}"
check lone 1 "$DIRB/$B-$V.pom: missing" "$JAR" "$G:$B:$V:pom"

# 13. a component spec that is not group:artifact:version:packaging
check clean 2 "group:artifact:version:packaging" "$G:$A:$V"
check clean 2 "usage"

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
