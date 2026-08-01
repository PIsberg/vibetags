#!/usr/bin/env bash
#
# Deploy one module to Maven Central, and exit non-zero when that genuinely failed.
#
# This replaces three inline copies of the same block, all of which shared one bug:
#
#     if mvn clean deploy ... 2>&1 | tee "$log"; then echo "deployed."
#
# `if` tests the exit status of the *pipeline*, which is tee's, and tee always
# succeeds — so Maven's status was never read, and neither the "already exists"
# branch nor the failure branch could ever be reached. A transient
# central.sonatype.com timeout on 2026-08-01 therefore published
# vibetags-processor and vibetags-bom 1.0.0-RC8 but not vibetags-annotations,
# while the run reported success. Any consumer pinning 1.0.0-RC8 could not resolve.
#
# Usage: deploy-to-central.sh <module-dir> <artifact-name> [extra mvn args...]

set -uo pipefail

module="${1:?module directory required}"
name="${2:?artifact name required}"
shift 2

# Overridable so the retry path can be exercised without waiting on real backoff.
attempts="${CENTRAL_DEPLOY_ATTEMPTS:-3}"
backoff_step="${CENTRAL_DEPLOY_BACKOFF_SECONDS:-30}"

cd "$module" || exit 1

for attempt in $(seq 1 "$attempts"); do
  log="$(mktemp)"

  mvn clean deploy -P central-publish,sign-artifacts -B "$@" 2>&1 | tee "$log"
  status="${PIPESTATUS[0]}"

  if [ "$status" -eq 0 ]; then
    echo "$name: deployed."
    exit 0
  fi

  # Re-publishing an unchanged version is not a failure — the release workflow is
  # expected to be re-runnable.
  if grep -q "already exists" "$log"; then
    echo "::warning title=Already published::$name already exists on Maven Central — treating as success (idempotent re-publish)."
    exit 0
  fi

  # Transport failures reaching the portal say nothing about the artifact, so
  # retry them rather than leaving the release half-published.
  if [ "$attempt" -lt "$attempts" ] &&
     grep -qiE "Connection timed out|Connection reset|Read timed out|connect timed out|502 Bad Gateway|503 Service Unavailable|504 Gateway" "$log"; then
    backoff=$((attempt * backoff_step))
    echo "::warning title=Transient failure::$name deploy attempt ${attempt}/${attempts} failed on a transport error; retrying in ${backoff}s."
    sleep "$backoff"
    continue
  fi

  echo "::error title=Deploy failed::$name deploy failed (mvn exit ${status}) on attempt ${attempt}/${attempts} — see the Maven output above."
  exit 1
done

echo "::error title=Deploy failed::$name deploy failed after ${attempts} attempts."
exit 1
