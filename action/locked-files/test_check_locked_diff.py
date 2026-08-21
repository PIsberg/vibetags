#!/usr/bin/env python3
"""Unit tests for the locked-files guard.

The guard had no tests, and shipped two false-positive sources that a single fixture would
have caught. Both are pinned here, with the measurement that found them in the docstring so a
future reader knows what the assertion is defending rather than guessing from the name.

Run: python3 -m unittest discover -s action/locked-files
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from check_locked_diff import (  # noqa: E402
    added_paths,
    load_locks,
    locks_for,
    resolve_lock_path,
)

REPO = "/repo"


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
