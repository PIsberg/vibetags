#!/usr/bin/env bash
#
# Build the Maven Central bundle of every module publish.yml deploys, without publishing anything,
# and check each one with check-central-bundle.sh.
#
# Only Central ever validated a bundle, and publish.yml only uploads after the release is tagged.
# On 2026-10-08 that is how a release learned that the runner image's new Maven (3.10) made
# central-publishing-maven-plugin 0.11.0 bundle files Central rejects: the tag and the GitHub
# release existed, and nothing had reached Central. This builds the same bundles on every pull
# request, so the next change of that kind fails a check instead of a release.
#
# The plugin is pointed at the discard port on localhost with throwaway credentials, so it builds,
# stages and zips the real bundle and then fails to upload it. That failure is the expected end of
# every build here; what decides the result is the bundle it left behind.
#
# Tests (including their compilation) and static analysis are skipped: neither adds a file to a
# bundle, no published module attaches a test jar, and build-maven runs both.
#
# Usage: build-central-bundles.sh                     (from anywhere in the repository)
#   MVN=<path>          the Maven to run, default `mvn` on PATH
#   MAVEN_VERSION=<v>   if set, refuse to run unless that Maven is version <v>
set -uo pipefail

MVN="${MVN:-mvn}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
check="$root/.github/scripts/check-central-bundle.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Credentials for the server id the central-publish profile names, so the plugin gets as far as the
# upload. Also passed as the global settings: the build needs none, and a Maven unpacked under a
# path containing "--" cannot parse its own conf/settings.xml, whose comments quote ${maven.conf}.
cat > "$work/settings.xml" <<'EOF'
<settings>
  <servers>
    <server><id>central</id><username>bundle-check</username><password>not-a-token</password></server>
  </servers>
</settings>
EOF

modules="$(sed -n 's|^ *bash \.github/scripts/deploy-to-central\.sh \([^ ]*\) .*|\1|p' \
  "$root/.github/workflows/publish.yml")"
if [ -z "$modules" ]; then
  echo "::error::found no deploy-to-central.sh call in publish.yml, so there is nothing to check"
  exit 1
fi

if ! mvn_version="$("$MVN" --version)"; then
  echo "::error::$MVN --version failed"
  exit 1
fi
printf '%s\n' "$mvn_version"
if [ -n "${MAVEN_VERSION:-}" ] && ! printf '%s\n' "$mvn_version" | grep -qF "Apache Maven $MAVEN_VERSION "; then
  echo "::error::this is not Maven $MAVEN_VERSION, the version publish.yml deploys with, so its bundles say nothing about a release"
  exit 1
fi

# The first <name> element of a pom: the project's own, in a flattened pom with no parent.
first() { sed -n "/^ *<$1>/{s|^ *<$1>\([^<]*\)</$1>.*|\1|p;q;}" "$2"; }

failed=0
for module in $modules; do
  bundle="$root/$module/target/central-publishing/central-bundle.zip"
  pom="$root/$module/.flattened-pom.xml"
  log="$work/$module.log"
  rm -f "$bundle" "$pom"   # a bundle left by an earlier build must not be the one checked

  echo "::group::$module: mvn clean deploy, uploading to http://127.0.0.1:9"
  (cd "$root/$module" && "$MVN" -B clean deploy -P central-publish -Dmaven.test.skip=true \
      -Dpmd.skip=true -Dcpd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true \
      -DcentralBaseUrl=http://127.0.0.1:9 -s "$work/settings.xml" -gs "$work/settings.xml") \
    > "$log" 2>&1
  status=$?
  tail -n 25 "$log"
  echo "::endgroup::"

  if [ "$status" -eq 0 ]; then
    echo "::error::$module: the deploy reported success while pointed at http://127.0.0.1:9; read its log before trusting anything it did"
    failed=1
  elif [ ! -f "$bundle" ] || [ ! -f "$pom" ]; then
    echo "::error::$module: the build failed before the plugin wrote a bundle"
    tail -n 80 "$log"
    failed=1
  elif ! grep -qF "Using Central baseUrl: http://127.0.0.1:9" "$log"; then
    echo "::error::$module: the publishing plugin did not take the local base URL"
    failed=1
  else
    packaging="$(first packaging "$pom")"
    bash "$check" "$bundle" "$(first groupId "$pom")" "$(first artifactId "$pom")" \
      "$(first version "$pom")" "${packaging:-jar}" || failed=1
  fi
done
exit "$failed"
