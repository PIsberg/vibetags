#!/usr/bin/env python3
"""The new-code quality gate: Sonar's "Sonar way for agentic AI" conditions, measured on what a
change adds, with the tools this build already runs.

Usage (from the repository root, after `mvn test -Pe2e` and `mvn verify` produced the reports):

    tools/quality-gate/run.sh <base-ref>

which calls this script with the diff base and the report paths. The conditions it decides:

  * coverage on new code    executable lines the change adds or edits under the measured source
                            root that the test suite ran, at least --coverage-threshold percent
                            (JaCoCo's jacoco.xml)
  * duplication on new code non-blank new lines inside a clone CPD reports, at most
                            --duplication-threshold percent (cpd.xml)
  * complexity of new code  no method that is new over PMD's CognitiveComplexity or NcssCount
                            threshold, and no existing method made worse (two PMD reports, one
                            for the change and one for the same sources at the base)

Reliability, security and dependency risk are not measured here: PMD, SpotBugs, Error Prone and
NullAway already fail the build on any finding in any code, and the dependency-review workflow
checks every added dependency. The report says so, so a reader of the gate sees all six.

Only the standard library is used, so the gate runs wherever python3 does. The XML it parses is
the reports the build wrote in the same job, not third-party input, so xml.etree is used rather
than defusedxml: Python's bundled expat refuses external entities and limits entity expansion.
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import namedtuple

MEASURED_ROOTS = ["vibetags/src/main/java"]
COMPLEXITY_THRESHOLDS = "CognitiveComplexity 15, NcssCount 60 per method"

_HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
_QUOTED = re.compile(r"'([^']+)'")
_NUMBER = re.compile(r"\d+")


# ------------------------------------------------------------------ the change

def added_lines(diff_text):
    """Lines a `git diff -U0` adds, as {path: {line number: text}}. Deleted files add nothing."""
    result = {}
    path = None
    line = None
    in_header = False
    for raw in diff_text.splitlines():
        if raw.startswith("diff --git "):
            in_header, path, line = True, None, None
            continue
        if in_header:
            if raw.startswith("+++ "):
                target = raw[4:].strip()
                path = None if target == "/dev/null" else (target[2:] if target.startswith("b/") else target)
            elif raw.startswith("@@"):
                in_header = False
            else:
                continue
        hunk = _HUNK.match(raw)
        if hunk:
            line = int(hunk.group(1))
            continue
        if path is None or line is None:
            continue
        if raw.startswith("+"):
            result.setdefault(path, {})[line] = raw[1:]
            line += 1
        elif raw.startswith(" "):
            line += 1
        # "-" lines and "\\ No newline at end of file" do not advance the new file.
    return result


def java_sources(added, roots):
    """The Java sources among the added lines that sit under one of the measured roots."""
    prefixes = [r.rstrip("/") + "/" for r in roots]
    return {p: lines for p, lines in added.items()
            if p.endswith(".java") and any(p.startswith(prefix) for prefix in prefixes)}


# ------------------------------------------------------------------ reports

def _local(tag):
    """An element's name without its namespace: PMD 7 namespaces its reports, older PMD did not."""
    return tag.rsplit("}", 1)[-1]


def _elements(root, name):
    return [e for e in root.iter() if _local(e.tag) == name]


def _relative(path, repo_root):
    norm = path.replace("\\", "/")
    root = repo_root.replace("\\", "/").rstrip("/") + "/"
    return norm[len(root):] if norm.lower().startswith(root.lower()) else norm


class Coverage(namedtuple("Coverage", "covered total uncovered")):
    def percent(self):
        return 100.0 if self.total == 0 else 100.0 * self.covered / self.total

    def passes(self, threshold):
        return self.percent() >= threshold


class Duplication(namedtuple("Duplication", "duplicated total")):
    def percent(self):
        return 0.0 if self.total == 0 else 100.0 * self.duplicated / self.total

    def passes(self, threshold):
        return self.percent() <= threshold


def coverage_on_new_code(jacoco_xml, added, source_root):
    """New executable lines the suite ran. A line JaCoCo does not list is not executable."""
    covered, total, uncovered = 0, 0, []
    root = ET.fromstring(jacoco_xml)
    for package in _elements(root, "package"):
        for source in [e for e in package if _local(e.tag) == "sourcefile"]:
            path = "%s/%s/%s" % (source_root.rstrip("/"), package.get("name"), source.get("name"))
            new = added.get(path)
            if not new:
                continue
            for row in [e for e in source if _local(e.tag) == "line"]:
                number = int(row.get("nr"))
                if number not in new:
                    continue
                missed, hit = int(row.get("mi", "0")), int(row.get("ci", "0"))
                if missed + hit == 0:
                    continue
                total += 1
                if hit > 0:
                    covered += 1
                else:
                    uncovered.append("%s:%d" % (path, number))
    return Coverage(covered, total, sorted(uncovered))


