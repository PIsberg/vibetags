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
# From 14 on it checks what else Central validates (#949): each pom's required elements, and with
# --signed, an .asc beside every file that verifies against it. The signed cases make a throwaway
# key in a private GNUPGHOME, so they need gpg on PATH and leave the user's keyring alone.
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

# pom <groupId> <artifactId> <version>: a pom with every element Central requires
pom() {
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$1</groupId>
  <artifactId>$2</artifactId>
  <version>$3</version>
  <name>Example</name>
  <description>An example component.</description>
  <url>https://example.invalid/</url>
  <licenses><license><name>MIT License</name><url>https://opensource.org/licenses/MIT</url></license></licenses>
  <developers><developer><id>dev</id><name>A Developer</name></developer></developers>
  <scm>
    <connection>scm:git:https://example.invalid/repo.git</connection>
    <url>https://example.invalid/repo</url>
  </scm>
</project>
EOF
}

# tree <name> <path>...: a directory holding a small file at each path; a .pom gets a valid pom
tree() {
  local name="$1" root="$TMP/$1" p file artifact version
  shift
  rm -rf "$root"
  for p in "$@"; do
    mkdir -p "$root/$(dirname "$p")"
    file="${p##*/}"
    case "$file" in
      *.pom)
        version="${p%/*}"; version="${version##*/}"
        artifact="${file%-"$version".pom}"
        pom "$G" "$artifact" "$version" > "$root/$p" ;;
      *) printf 'x' > "$root/$p" ;;
    esac
  done
}

# pack <name>: zip the tree with the JDK's jar
pack() {
  rm -f "$TMP/$1.zip"
  (cd "$TMP/$1" && jar --create --no-manifest --file "$TMP/$1.zip" .)
}

# bundle <name> <path>...: tree, then pack
bundle() {
  tree "$@"
  pack "$1"
}

# component <dir> <file>...: each file of the component directory, with its .md5 and .sha1
component() {
  local dir="$1" f
  shift
  for f in "$@"; do
    printf '%s\n' "$dir/$f" "$dir/$f.md5" "$dir/$f.sha1"
  done
}

check() { # name expected_exit expected_text [--signed] component_spec...
  local name="$1" want="$2" needle="$3" out rc opts=()
  shift 3
  if [ "${1:-}" = --signed ]; then
    opts=(--signed)
    shift
  fi
  out="$(bash "$SCRIPT" "${opts[@]}" "$TMP/$name.zip" "$@" 2>&1)"; rc=$?
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

# 14-18. a pom Central would reject for its content (#949). Each starts from a valid component and
# breaks one element of its pom.
broken_pom() { # name sed-expression
  tree "$1" "${jar_component[@]}"
  sed -i.orig "$2" "$TMP/$1/$DIR/$A-$V.pom" && rm -f "$TMP/$1/$DIR/$A-$V.pom.orig"
  pack "$1"
}
broken_pom no-scm '/<scm>/,/<\/scm>/d'
check no-scm 1 "$DIR/$A-$V.pom: no <scm><url>" "$JAR"
broken_pom empty-description 's|<description>.*</description>|<description> </description>|'
check empty-description 1 "$DIR/$A-$V.pom: no <description>" "$JAR"
broken_pom no-licenses '/<licenses>/d'
check no-licenses 1 "$DIR/$A-$V.pom: no <licenses><license><name>" "$JAR"
broken_pom no-developers '/<developers>/d'
check no-developers 1 "$DIR/$A-$V.pom: no <developers><developer>" "$JAR"
broken_pom wrong-artifact "s|<artifactId>$A</artifactId>|<artifactId>$B</artifactId>|"
check wrong-artifact 1 "$DIR/$A-$V.pom: artifactId is $B, not $A" "$JAR"

# 19-22. --signed: every file that is not a checksum or a signature needs an .asc that verifies.
if command -v gpg >/dev/null 2>&1; then
  export GNUPGHOME="$TMP/gnupg"
  mkdir -p "$GNUPGHOME" && chmod 700 "$GNUPGHOME"
  gpg --batch --quiet --passphrase '' --quick-gen-key 'Bundle check test <bundle-check@example.invalid>' \
    default default never 2>/dev/null

  # sign <name>: an armored detached signature beside every file of the tree that needs one
  sign() {
    local f
    while IFS= read -r f; do
      gpg --batch --quiet --yes --armor --detach-sign --output "$f.asc" "$f"
    done < <(find "$TMP/$1" -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.asc')
  }

  tree signed "${jar_component[@]}"
  sign signed
  pack signed
  check signed 0 "OK" --signed "$JAR"

  # 20. the same bundle without one signature
  rm -f "$TMP/signed/$DIR/$A-$V-sources.jar.asc"
  pack signed
  check signed 1 "$DIR/$A-$V-sources.jar: no .asc beside it" --signed "$JAR"

  # 21. a signature made over different content
  sign signed
  printf 'tampered' > "$TMP/signed/$DIR/$A-$V.jar"
  pack signed
  check signed 1 "$DIR/$A-$V.jar: its .asc does not verify" --signed "$JAR"

  # 22. without --signed the same bundles are judged on layout and pom alone
  check clean 0 "OK" "$JAR"

  gpgconf --kill gpg-agent >/dev/null 2>&1 || true
else
  echo "FAIL  signed: gpg is not on PATH, so the signature cases did not run (not run is not passed)"
  fail=$((fail + 1))
fi

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
