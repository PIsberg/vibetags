#!/usr/bin/env bash
# Reset all AI config files to empty (preserves files so processor opt-in stays active).
# Use this before a clean compile to verify the processor regenerates all content.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

AI_FILES=(
  ".cursorrules"
  "CLAUDE.md"
  ".aiexclude"
  "AGENTS.md"
  "QWEN.md"
  "gemini_instructions.md"
  ".github/copilot-instructions.md"
  "llms.txt"
  "llms-full.txt"
  ".cursorignore"
  ".claudeignore"
  ".copilotignore"
  ".qwenignore"
  ".codex/config.toml"
  ".codex/rules/vibetags.rules"
  ".qwen/settings.json"
  ".qwen/commands/refactor.md"
)

echo "Resetting AI config files in: $SCRIPT_DIR"
for f in "${AI_FILES[@]}"; do
  path="$SCRIPT_DIR/$f"
  if [ -f "$path" ]; then
    > "$path"
    echo "  cleared: $f"
  fi
done
echo "Done. All present AI config files cleared."
