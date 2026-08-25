#!/usr/bin/env python3
"""Unit tests for the locked-files guard.

The guard had no tests, and shipped two false-positive sources that a single fixture would
have caught. Both are pinned here, with the measurement that found them in the docstring so a
future reader knows what the assertion is defending rather than guessing from the name.

Run: python3 -m unittest discover -s action/locked-files
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import check_locked_diff  # noqa: E402
from check_locked_diff import (  # noqa: E402
    added_paths,
    load_locks,
    lock_key,
    locks_at,
    locks_for,
    parse_lock_entries,
    resolve_lock_path,
)

REPO = "/repo"

UNLOCKED_SRC = "class Src {\n    int field = 1;\n}\n"
LOCKED_SRC = 'class Src {\n    @AILocked(reason = "pinned")\n    int field = 1;\n}\n'
EDITED_LOCKED_SRC = 'class Src {\n    @AILocked(reason = "pinned")\n    int field = 99;\n}\n'
LOCK_ENTRY = {"type": "locked", "element": "Src.field", "kind": "FIELD", "file": "Src.java",
              "startLine": 2, "endLine": 4, "reason": "pinned"}


class ResolveLockPathTest(unittest.TestCase):
    """A lock's path is relative to its own report, not to the repository."""

    def test_relative_path_is_resolved_against_the_reports_directory(self):
        self.assertEqual(
            "examples/multimodule/showcase/src/Main.java",
            resolve_lock_path("showcase/src/Main.java", "/repo/examples/multimodule", REPO),
        )

    def test_report_at_the_repo_root_keeps_the_recorded_path(self):
        self.assertEqual(
            "vibetags/src/Main.java",
            resolve_lock_path("vibetags/src/Main.java", REPO, REPO),
        )

    def test_absolute_path_is_made_repo_relative(self):
        self.assertEqual(
            "vibetags/src/Main.java",
            resolve_lock_path("/repo/vibetags/src/Main.java", REPO, REPO),
        )

    def test_two_reactors_sharing_a_relative_path_resolve_differently(self):
        """The measured defect: nine false violations from a sibling example's report.

        Both reactors had a module at showcase/, so both recorded the identical string. The
        Maven example's locks were blaming the Gradle example's diff and vice versa.
        """
        maven = resolve_lock_path(
            "showcase/src/App.java", "/repo/examples/multimodule", REPO)
        gradle = resolve_lock_path(
            "showcase/src/App.java", "/repo/examples/gradle-multimodule", REPO)
        self.assertNotEqual(maven, gradle)


class LocksForTest(unittest.TestCase):
    def test_matches_only_the_exact_path(self):
        locks = [
            {"file": "examples/multimodule/showcase/src/App.java", "element": "maven"},
            {"file": "examples/gradle-multimodule/showcase/src/App.java", "element": "gradle"},
        ]
        matched = locks_for(locks, "examples/gradle-multimodule/showcase/src/App.java")
        self.assertEqual(["gradle"], [lock["element"] for lock in matched])

    def test_a_shorter_path_is_not_a_match_by_suffix(self):
        """The old fallback matched either path as a suffix of the other. That is what let
        one reactor's locks claim another's files."""
        locks = [{"file": "showcase/src/App.java", "element": "somewhere-else"}]
        self.assertEqual(
            [], locks_for(locks, "examples/gradle-multimodule/showcase/src/App.java"))

    def test_backslashes_are_normalized(self):
        locks = [{"file": "a/b/C.java", "element": "x"}]
        self.assertEqual(1, len(locks_for(locks, "a\\b\\C.java")))


class AddedPathsTest(unittest.TestCase):
    def test_collects_only_added_files(self):
        text = "A\tnew/File.java\nM\told/File.java\nD\tgone/File.java\n"
        self.assertEqual({"new/File.java"}, added_paths(text))

    def test_renames_are_not_treated_as_additions(self):
        """A rename must stay in scope: moving a locked file is still touching it."""
        text = "R096\told/File.java\tnew/File.java\n"
        self.assertEqual(set(), added_paths(text))

    def test_tolerates_blank_and_malformed_lines(self):
        self.assertEqual({"x/Y.java"}, added_paths("\nA\tx/Y.java\ngarbage\n\n"))


