"""Every platform file VibeTags can write, opted in and then read back with a real parser.

The fixture tests in this repository assert what a renderer *contains*. None of them asserts
that a real parser accepts it, and those are different questions: a YAML renderer that emits an
unquoted value starting with `@`, or a JSON one that leaves a trailing comma, produces output
that satisfies every `contains` assertion ever written and that no consumer can load. The
renderers nobody looks at are the cheapest ones to break (#489).

Two modes:

  list <ServiceRegistry.java>   prints one relative path per line, extracted from the registry
                                itself so the list cannot drift from what the code writes
  verify <root>                 checks every file that exists is non-empty, and parses the ones
                                with a structured format

The list is read out of the source rather than maintained here on purpose. A hand-kept copy of
the registry is a second source of truth, and the failure it produces is the quiet kind: a new
platform is added, nothing lists it, and the sweep reports success over a set that no longer
matches reality.
"""
import json
import pathlib
import re
import sys

try:
    import tomllib  # Python 3.11+
except ModuleNotFoundError:  # pragma: no cover - the CI and dev JDK images both have 3.11+
    tomllib = None

try:
    import yaml
except ModuleNotFoundError:
    yaml = None

# Pairs the service key with the path it resolves to, because the key is what says whether the
# opt-in is a file or a directory. Guessing from the filename does not work and failed loudly:
# ".cursorrules" and ".claude/rules" both end in "rules" and only one is a directory, so a
# name-based heuristic created most opt-ins as directories, the processor saw two services
# instead of sixty, and the sweep measured almost nothing.
ENTRY = re.compile(r'map\.put\(\s*"([^"]+)"\s*,\s*root\.resolve\(\s*"([^"]+)"\s*\)')

# Opt-ins that are a mode switch rather than a destination. Touching .vibetags-root-index turns
# the root aggregate into a lean index (docs/MULTI-MODULE.md); its presence is the whole message
# and it is never written to. ServiceRegistry says the same thing in code, excluding root_index
# from the keys it treats as output files. So "opted in and still empty" is correct here, and
# asserting otherwise would be the check misunderstanding the design rather than finding a bug.
SWITCHES = {"root_index"}


def list_entries(registry: pathlib.Path):
    """Yields (kind, path) where kind is "dir" for a granular rules directory, else "file"."""
    text = registry.read_text(encoding="utf-8")
    seen, entries = set(), []
    for m in ENTRY.finditer(text):
        key, path = m.group(1), m.group(2)
        if path in seen:
            continue
        seen.add(path)
        # A granular service resolves to the directory its per-element rule files live in.
        if key.endswith("_granular"):
            kind = "dir"
        elif key in SWITCHES:
            kind = "switch"
        else:
            kind = "file"
        entries.append((kind, path))
    return entries


def list_paths(registry: pathlib.Path):
    return [p for _, p in list_entries(registry)]


def parse(path: pathlib.Path):
    """Returns None when the file parses, or a reason string when it does not."""
    suffix = path.suffix.lower()
    raw = path.read_text(encoding="utf-8", errors="replace")
    try:
        if suffix == ".json":
            json.loads(raw)
        elif suffix in (".yaml", ".yml"):
            if yaml is None:
                return "SKIP: PyYAML is not installed"
            # safe_load, not load: this is somebody's generated config, and the check is whether
            # a consumer's parser accepts it, not whether Python can be talked into anything.
            yaml.safe_load(raw)
        elif suffix == ".toml":
            if tomllib is None:
                return "SKIP: tomllib is not available"
            tomllib.loads(raw)
        else:
            return None
    except Exception as e:  # noqa: BLE001 - any parser complaint is the finding
        first = str(e).split("\n")[0]
        return f"{type(e).__name__}: {first}"
    return None


def verify(root: pathlib.Path, registry: pathlib.Path):
    failures = []
    absent, dirs = [], []
    checked = parsed = 0
    for kind, rel in list_entries(registry):
        if kind == "switch":
            continue
        path = root / rel
        if not path.exists():
            # Not every service is opted in for every run; absence is the opt-out, not a fault.
            absent.append(rel)
            continue
        if path.is_dir():
            dirs.append(rel)
            continue
        checked += 1
        if path.stat().st_size == 0:
            failures.append(f"{rel}: opted in and written empty")
            continue
        problem = parse(path)
        if problem is None:
            if path.suffix.lower() in (".json", ".yaml", ".yml", ".toml"):
                parsed += 1
        elif problem.startswith("SKIP:"):
            failures.append(f"{rel}: {problem} - the parser this needs is missing, so this file "
                            f"was not actually checked")
        else:
            failures.append(f"{rel}: {problem}")
    return checked, parsed, failures, absent, dirs


def main():
    # Newlines stay LF even on Windows. Python's text stdout translates "\n" to "\r\n" there,
    # and a carriage return kept in a path creates an opt-in file literally named
    # ".aiderignore\r": the shell makes it, the processor never matches it, and the sweep
    # reports two active services out of sixty while every seeding check says it worked.
    # Fixed here rather than with a `tr` at each call site, because the next caller will forget.
    sys.stdout.reconfigure(newline="\n")
    mode = sys.argv[1]
    if mode == "list":
        # kind TAB path, so the caller knows whether to create a file or a directory.
        for kind, path in list_entries(pathlib.Path(sys.argv[2])):
            print(f"{kind}	{path}")
        return 0
    if mode == "verify":
        root = pathlib.Path(sys.argv[2])
        registry = pathlib.Path(sys.argv[3])
        checked, parsed, failures, absent, dirs = verify(root, registry)
        print(f"PLATFORM-FILES\t{checked}")
        print(f"PARSED\t{parsed}")
        for f in failures:
            print(f"FAIL\t{f}")
        return 1 if failures else 0
    print(f"unknown mode: {mode}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
