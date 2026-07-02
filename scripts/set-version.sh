#!/bin/sh
# Bumps the VibeTags core library version consistently across every place it is
# hardcoded, since there is deliberately no parent POM tying the modules together.
#
# Usage:
#   scripts/set-version.sh <new-version>
#
# Example:
#   scripts/set-version.sh 1.0.0
#
# Covers (5 files, ~16 occurrences):
#   vibetags-annotations/pom.xml       - <version>, plus Maven/Gradle install snippets in <description>
#   vibetags-annotations/build.gradle  - top-level version, publication version
#   vibetags/pom.xml                   - <version>, plus the <dependency> on vibetags-annotations
#   vibetags/build.gradle              - top-level version, publication version, the
#                                         api 'vibetags-annotations:...' dependency line
#   vibetags-bom/pom.xml               - <version>, <vibetags.version> (the BOM's managed
#                                         version property), plus install snippets in <description>
#
# Deliberately NOT touched (tracked separately, see docs/RELEASING.md / CLAUDE.md):
#   example/pom.xml, example/build.gradle, tools/demo/pom.xml - track a released BOM
#     version via vibetags.bom.version, updated independently once a release exists.
#   load-tests/pom.xml - pins <processor.version> intentionally for cross-version
#     benchmark comparisons; must not be force-bumped by this script.
#
# Idempotent: the current version is read fresh from vibetags-bom/pom.xml on every
# run, so re-running with the same target version is a no-op, and running again
# later with a different target version picks up correctly from whatever is on disk.
set -eu

NEW_VERSION="${1:-}"
if [ -z "$NEW_VERSION" ]; then
    echo "Usage: scripts/set-version.sh <new-version>" >&2
    exit 1
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

BOM_POM="$ROOT_DIR/vibetags-bom/pom.xml"
if [ ! -f "$BOM_POM" ]; then
    echo "error: $BOM_POM not found" >&2
    exit 1
fi

# vibetags-bom/pom.xml's <vibetags.version> property is treated as the single
# source of truth for "what version are we currently on".
OLD_VERSION=$(sed -n 's:.*<vibetags\.version>\(.*\)</vibetags\.version>.*:\1:p' "$BOM_POM" | head -n1)

if [ -z "$OLD_VERSION" ]; then
    echo "error: could not determine current version from $BOM_POM" >&2
    exit 1
fi

if [ "$OLD_VERSION" = "$NEW_VERSION" ]; then
    echo "Already at version $NEW_VERSION - nothing to do."
    exit 0
fi

echo "Bumping VibeTags version: $OLD_VERSION -> $NEW_VERSION"

# Escape regex metacharacters in the old version (dots, dashes, etc.) so it is
# matched literally rather than as a sed pattern.
escape_sed() {
    printf '%s' "$1" | sed 's/[.[\*^$/]/\\&/g'
}
OLD_ESCAPED=$(escape_sed "$OLD_VERSION")

# In-place edit that works with both GNU sed (Git Bash/Linux) and BSD sed (macOS):
# always pass a backup suffix, then remove the backup file.
replace_in_file() {
    file="$1"
    if [ ! -f "$file" ]; then
        echo "warning: $file not found, skipping" >&2
        return 0
    fi
    sed -i.bak "s/$OLD_ESCAPED/$NEW_VERSION/g" "$file"
    rm -f "$file.bak"
    echo "  updated ${file#"$ROOT_DIR"/}"
}

for rel in \
    vibetags-annotations/pom.xml \
    vibetags-annotations/build.gradle \
    vibetags/pom.xml \
    vibetags/build.gradle \
    vibetags-bom/pom.xml \
; do
    replace_in_file "$ROOT_DIR/$rel"
done

echo "Done."
echo "Review the diff, then rebuild in order: vibetags-annotations -> vibetags -> vibetags-bom."
echo "Remember: example/, tools/demo/, and load-tests/ track versions independently (see docs/RELEASING.md)."
