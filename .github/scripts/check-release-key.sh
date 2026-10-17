#!/usr/bin/env bash
#
# Check that a keyserver Central reads serves the release signing key, and that the key will still
# be usable for a while (#956).
#
# Central rejects a bundle whose signatures it cannot verify against a public key it finds on a
# keyserver. Pull requests never sign with the release key, so the Central Bundle Shape check signs
# with a throwaway one: a release key that expired, was revoked or never reached a keyserver passes
# every pull-request check and is rejected only by the real publish, after the release is tagged.
# This needs only the key's public half and its fingerprint, which is public, so it runs anywhere.
#
# The fingerprint is publish.yml's RELEASE_KEY_FINGERPRINT, which publish.yml compares with the key
# it imports before signing, so this checks the key releases are actually signed with. It lives in
# the workflow, not in a file of its own, because a resumed release runs main's publish.yml against
# an older tag's source. Central's requirements page
# (https://central.sonatype.org/publish/requirements/gpg/, read 2026-10-17) lists three keyservers
# as supported and does not say which it consults, so the key passes when at least one of them
# serves it as a primary key that can sign, is not revoked, and does not expire within MIN_DAYS.
# Every server's answer is printed either way. Central and Maven verify against a primary key only,
# so a key that signs with a subkey fails here too.
#
# Usage: check-release-key.sh [fingerprint]      (default: RELEASE_KEY_FINGERPRINT in publish.yml)
#   KEYSERVERS="<url> ..."   URL templates, %s is replaced by the fingerprint; default the three
#                            servers Central lists. file:// URLs work, which is what the tests use.
#   MIN_DAYS=<n>             fail when the key expires within n days (default 60)
#   NOW=<epoch seconds>      the time to judge expiry against (default: now)
set -uo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
MIN_DAYS="${MIN_DAYS:-60}"
NOW="${NOW:-$(date +%s)}"
KEYSERVERS="${KEYSERVERS:-https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0x%s https://keys.openpgp.org/vks/v1/by-fingerprint/%s https://pgp.mit.edu/pks/lookup?op=get&options=mr&search=0x%s}"

if ! command -v gpg >/dev/null 2>&1; then
  echo "::error::gpg is not on PATH, so the release key cannot be checked"
  exit 1
fi
if [ "$#" -ge 1 ]; then
  fpr="$1"
else
  fpr="$(sed -n "s/^ *RELEASE_KEY_FINGERPRINT: *'\([^']*\)'.*/\1/p" "$root/.github/workflows/publish.yml")"
fi
fpr="$(printf '%s' "$fpr" | tr -d '[:space:]' | tr 'a-f' 'A-F')"
if ! printf '%s' "$fpr" | grep -qE '^[0-9A-F]{40}$'; then
  echo "::error::expected the release key's 40-hex-digit fingerprint, as RELEASE_KEY_FINGERPRINT: '<fingerprint>' in publish.yml, found '${fpr}'"
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
deadline=$((NOW + MIN_DAYS * 86400))
usable=0

for template in $KEYSERVERS; do
  url="${template//%s/$fpr}"
  server="$(printf '%s' "$url" | sed -E 's|^[a-z]+://([^/]*).*|\1|')"
  [ -n "$server" ] || server="$url"
  home="$TMP/home-$usable-$RANDOM"
  mkdir -p "$home" && chmod 700 "$home"
  if ! curl -fsS --retry 2 -m 30 -o "$TMP/key.asc" "$url" 2>"$TMP/curl.err"; then
    echo "release.key.miss server=$server reason=not-served detail=\"$(tr '\n' ' ' < "$TMP/curl.err")\""
    continue
  fi
  if ! gpg --homedir "$home" --batch --quiet --import "$TMP/key.asc" >/dev/null 2>&1; then
    echo "release.key.miss server=$server reason=unreadable"
    continue
  fi
  # The pub record of the key whose own fingerprint is $fpr: a subkey's fpr follows a sub record.
  record="$(gpg --homedir "$home" --batch --with-colons --fixed-list-mode --list-keys "$fpr" 2>/dev/null \
    | awk -F: -v want="$fpr" '$1 == "pub" { pub = $0 } $1 == "sub" { pub = "" } $1 == "fpr" && $10 == want && pub != "" { print pub; exit }')"
  if [ -z "$record" ]; then
    echo "release.key.miss server=$server reason=not-a-primary-key"
    continue
  fi
  validity="$(printf '%s' "$record" | cut -d: -f2)"
  expires="$(printf '%s' "$record" | cut -d: -f7)"
  caps="$(printf '%s' "$record" | cut -d: -f12)"
  if [ "$validity" = "r" ]; then
    echo "release.key.miss server=$server reason=revoked"
    continue
  fi
  case "$caps" in
    *s*) ;;
    *) echo "release.key.miss server=$server reason=cannot-sign capabilities=$caps"; continue ;;
  esac
  if [ -n "$expires" ]; then
    left=$(( (expires - NOW) / 86400 ))
    on="$(date -u -d "@$expires" +%Y-%m-%d 2>/dev/null || echo "$expires")"
    if [ "$expires" -le "$NOW" ]; then
      echo "release.key.miss server=$server reason=expired expires=$on"
      continue
    fi
    if [ "$expires" -le "$deadline" ]; then
      echo "release.key.miss server=$server reason=expires-soon expires=$on days=$left min_days=$MIN_DAYS"
      continue
    fi
    echo "release.key.ok server=$server expires=$on days=$left"
  else
    echo "release.key.ok server=$server expires=never"
  fi
  usable=$((usable + 1))
done

if [ "$usable" -eq 0 ]; then
  echo "::error::no keyserver Central lists serves release key $fpr as a usable signing key for the next $MIN_DAYS days. Renew or re-upload it (docs/RELEASING.md, 'Renew the release signing key') before the next release."
  exit 1
fi
echo "release.key.pass fingerprint=$fpr servers=$usable"
