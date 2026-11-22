"""Tests for quality_gate.py: the new-code quality gate.

Each case feeds the parser the shape the real tool writes (a git diff -U0, a JaCoCo XML report, a
CPD XML report, a PMD XML report) and asserts the verdict a pull request would get. Run with
`python3 -m unittest discover -s tools/quality-gate -v`.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import quality_gate as qg  # noqa: E402

ROOT = "/repo"
SRC = "vibetags/src/main/java"

DIFF = """diff --git a/vibetags/src/main/java/se/x/A.java b/vibetags/src/main/java/se/x/A.java
index 111..222 100644
--- a/vibetags/src/main/java/se/x/A.java
+++ b/vibetags/src/main/java/se/x/A.java
@@ -10,0 +11,3 @@ class A {
+    int a() {
+
+        return 1; }
@@ -20 +23 @@ class A {
-        old();
+        changed();
diff --git a/vibetags/src/main/java/se/x/Gone.java b/vibetags/src/main/java/se/x/Gone.java
deleted file mode 100644
--- a/vibetags/src/main/java/se/x/Gone.java
+++ /dev/null
@@ -1,2 +0,0 @@
-class Gone {
-}
diff --git a/docs/README.md b/docs/README.md
--- a/docs/README.md
+++ b/docs/README.md
@@ -1 +1 @@
-old
+new
"""


def jacoco(lines):
    rows = "".join('<line nr="%d" mi="%d" ci="%d" mb="0" cb="0"/>' % row for row in lines)
    return ('<?xml version="1.0"?><report name="x"><package name="se/x">'
            '<class name="se/x/A" sourcefilename="A.java"/>'
            '<sourcefile name="A.java">' + rows + '</sourcefile></package></report>')


def pmd(violations):
    body = "".join(
        '<file name="%s/%s/se/x/A.java">' % (ROOT, SRC)
        + '<violation beginline="%d" endline="%d" rule="%s" package="se.x" class="A" method="%s">%s</violation>'
        % v + "</file>"
        for v in violations)
    return '<?xml version="1.0"?><pmd version="7">' + body + "</pmd>"


class AddedLinesTest(unittest.TestCase):

    def test_reads_added_and_changed_lines_with_their_text(self):
        added = qg.added_lines(DIFF)
        self.assertEqual({11: "    int a() {", 12: "", 13: "        return 1; }", 23: "        changed();"},
                         added["vibetags/src/main/java/se/x/A.java"])

    def test_a_deleted_file_adds_nothing(self):
        self.assertNotIn("vibetags/src/main/java/se/x/Gone.java", qg.added_lines(DIFF))

    def test_only_java_sources_under_the_measured_root_count(self):
        self.assertEqual(["vibetags/src/main/java/se/x/A.java"],
                         sorted(qg.java_sources(qg.added_lines(DIFF), [SRC])))


class CoverageTest(unittest.TestCase):

    def test_counts_only_executable_new_lines(self):
        added = qg.java_sources(qg.added_lines(DIFF), [SRC])
        # 11 executable and covered, 12 blank (not in the report), 13 executable and missed,
        # 23 executable and covered; line 40 is old code and must not count.
        report = jacoco([(11, 0, 3), (13, 2, 0), (23, 0, 1), (40, 5, 0)])
        result = qg.coverage_on_new_code(report, added, SRC)
        self.assertEqual((2, 3), (result.covered, result.total))
        self.assertEqual(["vibetags/src/main/java/se/x/A.java:13"], result.uncovered)

    def test_no_executable_new_line_passes(self):
        result = qg.coverage_on_new_code(jacoco([]), {}, SRC)
        self.assertEqual((0, 0), (result.covered, result.total))
        self.assertTrue(result.passes(80.0))

    def test_threshold_is_inclusive(self):
        self.assertTrue(qg.Coverage(4, 5, []).passes(80.0))
        self.assertFalse(qg.Coverage(3, 4, []).passes(80.0))


class DuplicationTest(unittest.TestCase):

    def test_new_lines_inside_a_clone_count_as_duplicated(self):
        added = qg.java_sources(qg.added_lines(DIFF), [SRC])
        cpd = ('<?xml version="1.0"?><pmd-cpd><duplication lines="3" tokens="120">'
               '<file line="12" endline="14" path="%s\\%s\\se\\x\\A.java"/>'
               '<file line="50" endline="52" path="%s/%s/se/x/B.java"/>'
               '<codefragment>x</codefragment></duplication></pmd-cpd>') % (
                   ROOT.replace("/", "\\"), SRC.replace("/", "\\"), ROOT, SRC)
        result = qg.duplication_on_new_code(cpd, added, ROOT)
        # Non-blank new lines: 11, 13, 23. Inside the clone: 13 (12 is blank).
        self.assertEqual((1, 3), (result.duplicated, result.total))
        self.assertFalse(result.passes(3.0))

    def test_no_new_line_passes(self):
        result = qg.duplication_on_new_code('<pmd-cpd/>', {}, ROOT)
        self.assertTrue(result.passes(3.0))


class ComplexityTest(unittest.TestCase):

    COG = "CognitiveComplexity"

    def test_a_new_method_over_the_threshold_is_a_regression(self):
        head = pmd([(5, 30, self.COG, "fresh",
                     "The method 'fresh(int)' has a cognitive complexity of 22, current threshold is 15")])
        found = qg.complexity_regressions(head, pmd([]))
        self.assertEqual(1, len(found))
        self.assertIn("se.x.A#fresh(int)", found[0])
        self.assertIn("22", found[0])

    def test_an_old_method_left_as_complex_as_it_was_is_not(self):
        v = (5, 30, self.COG, "old", "The method 'old()' has a cognitive complexity of 40, current threshold is 15")
        self.assertEqual([], qg.complexity_regressions(pmd([v]), pmd([v])))

    def test_an_old_method_made_worse_is_a_regression(self):
        before = (5, 30, self.COG, "old", "The method 'old()' has a cognitive complexity of 40, current threshold is 15")
        after = (5, 34, self.COG, "old", "The method 'old()' has a cognitive complexity of 41, current threshold is 15")
        found = qg.complexity_regressions(pmd([after]), pmd([before]))
        self.assertEqual(1, len(found))
        self.assertIn("40 -> 41", found[0])

    def test_an_old_method_made_simpler_is_not(self):
        before = (5, 30, self.COG, "old", "The method 'old()' has a cognitive complexity of 40, current threshold is 15")
        after = (5, 20, self.COG, "old", "The method 'old()' has a cognitive complexity of 30, current threshold is 15")
        self.assertEqual([], qg.complexity_regressions(pmd([after]), pmd([before])))

    def test_overloads_are_told_apart_by_signature(self):
        base = (5, 30, self.COG, "run", "The method 'run(int)' has a cognitive complexity of 20, current threshold is 15")
        head_other = (40, 70, self.COG, "run",
                      "The method 'run(String)' has a cognitive complexity of 18, current threshold is 15")
        found = qg.complexity_regressions(pmd([base, head_other]), pmd([base]))
        self.assertEqual(1, len(found))
        self.assertIn("run(String)", found[0])

    def test_ncss_is_tracked_per_rule(self):
        cog = (5, 30, self.COG, "m", "The method 'm()' has a cognitive complexity of 20, current threshold is 15")
        ncss = (5, 30, "NcssCount", "m", "The method 'm()' has a NCSS line count of 70")
        found = qg.complexity_regressions(pmd([cog, ncss]), pmd([cog]))
        self.assertEqual(1, len(found))
        self.assertIn("NcssCount", found[0])


class GateTest(unittest.TestCase):

    def test_the_report_names_every_condition_and_the_verdict(self):
        report, failed = qg.render(qg.Coverage(9, 10, []), qg.Duplication(0, 10), [], 80.0, 3.0)
        self.assertFalse(failed)
        for expected in ("Coverage on new code", "Duplication on new code", "Complexity of new code",
                         "Reliability and security", "Dependency risk", "90.0%", "PASSED"):
            self.assertIn(expected, report)

    def test_any_failing_condition_fails_the_gate_and_says_why(self):
        report, failed = qg.render(qg.Coverage(1, 10, ["a.java:3"]), qg.Duplication(0, 10),
                                   ["se.x.A#m(): CognitiveComplexity new at 22"], 80.0, 3.0)
        self.assertTrue(failed)
        self.assertIn("FAILED", report)
        self.assertIn("a.java:3", report)
        self.assertIn("se.x.A#m()", report)


if __name__ == "__main__":
    unittest.main()