def duplication_on_new_code(cpd_xml, added, repo_root):
    """Non-blank new lines that fall inside a clone CPD reports, out of all non-blank new lines."""
    ranges = {}
    root = ET.fromstring(cpd_xml)
    for duplication in _elements(root, "duplication"):
        length = int(duplication.get("lines", "1"))
        for occurrence in [e for e in duplication if _local(e.tag) == "file"]:
            start = int(occurrence.get("line"))
            end = int(occurrence.get("endline", start + length - 1))
            ranges.setdefault(_relative(occurrence.get("path"), repo_root), []).append((start, end))
    duplicated, total = 0, 0
    for path, lines in added.items():
        spans = ranges.get(path, [])
        for number, text in lines.items():
            if not text.strip():
                continue
            total += 1
            if any(start <= number <= end for start, end in spans):
                duplicated += 1
    return Duplication(duplicated, total)


def _complexity(pmd_xml):
    """{(method key, rule): highest value} from a PMD report of the complexity ruleset."""
    found = {}
    root = ET.fromstring(pmd_xml)
    for violation in _elements(root, "violation"):
        message = (violation.text or "").strip()
        quoted = _QUOTED.search(message)
        signature = quoted.group(1) if quoted else (violation.get("method") or "")
        rest = message[quoted.end():] if quoted else message
        number = _NUMBER.search(rest)
        if number is None:
            continue
        key = ("%s.%s#%s" % (violation.get("package", ""), violation.get("class", ""), signature),
               violation.get("rule", ""))
        found[key] = max(found.get(key, 0), int(number.group(0)))
    return found


def complexity_regressions(head_pmd_xml, base_pmd_xml):
    """Methods over a threshold that were not before, or that the change made worse."""
    head, base = _complexity(head_pmd_xml), _complexity(base_pmd_xml)
    regressions = []
    for (method, rule), value in sorted(head.items()):
        before = base.get((method, rule))
        if before is None:
            regressions.append("%s: %s new at %d" % (method, rule, value))
        elif value > before:
            regressions.append("%s: %s %d -> %d" % (method, rule, before, value))
    return regressions


# ------------------------------------------------------------------ verdict

def render(coverage, duplication, complexity, coverage_threshold, duplication_threshold):
    """The gate's report as Markdown, and whether it failed."""
    cov_ok = coverage.passes(coverage_threshold)
    dup_ok = duplication.passes(duplication_threshold)
    cx_ok = not complexity
    failed = not (cov_ok and dup_ok and cx_ok)
    verdict = lambda ok: "pass" if ok else "**FAIL**"  # noqa: E731
    rows = [
        "| Condition (new code) | Threshold | Measured | Result |",
        "|---|---|---|---|",
        "| Coverage on new code | >= %.0f%% | %.1f%% (%d of %d executable lines) | %s |" % (
            coverage_threshold, coverage.percent(), coverage.covered, coverage.total, verdict(cov_ok)),
        "| Duplication on new code | <= %.0f%% | %.1f%% (%d of %d lines) | %s |" % (
            duplication_threshold, duplication.percent(), duplication.duplicated, duplication.total,
            verdict(dup_ok)),
        "| Complexity of new code | no new or worsened method over %s | %d | %s |" % (
            COMPLEXITY_THRESHOLDS, len(complexity), verdict(cx_ok)),
        "| Reliability and security | no finding | enforced on all code by PMD, SpotBugs, Error Prone and NullAway, which fail the build first | build |",
        "| Dependency risk | no vulnerable dependency added | enforced by the dependency-review workflow | workflow |",
    ]
    lines = ["## Quality gate on new code: %s" % ("FAILED" if failed else "PASSED"), ""] + rows
    if coverage.uncovered:
        lines += ["", "New lines no test runs (%d):" % len(coverage.uncovered), ""]
        lines += ["- %s" % u for u in coverage.uncovered[:50]]
        if len(coverage.uncovered) > 50:
            lines.append("- ... and %d more" % (len(coverage.uncovered) - 50))
    if complexity:
        lines += ["", "Methods over the complexity thresholds:", ""] + ["- %s" % c for c in complexity]
    return "\n".join(lines) + "\n", failed


def _read(path, what):
    if not path or not os.path.isfile(path):
        raise SystemExit("quality gate: the %s report %s does not exist; a gate that skips a missing "
                         "report passes without having measured anything" % (what, path))
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="commit the change is measured against")
    parser.add_argument("--repo-root", default=os.getcwd())
    parser.add_argument("--jacoco", required=True)
    parser.add_argument("--cpd", required=True)
    parser.add_argument("--pmd-head", required=True)
    parser.add_argument("--pmd-base", required=True)
    parser.add_argument("--coverage-threshold", type=float, default=80.0)
    parser.add_argument("--duplication-threshold", type=float, default=3.0)
    args = parser.parse_args(argv)

    diff = subprocess.run(
        ["git", "diff", "-U0", "--no-color", "--no-ext-diff", args.base, "HEAD", "--"] + MEASURED_ROOTS,
        cwd=args.repo_root, check=True, capture_output=True, text=True, encoding="utf-8").stdout
    added = java_sources(added_lines(diff), MEASURED_ROOTS)
    report, failed = render(
        coverage_on_new_code(_read(args.jacoco, "JaCoCo"), added, MEASURED_ROOTS[0]),
        duplication_on_new_code(_read(args.cpd, "CPD"), added, args.repo_root),
        complexity_regressions(_read(args.pmd_head, "PMD complexity"), _read(args.pmd_base, "base PMD complexity")),
        args.coverage_threshold, args.duplication_threshold)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(report)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
