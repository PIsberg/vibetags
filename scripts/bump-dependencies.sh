#!/usr/bin/env bash
# Reports every third-party version pin in this repository that has a newer stable release.
#
# Read-only: it changes nothing. Applying, verifying and the places each pin is mirrored are
# in .claude/skills/bump-dependencies/SKILL.md, which is the procedure this script serves.
#
#   scripts/bump-dependencies.sh                       # stable releases only
#   scripts/bump-dependencies.sh --include-prereleases # also alpha/beta/M/RC (never applied by default)
#
# Every version pin lives in vibetags-parent/pom.xml as a <name.version> property, so the table
# below maps each property to the Maven Central path whose maven-metadata.xml is authoritative.
# The versions-maven-plugin was tried first and cannot link the properties that only appear in
# plugin configuration (error-prone, nullaway, findsecbugs, pitest-junit5), so the table is the
# source of truth and the script fails loudly if the pom gains a property the table lacks.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PARENT="$ROOT/vibetags-parent/pom.xml"
INCLUDE_PRE=0
[ "${1:-}" = "--include-prereleases" ] && INCLUDE_PRE=1

# property → Maven Central group/artifact path
PINS="
jspecify.version                        org/jspecify/jspecify
slf4j.version                           org/slf4j/slf4j-api
logback.version                         ch/qos/logback/logback-classic
junit.version                           org/junit/jupiter/junit-jupiter
junit.platform.version                  org/junit/platform/junit-platform-launcher
mockito.version                         org/mockito/mockito-core
archunit.version                        com/tngtech/archunit/archunit-junit5
async-test-lib.version                  se/deversity/async-test-lib/async-test-lib
snakeyaml.version                       org/yaml/snakeyaml
jmh.version                             org/openjdk/jmh/jmh-core
maven-compiler-plugin.version           org/apache/maven/plugins/maven-compiler-plugin
maven-surefire-plugin.version           org/apache/maven/plugins/maven-surefire-plugin
maven-jar-plugin.version                org/apache/maven/plugins/maven-jar-plugin
maven-source-plugin.version             org/apache/maven/plugins/maven-source-plugin
maven-javadoc-plugin.version            org/apache/maven/plugins/maven-javadoc-plugin
maven-shade-plugin.version              org/apache/maven/plugins/maven-shade-plugin
exec-maven-plugin.version               org/codehaus/mojo/exec-maven-plugin
flatten-maven-plugin.version            org/codehaus/mojo/flatten-maven-plugin
maven-pmd-plugin.version                org/apache/maven/plugins/maven-pmd-plugin
pmd.version                             net/sourceforge/pmd/pmd-java
maven-checkstyle-plugin.version         org/apache/maven/plugins/maven-checkstyle-plugin
spotbugs-maven-plugin.version           com/github/spotbugs/spotbugs-maven-plugin
maven-enforcer-plugin.version           org/apache/maven/plugins/maven-enforcer-plugin
findsecbugs-plugin.version              com/h3xstream/findsecbugs/findsecbugs-plugin
jacoco-maven-plugin.version             org/jacoco/jacoco-maven-plugin
pitest-maven.version                    org/pitest/pitest-maven
pitest-junit5-plugin.version            org/pitest/pitest-junit5-plugin
error-prone.version                     com/google/errorprone/error_prone_core
nullaway.version                        com/uber/nullaway/nullaway
cyclonedx-maven-plugin.version          org/cyclonedx/cyclonedx-maven-plugin
central-publishing-maven-plugin.version org/sonatype/central/central-publishing-maven-plugin
maven-gpg-plugin.version                org/apache/maven/plugins/maven-gpg-plugin
"

