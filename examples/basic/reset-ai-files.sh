#!/usr/bin/env bash
# Reset all AI config files to empty (preserves files so processor opt-in stays active).
# Use this before a clean compile to verify the processor regenerates all content.
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

AI_FILES=(
  ".cursorrules"
  "CLAUDE.md"
  ".aiexclude"
  # NOTE: AGENTS.md is intentionally NOT reset. Because this example ships other AI config
  # files, VibeTags treats AGENTS.md as a hand-authored pointer (sole-file fallback rule) and
  # never regenerates it — clearing it here would permanently blank the pointer.
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
  "CONVENTIONS.md"
  ".aiderignore"
  ".windsurfrules"
  ".rules"
  ".cody/config.json"
  ".codyignore"
  ".supermavenignore"
  # v0.8.0 platforms
  ".mentatconfig.json"
  "sweep.yaml"
  ".plandex.yaml"
  ".doubleignore"
  ".interpreter/profiles/vibetags.yaml"
  ".codeiumignore"
  # v0.9.6 platforms
  "GEMINI.md"
  ".antigravityignore"
  # v0.9.7 platforms
  ".clinerules"
  ".junie/guidelines.md"
  # Firebase AI
  ".idx/airules.md"
  # Context-packer ignore files
  ".repomixignore"
  ".gitingestignore"
  ".gptignore"
  ".ghostcoderignore"
  ".piecesignore"
  # AI pull-request reviewers
  ".coderabbit.yaml"
  ".pr_agent.toml"
  "ellipsis.yaml"
  # Editors & modes
  ".void/rules.md"
  ".roomodes"
  # Claude Code local override and Skill
  "CLAUDE.local.md"
  ".claude/skills/vibetags-guardrails/SKILL.md"
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
for dir in ".cursor/rules" ".trae/rules" ".roo/rules" ".windsurf/rules" ".continue/rules" ".tabnine/guidelines" ".amazonq/rules" ".ai/rules" ".pearai/rules" ".claude/rules" ".github/instructions"; do
  if [ -d "$SCRIPT_DIR/$dir" ]; then
    echo "  cleaning granular rules in: $dir"
    find "$SCRIPT_DIR/$dir" -type f \( -name "*.mdc" -o -name "*.md" \) -exec rm {} +
  fi
done

echo "Done. All present AI config files cleared and granular rules removed."
