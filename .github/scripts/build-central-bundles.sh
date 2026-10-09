#!/usr/bin/env bash
#
# Build the Maven Central bundle publish.yml deploys, without publishing anything, and check it with
# check-central-bundle.sh. A release is one bundle: publish.yml deploys the release reactor in
# .github/central-release, and the plugin uploads every module's files as a single deployment
# (#863). This builds that same reactor.
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
# bundle, no published module attaches a test jar, and build-maven runs both. The build runs in the
# repository it is given, so its install step puts the release version in the local repository.
#
# Usage: build-central-bundles.sh                     (from anywhere in the repository)
#   MVN=<path>          the Maven to run, default `mvn` on PATH
#   MAVEN_VERSION=<v>   if set, refuse to run unless that Maven is version <v>
#   SIGN=true           also activate sign-artifacts, as publish.yml does, and require every file's
#                       .asc to verify (#949). Needs a secret key in the default gpg keyring with no
#                       passphrase; the Central Bundle Shape job makes a throwaway one.
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

reactor="$root/.github/central-release"
modules="$(sed -n 's|^ *<module>\.\./\.\./\([^<]*\)</module>.*|\1|p' "$reactor/pom.xml")"
if [ -z "$modules" ]; then
  echo "::error::found no <module>../../<dir></module> in $reactor/pom.xml, so there is nothing to check"
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

for module in $modules; do
  # A bundle or pom left by an earlier build must not be the one checked.
  rm -f "$root/$module/target/central-publishing/central-bundle.zip" "$root/$module/.flattened-pom.xml"
done

log="$work/release.log"
profiles=central-publish
check_opts=()
if [ "${SIGN:-}" = true ]; then
  profiles=central-publish,sign-artifacts
  check_opts=(--signed)
fi

echo "::group::release reactor: mvn clean deploy -P $profiles, uploading to http://127.0.0.1:9"
(cd "$reactor" && "$MVN" -B clean deploy -P "$profiles" -Dmaven.test.skip=true \
    -Dpmd.skip=true -Dcpd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true \
    -DcentralBaseUrl=http://127.0.0.1:9 -s "$work/settings.xml" -gs "$work/settings.xml") \
  > "$log" 2>&1
status=$?
tail -n 40 "$log"
echo "::endgroup::"

if [ "$status" -eq 0 ]; then
  echo "::error::the deploy reported success while pointed at http://127.0.0.1:9; read its log before trusting anything it did"
  exit 1
fi
if ! grep -qF "Using Central baseUrl: http://127.0.0.1:9" "$log"; then
  echo "::error::the publishing plugin did not take the local base URL"
  tail -n 80 "$log"
  exit 1
fi

# The plugin stages every module into the first one's target/ and zips it once, after the last.
bundles=()
specs=()
for module in $modules; do
  pom="$root/$module/.flattened-pom.xml"
  if [ ! -f "$pom" ]; then
    echo "::error::$module: the build failed before it wrote $pom"
    tail -n 80 "$log"
    exit 1
  fi
  packaging="$(first packaging "$pom")"
  specs+=("$(first groupId "$pom"):$(first artifactId "$pom"):$(first version "$pom"):${packaging:-jar}")
  candidate="$root/$module/target/central-publishing/central-bundle.zip"
  [ -f "$candidate" ] && bundles+=("$candidate")
done
if [ "${#bundles[@]}" -ne 1 ]; then
  echo "::error::expected the release reactor to write one central-bundle.zip, found ${#bundles[@]}: ${bundles[*]:-none}. More than one is more than one Central release (#863)."
  tail -n 80 "$log"
  exit 1
fi
bash "$check" "${check_opts[@]}" "${bundles[0]}" "${specs[@]}"
