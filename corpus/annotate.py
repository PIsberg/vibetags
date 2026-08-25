"""Annotates one real element in a cloned corpus repo, so the opt-in phase has something to generate.

The corpus otherwise proves only that VibeTags stays out of the way. To evaluate what it
*produces*, something in the repo has to be annotated, and it has to be the repo's own code
rather than a fixture: the point is a real signature, resolved against real dependencies.

Two targets are chosen per repo, both deterministically so a rerun annotates the same elements:

  class   the first public top-level class in the source root, by sorted path
  method  the first method with a jspecify @Nullable parameter, if the repo has one

The second is the interesting one. Its element path is what #480 changed, and what the
type-use annotation question turns on: javac would render the parameter as
`java.lang.@org.jspecify.annotations.Nullable String`, and if that reached granularQName the
rule *filename* would carry the annotation with it.

Edits go to the clone under target/corpus, which is never committed and is restored with
`git checkout -- .` after the phase. Prints one `TARGET <kind> <reason>` line per annotated
element, where the third field is a unique reason string the harness greps for in the
generated output.
"""
import pathlib
import re
import sys

ANNOTATIONS_IMPORT = "import se.deversity.vibetags.annotations.AILocked;"
PACKAGE = re.compile(r"^package\s+([\w.]+)\s*;", re.M)
# Any public top-level type. Not just `class`: record-builder's main sources are entirely
# annotation types and interfaces, and restricting this to classes left it with nothing to
# annotate, which the harness correctly reported as a corpus member checking nothing.
TOP_LEVEL_CLASS = re.compile(
    r"^public\s+(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+)*"
    r"(?:class|interface|record|enum|@interface)\s+(\w+)", re.M)
# A method whose parameter list contains a @Nullable.
NULLABLE_METHOD = re.compile(
    r"^([ \t]+)((?:public|protected)\s+(?:static\s+)?(?:final\s+)?[\w.<>\[\],?\s]+?\s+)(\w+)\("
    r"([^)]*@Nullable[^)]*)\)\s*\{",
    re.M)


def insert_import(text):
    """Adds the annotation import after the package statement, if it is not already there."""
    if ANNOTATIONS_IMPORT in text:
        return text
    match = PACKAGE.search(text)
    if not match:
        return None
    at = match.end()
    return text[:at] + "\n\n" + ANNOTATIONS_IMPORT + text[at:]


def annotate_class(path, text):
    match = TOP_LEVEL_CLASS.search(text)
    if not match:
        return None, None
    updated = insert_import(text)
    if updated is None:
        return None, None
    # Re-find after the import shifted offsets.
    match = TOP_LEVEL_CLASS.search(updated)
    at = match.start()
    # The reason is the handle the harness greps for. Reconstructing the element's full path in
    # Python was the first attempt and it was the wrong tool: the path depends on nesting and on
    # parameter rendering, which is the very thing under test. A unique reason string proves the
    # element reached the file without this script having to predict how it would be named.
    reason = f"corpus opt-in fixture CLASS {match.group(1)}"
    annotation = f'@AILocked(reason = "{reason}")\n'
    return updated[:at] + annotation + updated[at:], reason


def annotate_nullable_method(path, text):
    match = NULLABLE_METHOD.search(text)
    if not match:
        return None, None
    updated = insert_import(text)
    if updated is None:
        return None, None
    match = NULLABLE_METHOD.search(updated)
    indent = match.group(1)
    at = match.start()
    reason = f"corpus opt-in fixture NULLABLE-PARAM {match.group(3)}"
    annotation = f'{indent}@AILocked(reason = "{reason}")\n'
    return updated[:at] + annotation + updated[at:], reason


def main():
    source_root = pathlib.Path(sys.argv[1])
    files = sorted(p for p in source_root.rglob("*.java") if p.name != "module-info.java")

    done_class = False
    done_nullable = False
    for path in files:
        text = path.read_text(encoding="utf-8", errors="replace")
        if not done_nullable and "@Nullable" in text:
            updated, reason = annotate_nullable_method(path, text)
            if updated:
                path.write_text(updated, encoding="utf-8", newline="")
                print(f"TARGET nullable-method {reason}")
                done_nullable = True
                continue
        if not done_class:
            updated, reason = annotate_class(path, text)
            if updated:
                path.write_text(updated, encoding="utf-8", newline="")
                print(f"TARGET class {reason}")
                done_class = True
    if not done_class and not done_nullable:
        print("TARGET none", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
