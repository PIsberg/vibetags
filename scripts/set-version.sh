#!/bin/sh
# Sets the VibeTags release version.
#
# Usage:
#   scripts/set-version.sh <new-version>
#
# Example:
#   scripts/set-version.sh 1.0.0
#
# The version lives in ONE place: <revision> in vibetags-parent/pom.xml. Every pom that
# inherits from the parent — vibetags-annotations, vibetags, vibetags-bom, load-tests —
# takes its own version, its sibling dependencies and its BOM entries from it, so this
# script does not touch them at all.
#
# What it still has to rewrite by hand, and why:
#
#   vibetags-annotations/build.gradle  Gradle cannot inherit from a Maven POM.
#   vibetags/build.gradle              Same. Both publish under this version.
#   vibetags-annotations/pom.xml       Prose only: the <description> shows consumers a
#   vibetags-bom/pom.xml               copy-pasteable snippet with a literal version in it.
#   vibetags-cli/pom.xml               Same: the jbang one-liner in its <description>.
#   examples/basic/pom.xml                    Standalone on purpose, so a user can lift them into
#   examples/basic/build.gradle               their own project. Their vibetags.bom.version is a
#   examples/multimodule/pom.xml        literal, and CI builds them against the artifacts
#   examples/multimodule-indexed/pom.xml  this repo just installed, so they track the
#   examples/all-tiers/pom.xml          current version rather than the last released one.
#   tools/demo/pom.xml
#   examples/kotlin/build.gradle.kts    Same standalone-consumer shape: their BOM
#   examples/groovy/build.gradle        coordinates are literals kept in step here
#   examples/scala/build.gradle         (Kotlin DSL for the first, Groovy DSL for the rest).
#
#   README.md                          Install snippets. A consumer copies these verbatim, so a
#   .claude/skills/vibetags-usage/     GA that still says RC9 hands every new user the wrong
#     SKILL.md                         coordinate.
#
# This list is not maintained by discipline: BuildVersionParityTest fails the build if any
# of these disagrees with <revision>, and it is what caught the omissions this script used
# to leave behind.
#
# What must NOT be rewritten, and why a blanket search-and-replace is wrong:
#
#   docs/CHANGELOG.md                  A record of what each version did. Rewriting it would
#                                      claim the current release shipped every past change.
#   load-tests/results/**              Measurements belong to the version they were taken on.
#   load-tests/README.md               "since 1.0.0-RC9" is provenance, not a coordinate.
#
# Idempotent: the current version is read fresh from the parent on every run.
set -eu

NEW_VERSION="${1:-}"
if [ -z "$NEW_VERSION" ]; then
    echo "Usage: scripts/set-version.sh <new-version>" >&2
    exit 1
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

PARENT_POM="$ROOT_DIR/vibetags-parent/pom.xml"
if [ ! -f "$PARENT_POM" ]; then
    echo "error: $PARENT_POM not found" >&2
    exit 1
fi

OLD_VERSION=$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' "$PARENT_POM" | head -n1)
if [ -z "$OLD_VERSION" ]; then
    echo "error: could not read <revision> from $PARENT_POM" >&2
    exit 1
fi

if [ "$OLD_VERSION" = "$NEW_VERSION" ]; then
    echo "Already at version $NEW_VERSION - nothing to do."
    exit 0
fi

echo "Bumping VibeTags version: $OLD_VERSION -> $NEW_VERSION"

# Escape regex metacharacters in the old version so it is matched literally.
escape_sed() {
    printf '%s' "$1" | sed 's/[.[\*^$/]/\\&/g'
}
OLD_ESCAPED=$(escape_sed "$OLD_VERSION")

# In-place edit that works with both GNU sed (Git Bash/Linux) and BSD sed (macOS).
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

# Scoped edit for files that also pin third-party dependency versions which can coincidentally
# equal $OLD_VERSION (e.g. jspecify.version matched the VibeTags version once, and a blanket
# replace_in_file bumped it right along with <revision>). Only two shapes are VibeTags's own:
# the parent POM's <revision> property, and Gradle's `version = '...'` / `se.deversity.vibetags:
# artifact:version` coordinate strings. Anything else on the line is left untouched.
replace_xml_property() {
    file="$1"
    property="$2"
    if [ ! -f "$file" ]; then
        echo "warning: $file not found, skipping" >&2
        return 0
    fi
    sed -i.bak -E "s#(<${property}>)$OLD_ESCAPED(</${property}>)#\1$NEW_VERSION\2#" "$file"
    rm -f "$file.bak"
    echo "  updated ${file#"$ROOT_DIR"/} (<$property> only)"
}

replace_gradle_vibetags_refs() {
    file="$1"
    if [ ! -f "$file" ]; then
        echo "warning: $file not found, skipping" >&2
        return 0
    fi
    sed -i.bak -E \
        -e "s/^([[:space:]]*version = )'$OLD_ESCAPED'/\1'$NEW_VERSION'/" \
        -e "s#(se\.deversity\.vibetags:[A-Za-z0-9_-]+:)$OLD_ESCAPED#\1$NEW_VERSION#g" \
        "$file"
    rm -f "$file.bak"
    echo "  updated ${file#"$ROOT_DIR"/} (se.deversity.vibetags refs only)"
}

# The one authoritative edit — <revision> only, so a third-party version pin that happens to
# equal $OLD_VERSION (jspecify.version has collided with it before) is never touched.
replace_xml_property "$PARENT_POM" "revision"

# Everything that cannot inherit it.
for rel in \
    vibetags-annotations/build.gradle \
    vibetags/build.gradle \
    examples/kotlin/build.gradle.kts \
    examples/groovy/build.gradle \
    examples/scala/build.gradle \
    examples/gradle-multimodule/build.gradle \
    examples/gradle-shared-buildfile/build.gradle \
    examples/gradle-flat/app/build.gradle \
    examples/gradle-composite/app/build.gradle \
    examples/gradle-composite/lib/build.gradle \
; do
    replace_gradle_vibetags_refs "$ROOT_DIR/$rel"
done

for rel in \
    vibetags-annotations/pom.xml \
    vibetags-bom/pom.xml \
    vibetags-cli/pom.xml \
    examples/basic/pom.xml \
    examples/basic/build.gradle \
    examples/multimodule/pom.xml \
    examples/multimodule-indexed/pom.xml \
    examples/all-tiers/pom.xml \
    tools/demo/pom.xml \
    README.md \
    .claude/skills/vibetags-usage/SKILL.md \
; do
    replace_in_file "$ROOT_DIR/$rel"
done

echo "Done."
echo "Verify with:  cd vibetags && mvn test -Dtest=BuildVersionParityTest"
echo "Then rebuild in order: vibetags-annotations -> vibetags -> vibetags-bom."
