#!/usr/bin/env bash
# Reports every third-party version pin in this repository that has a newer stable release.
#
# Read-only: it changes nothing. Applying, verifying and the gates that prove a bump are in
# .claude/skills/bump-dependencies/SKILL.md, which is the procedure this script serves.
#
#   tools/bump-dependencies.sh                       # stable releases only
#   tools/bump-dependencies.sh --include-prereleases # also alpha/beta/M/RC (never applied by default)
#
# Every version pin lives in vibetags-parent/pom.xml as a <name.version> property, toolchains
# included, so the table below maps each property to where its releases are listed. The copies in
# files that cannot read the pom (Gradle wrappers, examples, workflows, README snippets) follow with
# tools/sync-version-mirrors.py. The versions-maven-plugin was tried first and cannot link the
# properties that only appear in plugin configuration (error-prone, nullaway, findsecbugs,
# pitest-junit5), so the table is the source of truth and the script fails loudly if the pom gains a
# property the table lacks (BumpDependenciesTableTest fails first).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PARENT="$ROOT/vibetags-parent/pom.xml"
INCLUDE_PRE=0
[ "${1:-}" = "--include-prereleases" ] && INCLUDE_PRE=1

# property → source, and optionally the release line it must stay on. A source is a Maven Central
# group/artifact path, a plugins.gradle.org path (plugins:...), or one of the two non-Maven feeds
# (gradle:distributions, node:lts).
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
ksp.version                             com/google/devtools/ksp/symbol-processing-api
kotlin-stdlib.version                   org/jetbrains/kotlin/kotlin-stdlib
kotlinx-coroutines.version              org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm
maven-compiler-plugin.version           org/apache/maven/plugins/maven-compiler-plugin
maven-surefire-plugin.version           org/apache/maven/plugins/maven-surefire-plugin
maven-jar-plugin.version                org/apache/maven/plugins/maven-jar-plugin
maven-source-plugin.version             org/apache/maven/plugins/maven-source-plugin
maven-javadoc-plugin.version            org/apache/maven/plugins/maven-javadoc-plugin
maven-shade-plugin.version              org/apache/maven/plugins/maven-shade-plugin
exec-maven-plugin.version               org/codehaus/mojo/exec-maven-plugin
flatten-maven-plugin.version            org/codehaus/mojo/flatten-maven-plugin
build-helper-maven-plugin.version       org/codehaus/mojo/build-helper-maven-plugin
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
ecj.version                             org/eclipse/jdt/ecj
gradle.version                          gradle:distributions
kotlin.version                          org/jetbrains/kotlin/kotlin-gradle-plugin
groovy.version                          org/apache/groovy/groovy
scala.version                           org/scala-lang/scala-library 2.13.
foojay-resolver.version                 plugins:org/gradle/toolchains/foojay-resolver-convention/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin
publish-maven.version                   org/apache/maven/apache-maven 3.
node.version                            node:lts
"

prop() { sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$PARENT" | head -n1; }

# Every version a maven-metadata.xml lists, in publication order.
metadata_versions() {
  curl -sf "$1/maven-metadata.xml" | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g'
}

# Newest release of a source, pre-releases dropped unless asked for, restricted to a release line
# when one is given. Maven metadata lists versions in publication order, so where no line is given
# the last surviving entry is the newest; within a line, version order decides.
latest() {
  local source="$1" line="${2:-}" versions
  case "$source" in
    gradle:distributions)
      versions="$(curl -sf https://services.gradle.org/versions/current)" || { echo "?"; return; }
      printf '%s\n' "$versions" | sed -n 's|.*"version" : "\([^"]*\)".*|\1|p'
      return ;;
    node:lts)
      # Newest first; an LTS entry names its codename where the others say "lts":false. sed -n 1p
      # rather than head: head exits early and pipefail would turn the SIGPIPE into a failure.
      versions="$(curl -sf https://nodejs.org/dist/index.json)" || { echo "?"; return; }
      printf '%s\n' "$versions" | grep -o '"version":"v[0-9.]*"[^}]*"lts":"[A-Za-z]*"' \
        | sed -n 's/.*"version":"v\([0-9.]*\)".*/\1/; 1p'
      return ;;
    plugins:*)
      versions="$(metadata_versions "https://plugins.gradle.org/m2/${source#plugins:}")" || { echo "?"; return; } ;;
    *)
      versions="$(metadata_versions "https://repo1.maven.org/maven2/$source")" || { echo "?"; return; } ;;
  esac
  if [ "$INCLUDE_PRE" = 0 ]; then
    versions="$(printf '%s\n' "$versions" | grep -viE -- '-(alpha|beta|rc|m|ea|cr|pre|snapshot)[-.]?[0-9]*$' || true)"
  fi
  if [ -n "$line" ]; then
    printf '%s\n' "$versions" | grep -F -- "$line" | grep "^${line//./\\.}" | sort -V | tail -n1
  else
    printf '%s\n' "$versions" | tail -n1
  fi
}

updates=0
printf '%-40s %-12s %-12s\n' "property" "pinned" "latest"
while read -r name source line; do
  [ -z "$name" ] && continue
  current="$(prop "$name")"
  if [ -z "$current" ]; then
    echo "ERROR: $name is in this script's table but not in $PARENT" >&2; exit 2
  fi
  newest="$(latest "$source" "$line")"
  [ -z "$newest" ] && newest="?"
  if [ "$newest" != "$current" ] && [ "$newest" != "?" ]; then
    printf '%-40s %-12s %-12s  <- update\n' "$name" "$current" "$newest"; updates=$((updates + 1))
  else
    printf '%-40s %-12s %-12s\n' "$name" "$current" "$newest"
  fi
done <<< "$PINS"

# A property added to the pom but not to the table would otherwise never be reported.
for p in $(grep -o '<[a-zA-Z0-9.-]*\.version>' "$PARENT" | tr -d '<>' | sort -u); do
  if ! grep -q "^$p " <<< "$PINS"; then
    echo "ERROR: $PARENT declares $p but this script has no source for it" >&2; exit 2
  fi
done

echo
echo "publish-maven.sha512 moves with publish-maven.version; the skill says where to take it from."
echo "pre-commit hook revs: run 'python -m pre_commit autoupdate' (no dry-run exists); the checkstyle"
echo "hook needs Docker to verify, so revert its rev unless you can run it."
echo
echo "$updates update(s) available. After editing vibetags-parent/pom.xml, run"
echo "'python tools/sync-version-mirrors.py' to rewrite the copies. Procedure:"
echo ".claude/skills/bump-dependencies/SKILL.md"
