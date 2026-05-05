#!/usr/bin/env bash
# Reset all AI config files to empty (preserves files so processor opt-in stays active).
# Use this before a clean compile to verify the processor regenerates all content.
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

AI_FILES=(
  ".cursorrules"
  "CLAUDE.md"
  ".aiexclude"
  "AGENTS.md"
  "QWEN.md"
  ".qwenignore"
  ".qwen/settings.json"
  ".qwen/commands/refactor.md"
  "gemini_instructions.md"
  ".github/copilot-instructions.md"
  "llms.txt"
  "llms-full.txt"
  ".cursorignore"
  ".claudeignore"
  ".copilotignore"
  ".codex/config.toml"
  ".codex/rules/vibetags.rules"
  "CONVENTIONS.md"
  ".aiderignore"
  ".windsurfrules"
  ".rules"
  ".cody/config.json"
  ".codyignore"
  ".supermavenignore"
)

echo "Resetting AI config files in: $SCRIPT_DIR"
for f in "${AI_FILES[@]}"; do
  path="$SCRIPT_DIR/$f"
  if [ -f "$path" ]; then
    > "$path"
    echo "  cleared: $f"
  fi
done

# Remove the per-file write cache so the next compile is a full cold rebuild.
if [ -f "$SCRIPT_DIR/.vibetags-cache" ]; then
  rm "$SCRIPT_DIR/.vibetags-cache"
  echo "  removed: .vibetags-cache"
fi

# Sweep any stale backup files left over from before 0.5.6's atomic-move switch.
# The current writer never creates these, but old checkouts might.
find "$SCRIPT_DIR" -type f -name "*.bak" -exec rm -v {} + 2>/dev/null || true

# Cleanup granular rules in directories
for dir in ".cursor/rules" ".trae/rules" ".roo/rules" ".windsurf/rules" ".continue/rules" ".tabnine/guidelines" ".amazonq/rules" ".ai/rules"; do
  if [ -d "$SCRIPT_DIR/$dir" ]; then
    echo "  cleaning granular rules in: $dir"
    find "$SCRIPT_DIR/$dir" -type f \( -name "*.mdc" -o -name "*.md" \) -exec rm {} +
  fi
done

echo "Done. All present AI config files cleared and granular rules removed."