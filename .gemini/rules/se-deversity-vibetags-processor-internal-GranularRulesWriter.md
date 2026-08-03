<!-- VIBETAGS-START -->
# Rules for GranularRulesWriter

## Context & Focus
- **Focus**: Writes granular rule files (per-class, or role-grouped when .vibetags-roles is present) for Cursor, Windsurf, Trae, Roo, and similar platforms; cleanup runs AFTER write to avoid delete-then-recreate cycles
- **Avoid**: Running cleanup before write — would delete files that are about to be recreated, causing spurious filesystem events and empty windows for incremental build tools
<!-- VIBETAGS-END -->
