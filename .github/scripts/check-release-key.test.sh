#!/usr/bin/env bash
#
# Exercise check-release-key.sh against keys made for this run and served from local files.
#
# Each case is one way a release key stops working for Central while every pull-request check stays
# green (#956): expired, about to expire, revoked, signing only through a subkey, or on no keyserver.
# Needs gpg on PATH; every key lives in a private homedir and is discarded with it.
#
# Usage: check-release-key.test.sh [path-to-check-release-key.sh]
set -u

SCRIPT="${1:-$(cd "$(dirname "$0")" && pwd)/check-release-key.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# A file:// URL needs a path curl can open; on Windows that is the mixed form of the temp dir.
SRV="$TMP/srv"
URL_ROOT="$SRV"
command -v cygpath >/dev/null 2>&1 && URL_ROOT="$(cygpath -m "$SRV")"
mkdir -p "$SRV"

pass=0
fail=0

# expect <0|text> <name> <fingerprint> [VAR=value ...]: run the checker. 0 wants it to pass;
# anything else wants it to fail and print that text, so a case cannot pass by failing for another reason.
expect() {
  local want="$1" name="$2" fpr="$3"
  shift 3
  env "$@" bash "$SCRIPT" "$fpr" > "$TMP/out" 2>&1
  local status=$?
  if { [ "$want" = 0 ] && [ "$status" -eq 0 ]; } || { [ "$want" != 0 ] && [ "$status" -ne 0 ] && grep -qF -- "$want" "$TMP/out"; }; then
    echo "ok   $name (exit $status)"
    pass=$((pass + 1))
  else
    echo "FAIL $name: wanted ${want/#0/exit 0}, got exit $status"
    sed 's/^/     /' "$TMP/out"
    fail=$((fail + 1))
  fi
}

# key <name> <expiry> [subkey-expiry]: a signing key in its own homedir; prints its fingerprint.
# With a third argument the key also gets a signing subkey, and the subkey's fingerprint is printed.
key() {
  local home="$TMP/$1"
  mkdir -p "$home" && chmod 700 "$home"
  gpg --homedir "$home" --batch --passphrase '' --quick-gen-key "$1 <$1@example.invalid>" ed25519 sign "$2" >/dev/null 2>&1
  local fpr
  fpr="$(gpg --homedir "$home" --batch --with-colons --list-keys | awk -F: '$1 == "fpr" { print $10; exit }')"
  if [ "$#" -ge 3 ]; then
    gpg --homedir "$home" --batch --passphrase '' --quick-add-key "$fpr" ed25519 sign "$3" >/dev/null 2>&1
    fpr="$(gpg --homedir "$home" --batch --with-colons --list-keys | awk -F: '$1 == "fpr" { n++ } $1 == "fpr" && n == 2 { print $10; exit }')"
  fi
  printf '%s\n' "$fpr"
}

# serve <name> <fingerprint>: put the key's public half where the file:// keyserver serves it
serve() {
  gpg --homedir "$TMP/$1" --batch --armor --export > "$SRV/$2.asc"
}

ONE="file://$URL_ROOT/%s.asc"
NOWHERE="file://$URL_ROOT/absent/%s.asc"

# 1. a key that is good for two years
good="$(key good 2y)"
serve good "$good"
expect 0 "usable key" "$good" KEYSERVERS="$ONE"

# 2. the same key, judged 23 months from now: under the 60-day margin
expect reason=expires-soon "expires within 60 days" "$good" KEYSERVERS="$ONE" NOW=$(( $(date +%s) + 700 * 86400 ))

# 3. the same key, judged three years from now: expired
expect reason=expired "expired" "$good" KEYSERVERS="$ONE" NOW=$(( $(date +%s) + 3 * 365 * 86400 ))

# 4. a lower margin lets the key of case 2 through
expect 0 "MIN_DAYS lowers the margin" "$good" KEYSERVERS="$ONE" MIN_DAYS=10 NOW=$(( $(date +%s) + 700 * 86400 ))

# 5. the key is on no keyserver
expect reason=not-served "on no keyserver" "$good" KEYSERVERS="$NOWHERE"

# 6. missing from one server, served by another: one is enough
expect 0 "served by one of two" "$good" KEYSERVERS="$NOWHERE $ONE"

# 7. a revoked key, using the revocation certificate gpg wrote when it made the key
revoked="$(key revoked 2y)"
cert="$TMP/revoked/openpgp-revocs.d/$revoked.rev"
sed 's/^:-----BEGIN/-----BEGIN/' "$cert" > "$TMP/revoked.rev"
gpg --homedir "$TMP/revoked" --batch --import "$TMP/revoked.rev" >/dev/null 2>&1
serve revoked "$revoked"
expect reason=revoked "revoked" "$revoked" KEYSERVERS="$ONE"

# 8. the recorded fingerprint is a signing subkey's: Central verifies against primary keys only
sub="$(key withsub 2y 1y)"
serve withsub "$sub"
expect reason=not-a-primary-key "signing subkey, not a primary key" "$sub" KEYSERVERS="$ONE"

# 9. something that is not a fingerprint
expect "40-hex-digit fingerprint" "malformed fingerprint" not-a-fingerprint KEYSERVERS="$ONE"

echo "check-release-key.test: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
