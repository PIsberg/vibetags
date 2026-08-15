#!/usr/bin/env bash
# SessionEnd hook: ambient prompt-lineage capture (Vibe Architecture ch23: ambient capture,
# human promotion). Appends one line per agent session to .claude/lineage/sessions.log, a
# gitignored staging file. Promotion of load-bearing intent into commit messages and PR
# bodies stays a human act; this hook only makes sure the raw trail exists to promote from.
#
# Must never block or fail a session: every path exits 0.
input="$(cat 2>/dev/null || true)"
mkdir -p .claude/lineage 2>/dev/null || exit 0
sid="$(printf '%s' "$input" | tr '{,' '\n\n' | grep -o '"session_id"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown-time)"
printf '%s session=%s cwd=%s\n' "$stamp" "${sid:-unknown}" "$(pwd)" >> .claude/lineage/sessions.log 2>/dev/null || true
exit 0