prop() { sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$PARENT" | head -n1; }

# Newest version listed on Maven Central, pre-releases dropped unless asked for. The metadata
# lists versions in publication order, so the last surviving line is the newest.
latest() {
  local versions
  versions="$(curl -sf "https://repo1.maven.org/maven2/$1/maven-metadata.xml" \
    | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g')" || { echo "?"; return; }
  if [ "$INCLUDE_PRE" = 0 ]; then
    versions="$(printf '%s\n' "$versions" | grep -viE -- '-(alpha|beta|rc|m|ea|cr|pre|snapshot)[-.]?[0-9]*$' || true)"
  fi
  printf '%s\n' "$versions" | tail -n1
}

updates=0
printf '%-40s %-12s %-12s\n' "property" "pinned" "latest"
while read -r name path; do
  [ -z "$name" ] && continue
  current="$(prop "$name")"
  if [ -z "$current" ]; then
    echo "ERROR: $name is in this script's table but not in $PARENT" >&2; exit 2
  fi
  newest="$(latest "$path")"
  if [ "$newest" != "$current" ] && [ "$newest" != "?" ]; then
    printf '%-40s %-12s %-12s  <- update\n' "$name" "$current" "$newest"; updates=$((updates + 1))
  else
    printf '%-40s %-12s %-12s\n' "$name" "$current" "$newest"
  fi
done <<< "$PINS"

# A property added to the pom but not to the table would otherwise never be reported.
for p in $(grep -o '<[a-zA-Z0-9.-]*\.version>' "$PARENT" | tr -d '<>' | sort -u); do
  if ! grep -q "^$p " <<< "$PINS"; then
    echo "ERROR: $PARENT declares $p but this script has no Maven Central path for it" >&2; exit 2
  fi
done

echo
echo "Toolchains pinned outside the parent pom:"
wrapper="$(sed -n 's:.*gradle-\(.*\)-bin.zip:\1:p' "$ROOT/vibetags/gradle/wrapper/gradle-wrapper.properties")"
gradle_now="$(curl -sf https://services.gradle.org/versions/current | sed -n 's|.*"version" : "\([^"]*\)".*|\1|p')"
printf '%-40s %-12s %-12s%s\n' "gradle wrapper (6 files)" "$wrapper" "$gradle_now" "$([ "$wrapper" != "$gradle_now" ] && echo '  <- update')"
kotlin="$(sed -n 's:.*kotlin("jvm") version "\([^"]*\)".*:\1:p' "$ROOT/examples/kotlin/build.gradle.kts")"
kotlin_now="$(latest org/jetbrains/kotlin/kotlin-stdlib)"
printf '%-40s %-12s %-12s%s\n' "kotlin (examples/kotlin + README snippets)" "$kotlin" "$kotlin_now" "$([ "$kotlin" != "$kotlin_now" ] && echo '  <- update')"
groovy="$(sed -n "s|.*org.apache.groovy:groovy:\([^']*\)'.*|\1|p" "$ROOT/examples/groovy/build.gradle")"
groovy_now="$(latest org/apache/groovy/groovy)"
printf '%-40s %-12s %-12s%s\n' "groovy (examples/groovy)" "$groovy" "$groovy_now" "$([ "$groovy" != "$groovy_now" ] && echo '  <- update')"
scala="$(sed -n "s|.*org.scala-lang:scala-library:\([^']*\)'.*|\1|p" "$ROOT/examples/scala/build.gradle")"
scala_now="$(curl -sf https://repo1.maven.org/maven2/org/scala-lang/scala-library/maven-metadata.xml | grep -o '<version>2\.13\.[0-9]*</version>' | sed 's/<[^>]*>//g' | tail -n1)"
printf '%-40s %-12s %-12s%s\n' "scala 2.13 line (examples/scala)" "$scala" "$scala_now" "$([ "$scala" != "$scala_now" ] && echo '  <- update')"
echo
echo "pre-commit hook revs: run 'python -m pre_commit autoupdate' (no dry-run exists); the checkstyle"
echo "hook needs Docker to verify, so revert its rev unless you can run it."
echo
echo "$updates Maven property update(s) available. Applying: .claude/skills/bump-dependencies/SKILL.md"
