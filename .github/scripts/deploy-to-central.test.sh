#!/usr/bin/env bash
#
# Exercise deploy-to-central.sh against stub `mvn` behaviours.
#
# The case that matters is "hard failure -> exit 1". The inline blocks this script
# replaced piped Maven into tee and tested the pipeline's status, so a failed
# deploy was reported as a successful one — which is how 1.0.0-RC8 shipped without
# vibetags-annotations while the release run showed green. Run against the old
# code, this suite fails 3 of 5.
#
# Usage: deploy-to-central.test.sh [path-to-deploy-to-central.sh]
set -u

SCRIPT="${1:-$(cd "$(dirname "$0")" && pwd)/deploy-to-central.sh}"
case "$SCRIPT" in
  /*) ;;
  *) SCRIPT="$(cd "$(dirname "$SCRIPT")" && pwd)/$(basename "$SCRIPT")" ;;
esac

TMP="$(mktemp -d)"
mkdir -p "$TMP/bin" "$TMP/modA"
export PATH="$TMP/bin:$PATH"
export CENTRAL_DEPLOY_ATTEMPTS=3
export CENTRAL_DEPLOY_BACKOFF_SECONDS=0

pass=0
fail=0

check() { # name expected_exit expected_grep
  local name="$1" want="$2" needle="$3"
  local out rc
  out="$(cd "$TMP" && bash "$SCRIPT" modA test-artifact 2>&1)"; rc=$?
  if [ "$rc" -eq "$want" ] && printf '%s' "$out" | grep -q "$needle"; then
    echo "PASS  $name (exit $rc)"
    pass=$((pass + 1))
  else
    echo "FAIL  $name — exit=$rc want=$want; output:"
    printf '%s\n' "$out" | sed 's/^/        /'
    fail=$((fail + 1))
  fi
}

# 1. clean success
cat > "$TMP/bin/mvn" <<'EOF'
#!/usr/bin/env bash
echo "[INFO] BUILD SUCCESS"
exit 0
EOF
chmod +x "$TMP/bin/mvn"
check "success -> deployed" 0 "test-artifact: deployed."

# 2. a real failure must NOT be reported as success (the bug this script fixes)
cat > "$TMP/bin/mvn" <<'EOF'
#!/usr/bin/env bash
echo "[ERROR] Deployment failed while publishing"
echo "[INFO] BUILD FAILURE"
exit 1
EOF
check "hard failure -> exit 1" 1 "::error title=Deploy failed"

# 3. re-publishing an already-published version stays green
cat > "$TMP/bin/mvn" <<'EOF'
#!/usr/bin/env bash
echo "[ERROR] component already exists in the repository"
exit 1
EOF
check "already exists -> success" 0 "::warning title=Already published"

# 4. a transient transport error is retried rather than half-publishing the release
cat > "$TMP/bin/mvn" <<'EOF'
#!/usr/bin/env bash
n_file="${TMPDIR:-/tmp}/deploy-attempt-count"
n=$(cat "$n_file" 2>/dev/null || echo 0)
n=$((n + 1)); echo "$n" > "$n_file"
if [ "$n" -lt 2 ]; then
  echo "java.lang.RuntimeException: Invalid request. Connect to https://central.sonatype.com:443 failed: Connection timed out"
  exit 1
fi
echo "[INFO] BUILD SUCCESS"
exit 0
EOF
rm -f "${TMPDIR:-/tmp}/deploy-attempt-count"
check "transient -> retry -> deployed" 0 "test-artifact: deployed."

# 5. a permanently unreachable portal fails instead of looping
cat > "$TMP/bin/mvn" <<'EOF'
#!/usr/bin/env bash
echo "Connection timed out"
exit 1
EOF
check "persistent transient -> exit 1" 1 "::error title=Deploy failed"

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
