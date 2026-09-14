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
    java_annotation_lines,
    load_locks,
    lock_key,
    locks_at,
    locks_for,
    parse_diff,
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


class MissingReportTest(unittest.TestCase):
    """No report is a failed run, not a passed one.

    The report is opted in by committing ``.vibetags-locks``; not finding it means the action is
    looking in the wrong directory or the project never opted in. Both used to print a warning
    and exit 0, so the check went green without guarding anything.
    """

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "T")
        with open(os.path.join(self.dir, "Src.java"), "w", encoding="utf-8") as fh:
            fh.write(UNLOCKED_SRC)
        self.git("add", "-A")
        self.git("commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        out = subprocess.run(["git", "-C", self.dir, *args], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        return out.stdout

    def run_guard(self, warn_only):
        cwd, env = os.getcwd(), dict(os.environ)
        os.chdir(self.dir)
        os.environ["VIBETAGS_BASE_REF"] = self.base
        os.environ["VIBETAGS_WARN_ONLY"] = "true" if warn_only else "false"
        try:
            return check_locked_diff.main()
        finally:
            os.chdir(cwd)
            os.environ.clear()
            os.environ.update(env)

    def test_no_report_fails_the_check(self):
        self.assertEqual(self.run_guard(warn_only=False), 1,
                         "a guard with no report to read must not pass")

    def test_no_report_is_a_warning_only_when_asked(self):
        self.assertEqual(self.run_guard(warn_only=True), 0)


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


class GitFixture:
    """A throwaway repository with a committed base and a guard run against it."""

    def init_repo(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "T")
        self.git("config", "core.autocrlf", "false")

    def git(self, *args):
        out = subprocess.run(["git", "-C", self.dir, *args], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        return out.stdout

    def write(self, rel, text):
        full = os.path.join(self.dir, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8", newline="") as fh:
            fh.write(text)

    def write_locks(self, entries):
        lines = ["# VIBETAGS-START", '{"type":"format","version":1}']
        lines += [json.dumps(entry) for entry in entries]
        lines.append("# VIBETAGS-END")
        self.write(".vibetags-locks", "\n".join(lines) + "\n")

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD").strip()

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


FIXTURE_TEST = (
    "class FixtureTest {\n"
    "    static final String SRC =\n"
    '        "package p;\\n" +\n'
    '        "@AILocked(reason = \\"original reason\\")\\n" +\n'
    '        "class Locked {}\\n";\n'
    "}\n"
)
FIXTURE_TEST_EDITED = FIXTURE_TEST.replace("original reason", "different reason")

LOCKED_METHOD_SRC = (
    "class Svc {\n"
    '    @AILocked(reason = "pinned")\n'
    "    int compute() {\n"
    "        return 1;\n"
    "    }\n"
    "}\n"
)
LOCKED_METHOD_ENTRY = {"type": "locked", "element": "Svc.compute()", "kind": "METHOD",
                       "file": "src/main/java/Svc.java", "startLine": 2, "endLine": 5,
                       "reason": "pinned"}


class AnnotationTextIsNotALockTest(GitFixture, unittest.TestCase):
    """``@AILocked`` text that is not an annotation must not fail the guard (#708).

    Measured: PR #707 changed only tests, and the guard failed it because
    ``FingerprintShortCircuitTest`` builds Java sources as string literals and the rewrite
    removed lines whose string contents read ``@AILocked(...)``. The removed-line check was a
    plain substring match, so fixture text, comments and text blocks all counted as lock
    stripping. The PR had to keep those fixture blocks byte-identical to pass.

    The other direction is pinned alongside: a real annotation removed, a real locked body
    edited and a real annotation added all behave as they did before.
    """

    def setUp(self):
        self.init_repo()

    def establish(self, files, locks=()):
        for rel, text in files.items():
            self.write(rel, text)
        self.write_locks(list(locks))
        self.base = self.commit("base")

    def change(self, files, locks=None, delete=()):
        for rel, text in files.items():
            self.write(rel, text)
        for rel in delete:
            os.remove(os.path.join(self.dir, rel))
        if locks is not None:
            self.write_locks(list(locks))
        self.commit("change")

    # --- annotation-like text: must pass -------------------------------------------------

    def test_editing_ailocked_text_inside_a_fixture_string_passes(self):
        self.establish({"src/test/java/FixtureTest.java": FIXTURE_TEST})
        self.change({"src/test/java/FixtureTest.java": FIXTURE_TEST_EDITED})
        self.assertEqual(self.run_guard(), 0,
                         "a string literal that reads @AILocked is not a lock")

    def test_removing_ailocked_text_inside_a_text_block_passes(self):
        base = ('class T {\n    String src = """\n        @AILocked(reason = "r")\n'
                '        class X {}\n        """;\n}\n')
        self.establish({"src/test/java/T.java": base})
        self.change({"src/test/java/T.java": base.replace('        @AILocked(reason = "r")\n', "")})
        self.assertEqual(self.run_guard(), 0)

    def test_removing_an_ailocked_mention_in_a_comment_passes(self):
        base = ("class T {\n    // mirrors @AILocked on the real class\n"
                "    /* and @AILocked(reason = \"x\") here\n     */\n    int f;\n}\n")
        self.establish({"src/main/java/T.java": base})
        self.change({"src/main/java/T.java": "class T {\n    int f;\n}\n"})
        self.assertEqual(self.run_guard(), 0)

    def test_deleting_a_file_whose_only_ailocked_is_string_text_passes(self):
        self.establish({"src/test/java/FixtureTest.java": FIXTURE_TEST,
                        "src/main/java/Keep.java": UNLOCKED_SRC})
        self.change({}, delete=["src/test/java/FixtureTest.java"])
        self.assertEqual(self.run_guard(), 0)

    # --- real annotations and locked code: must still fail -------------------------------

    def test_editing_a_real_locked_method_body_fails(self):
        self.establish({"src/main/java/Svc.java": LOCKED_METHOD_SRC}, [LOCKED_METHOD_ENTRY])
        self.change({"src/main/java/Svc.java": LOCKED_METHOD_SRC.replace("return 1;", "return 2;")})
        self.assertEqual(self.run_guard(), 1)

    def test_stripping_a_real_annotation_fails(self):
        self.establish({"src/main/java/Src.java": LOCKED_SRC})
        self.change({"src/main/java/Src.java": UNLOCKED_SRC})
        self.assertEqual(self.run_guard(), 1,
                         "removing a real @AILocked must still need human review")

    def test_stripping_a_real_annotation_from_a_test_source_fails(self):
        """No directory is exempt: javac runs the processor over test sources too."""
        self.establish({"src/test/java/Src.java": LOCKED_SRC})
        self.change({"src/test/java/Src.java": UNLOCKED_SRC})
        self.assertEqual(self.run_guard(), 1)

    def test_stripping_a_real_annotation_next_to_a_string_fails(self):
        base = 'class Src {\n    String s = "@AILocked"; @AILocked(reason = "r") int f = 1;\n}\n'
        self.establish({"src/main/java/Src.java": base})
        self.change({"src/main/java/Src.java": 'class Src {\n    String s = "@AILocked"; int f = 1;\n}\n'})
        self.assertEqual(self.run_guard(), 1)

    def test_stripping_a_spaced_or_qualified_annotation_fails(self):
        base = ("class Src {\n    @ AILocked(reason = \"a\")\n    int a;\n"
                "    @se.deversity.vibetags.annotations.AILocked(reason = \"b\")\n    int b;\n}\n")
        self.establish({"src/main/java/Src.java": base})
        self.change({"src/main/java/Src.java": "class Src {\n    int a;\n    int b;\n}\n"})
        self.assertEqual(self.run_guard(), 1)

    def test_unicode_escaped_quote_does_not_hide_a_real_annotation(self):
        """javac translates a unicode escape for a quote (u0022) before it lexes anything.

        So the base line reads ``String s = ""; @AILocked(reason = "r") int f = 1;``. A lexer
        that skipped the translation would see one string running from the first raw quote to
        the second, swallowing the annotation, and the rest of the file would still lex, so
        the substring fallback would not rescue it either.
        """
        base = ("class Src {\n"
                '    String s = \\u0022"; @AILocked(reason = \\u0022r") int f = 1;\n'
                "}\n")
        self.establish({"src/main/java/Src.java": base})
        self.change({"src/main/java/Src.java": 'class Src {\n    String s = ""; int f = 1;\n}\n'})
        self.assertEqual(self.run_guard(), 1)

    def test_a_source_that_does_not_lex_falls_back_to_the_text_match(self):
        """When the file cannot be lexed the guard cannot tell, so it fails."""
        base = 'class Src {\n    String s = "unterminated @AILocked\n    int f = 1;\n}\n'
        self.establish({"src/main/java/Src.java": base})
        self.change({"src/main/java/Src.java": "class Src {\n    int f = 1;\n}\n"})
        self.assertEqual(self.run_guard(), 1)

    def test_deleting_a_file_with_a_real_annotation_fails(self):
        self.establish({"src/main/java/Src.java": LOCKED_SRC,
                        "src/main/java/Keep.java": UNLOCKED_SRC})
        self.change({}, delete=["src/main/java/Src.java"])
        self.assertEqual(self.run_guard(), 1)

    def test_kotlin_sources_keep_the_text_match(self):
        """Only Java is lexed. Kotlin string templates nest, so Kotlin stays conservative."""
        base = 'class T {\n    val src = "@AILocked(reason = \\"r\\")"\n}\n'
        self.establish({"src/test/kotlin/T.kt": base})
        self.change({"src/test/kotlin/T.kt": "class T {\n}\n"})
        self.assertEqual(self.run_guard(), 1)

    def test_adding_a_real_annotation_behaves_as_before(self):
        self.establish({"src/main/java/Src.java": UNLOCKED_SRC})
        entry = dict(LOCK_ENTRY, file="src/main/java/Src.java")
        self.change({"src/main/java/Src.java": LOCKED_SRC}, locks=[entry])
        self.assertEqual(self.run_guard(), 0,
                         "introducing a lock must not fail the PR that introduces it")


class ParseDiffTest(unittest.TestCase):
    """Removed lines carry their base line number, which the lock lookup depends on."""

    DIFF = "\n".join([
        "diff --git a/A.java b/A.java",
        "index 1111111..2222222 100644",
        "--- a/A.java",
        "+++ b/A.java",
        "@@ -3,2 +2,0 @@",
        "--- not a header",
        "-second",
        "@@ -9 +7 @@",
        "-ninth",
        "+new ninth",
        "",
    ])

    def test_removed_lines_are_numbered_from_the_old_side_of_each_hunk(self):
        [(path, old_path, hunks, removed)] = list(parse_diff(self.DIFF))
        self.assertEqual((path, old_path), ("A.java", "A.java"))
        self.assertEqual(hunks, [(2, 0), (7, 1)])
        self.assertEqual(removed, [(3, "-- not a header"), (4, "second"), (9, "ninth")],
                         "a removed line reading '-- ' is content, not a new file header")


class JavaAnnotationLinesTest(unittest.TestCase):
    """The lexer that decides which base lines carry a real ``@AILocked``."""

    def test_finds_a_plain_annotation(self):
        self.assertEqual(java_annotation_lines(LOCKED_SRC), {2})

    def test_ignores_string_char_text_block_and_comment_text(self):
        src = ('class A {\n'
               '    String s = "@AILocked";\n'
               "    char c = '\"'; String t = \"x\\\"@AILocked\";\n"
               '    String u = """\n        @AILocked\n        """;\n'
               '    // @AILocked\n'
               '    /* @AILocked */\n'
               '}\n')
        self.assertEqual(java_annotation_lines(src), set())

    def test_a_char_literal_quote_does_not_open_a_string(self):
        src = "class A {\n    char c = '\"';\n    @AILocked int f;\n}\n"
        self.assertEqual(java_annotation_lines(src), {3})

    def test_an_annotation_split_across_lines_marks_every_line(self):
        src = "class A {\n    @\n    /* gap */ AILocked int f;\n}\n"
        self.assertEqual(java_annotation_lines(src), {2, 3})

    def test_an_identifier_ignorable_character_does_not_rename_the_lock(self):
        """javac drops a soft hyphen from an identifier, so this still reads AILocked."""
        src = "class A {\n    @AI\u00adLocked int f;\n}\n"
        self.assertEqual(java_annotation_lines(src), {2})

    def test_a_unicode_escape_in_the_name_still_names_the_lock(self):
        src = "class A {\n    @\\u0041ILocked int f;\n}\n"
        self.assertEqual(java_annotation_lines(src), {2})

    def test_other_annotations_and_the_declaration_do_not_count(self):
        src = "public @interface AILocked {}\n@AILockedLater class B {}\n@Deprecated class C {}\n"
        self.assertEqual(java_annotation_lines(src), set())

    def test_lone_carriage_returns_do_not_shift_git_line_numbers(self):
        src = "class A {\r\n    // note\r    @AILocked int f;\r\n}\r\n"
        self.assertEqual(java_annotation_lines(src), {2})

    def test_unterminated_constructs_are_unsure(self):
        for src in ('class A { String s = "open\n}\n',
                    "class A { /* open\n}\n",
                    'class A { String s = """\n open\n}\n',
                    'class A { String s = """ not-a-newline """; }\n',
                    "class A { char c = 'x\n}\n",
                    "class A { String s = \"\\uZZZZ\"; }\n"):
            with self.subTest(src=src):
                self.assertIsNone(java_annotation_lines(src))


if __name__ == "__main__":
    unittest.main()