class LoadLocksTest(unittest.TestCase):
    def test_entries_from_two_reports_do_not_collide(self):
        with tempfile.TemporaryDirectory() as root:
            first = os.path.join(root, "one")
            second = os.path.join(root, "two")
            os.makedirs(first)
            os.makedirs(second)
            line = ('{"type":"locked","element":"E",'
                    '"file":"showcase/src/App.java","startLine":1,"endLine":9}\n')
            for directory in (first, second):
                with open(os.path.join(directory, ".vibetags-locks"), "w", encoding="utf-8") as fh:
                    fh.write("# VIBETAGS-START\n")
                    fh.write(line)
                    fh.write("# VIBETAGS-END\n")

            locks = load_locks(
                [os.path.join(first, ".vibetags-locks"),
                 os.path.join(second, ".vibetags-locks")],
                root,
            )
            self.assertEqual(
                ["one/showcase/src/App.java", "two/showcase/src/App.java"],
                sorted(lock["file"] for lock in locks),
            )
            self.assertEqual(
                1, len(locks_for(locks, "two/showcase/src/App.java")),
                "a lock declared by one report must not match the other reactor's file")

    def test_non_locked_entries_are_ignored(self):
        with tempfile.TemporaryDirectory() as root:
            report = os.path.join(root, ".vibetags-locks")
            with open(report, "w", encoding="utf-8") as fh:
                fh.write('{"type":"other","file":"a/B.java"}\n')
                fh.write('not json at all\n')
                fh.write('{"type":"locked","element":"E","file":"a/B.java"}\n')
            locks = load_locks([report], root)
            self.assertEqual(1, len(locks))


if __name__ == "__main__":
    unittest.main()


class IntroducingALockTest(unittest.TestCase):
    """A PR may adopt @AILocked on code that already exists.

    Adding the annotation is itself a change to the lines the lock now covers, so the range
    check flags the very commit that introduces it. The file-level ``created`` exemption does
    not help here: the file already existed. Measured on the PR that locked
    ``GuardrailAnnotations.ALL`` and ``TransitiveManifest.RESOURCE_PACKAGE`` -- one violation
    each, for doing exactly what this project tells its users to do.

    A lock that was already there is still enforced, and stripping one is still check 2.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "T")
        self.write("Src.java", UNLOCKED_SRC)
        self.write_locks([])
        self.git("add", "-A")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        out = subprocess.run(["git", "-C", self.dir, *args], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        return out.stdout

    def write(self, rel, text):
        with open(os.path.join(self.dir, rel), "w", encoding="utf-8", newline="") as fh:
            fh.write(text)

    def write_locks(self, entries):
        lines = ["# VIBETAGS-START", '{"type":"format","version":1}']
        lines += [json.dumps(entry) for entry in entries]
        lines.append("# VIBETAGS-END")
        self.write(".vibetags-locks", "\n".join(lines) + "\n")

    def run_guard(self):
        cwd, env = os.getcwd(), dict(os.environ)
        os.chdir(self.dir)
        os.environ["VIBETAGS_BASE_REF"] = self.base
        os.environ["VIBETAGS_WARN_ONLY"] = "false"
        try:
            return check_locked_diff.main()
        finally:
            os.chdir(cwd)
            os.environ.clear()
            os.environ.update(env)

    def test_adding_a_lock_to_existing_code_is_not_a_violation(self):
        self.write("Src.java", LOCKED_SRC)
        self.write_locks([LOCK_ENTRY])
        self.git("add", "-A")
        self.git("commit", "-qm", "adopt the lock")
        self.assertEqual(self.run_guard(), 0,
                         "introducing a lock must not fail the PR that introduces it")

    def test_a_lock_that_existed_at_base_is_still_enforced(self):
        self.write("Src.java", LOCKED_SRC)
        self.write_locks([LOCK_ENTRY])
        self.git("add", "-A")
        self.git("commit", "-qm", "declare the lock")
        self.base = self.git("rev-parse", "HEAD").strip()
        self.write("Src.java", EDITED_LOCKED_SRC)
        self.git("add", "-A")
        self.git("commit", "-qm", "edit locked code")
        self.assertEqual(self.run_guard(), 1,
                         "an established lock must still block a change to its range")


class LocksAtTest(unittest.TestCase):
    """``locks_at`` reads the base side, and tolerates a report that was not there."""

    def test_missing_report_contributes_nothing(self):
        missing = os.path.join(REPO, "nope", ".vibetags-locks")
        self.assertEqual(locks_at("HEAD", [missing], REPO), set())

    def test_entries_are_keyed_by_file_and_element(self):
        text = ('# VIBETAGS-START\n'
                '{"type":"format","version":1}\n'
                '{"type":"locked","element":"a.B.c","file":"src/B.java"}\n'
                '# VIBETAGS-END\n')
        locks = parse_lock_entries(text, REPO, REPO)
        self.assertEqual([lock_key(lock) for lock in locks], [("src/B.java", "a.B.c")])
