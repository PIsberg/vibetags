#!/usr/bin/env python3
"""Rewrite every copy of a vibetags-parent/pom.xml version from the pom.

vibetags-parent/pom.xml defines every version. Files that cannot read it (Gradle wrappers, the
standalone examples, workflow env blocks, README snippets) carry a copy, and
vibetags-parent/version-mirrors.txt lists where. After changing a property in the pom, run this
and the copies follow; BuildVersionParityTest fails the build if one is left behind.

    python tools/sync-version-mirrors.py           # rewrite the copies, list what changed
    python tools/sync-version-mirrors.py --check   # change nothing, exit 1 if a copy is stale

The matching rules are the test's: same glob syntax, same pruning, same regex with the version in
group 1. If the two ever disagree, BuildVersionParityTest is the one CI runs.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PARENT = ROOT / "vibetags-parent" / "pom.xml"
MIRRORS = ROOT / "vibetags-parent" / "version-mirrors.txt"
NON_SOURCE_DIRS = {".git", ".gradle", "build", "target", "node_modules"}


def parent_properties():
    pom = PARENT.read_text(encoding="utf-8")
    block = re.search(r"<properties>(.*?)</properties>", pom, re.S)
    if not block:
        sys.exit(f"{PARENT}: no <properties> block")
    body = re.sub(r"<!--.*?-->", "", block.group(1), flags=re.S)
    return {m.group(1): m.group(2).strip()
            for m in re.finditer(r"<([A-Za-z0-9_.\-]+)>([^<]*)</\1>", body)}


def mirror_rows():
    rows = []
    for line in MIRRORS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        cells = line.split(None, 2)
        if len(cells) != 3:
            sys.exit(f"{MIRRORS}: a row needs a property, a glob and a regex: {line}")
        rows.append(cells)
    return rows


def glob_pattern(glob):
    out, i = [], 0
    while i < len(glob):
        if glob.startswith("**/", i):
            out.append("(?:[^/]+/)*")
            i += 3
        elif glob[i] == "*":
            out.append("[^/]*")
            i += 1
        else:
            out.append(re.escape(glob[i]))
            i += 1
    return re.compile("".join(out))


def source_files():
    """Root-relative paths, pruned like the test: build output, caches and nested checkouts."""
    found = []

    def walk(directory):
        for entry in sorted(directory.iterdir()):
            if entry.is_dir() and not entry.is_symlink():
                if entry.name in NON_SOURCE_DIRS or (entry / ".git").exists():
                    continue
                walk(entry)
            elif entry.is_file():
                found.append(entry.relative_to(ROOT).as_posix())

    walk(ROOT)
    return found


def main():
    check = "--check" in sys.argv[1:]
    props = parent_properties()
    files = source_files()
    stale, problems = [], []
    for prop, glob, regex in mirror_rows():
        expected = props.get(prop)
        if expected is None:
            problems.append(f"{prop}: not a property of vibetags-parent/pom.xml")
            continue
        glob_re, copy_re, seen = glob_pattern(glob), re.compile(regex), 0
        for rel in files:
            if not glob_re.fullmatch(rel):
                continue
            path = ROOT / rel
            with open(path, encoding="utf-8", newline="") as f:
                text = f.read()
            parts, last = [], 0
            for m in copy_re.finditer(text):
                seen += 1
                if m.group(1) != expected:
                    stale.append(f"{rel}: {prop} {m.group(1)} -> {expected}")
                    parts.append(text[last:m.start(1)] + expected)
                    last = m.end(1)
            if parts and not check:
                with open(path, "w", encoding="utf-8", newline="") as f:
                    f.write("".join(parts) + text[last:])
        if seen == 0:
            problems.append(f"{prop} in {glob}: no copy found, so the row checks nothing")
    for line in stale:
        print(("stale   " if check else "updated ") + line)
    for line in problems:
        print("ERROR   " + line, file=sys.stderr)
    if problems:
        return 2
    if check and stale:
        return 1
    if not stale:
        print("every copy matches vibetags-parent/pom.xml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
