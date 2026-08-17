package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the {@code vibetags-usage} skill's element cheat sheet to the annotation sources.
 *
 * <p>The cheat sheet exists because nothing at a call site tells you whether an annotation has a
 * {@code value()} element. A consumer reported reaching for {@code javap} to discover that
 * {@code @AIExplain} takes {@code value()} rather than {@code complexityLevel()}, so the skill now
 * states every element of all 44 annotations. That table is hand-written prose about compiled
 * facts, which is exactly the shape that rots silently: adding an element, giving one a default or
 * renaming an enum constant leaves the table confidently wrong and nothing disagrees.
 *
 * <p>Four claims are checked against {@code vibetags-annotations}:
 *
 * <ul>
 *   <li>every annotation has a row, and every row an annotation;</li>
 *   <li>every element is listed, bolded exactly when it has no default. Omitting one of those
 *       fails the consumer's build, so it is the single thing the reader must not be wrong
 *       about;</li>
 *   <li>every enum constant is listed, so the documented constants are the ones that exist;</li>
 *   <li>the "positional form" and "will not compile bare" lists hold exactly the right
 *       annotations, including the English word-numbers in the prose introducing them.</li>
 * </ul>
 *
 * <p>Skips gracefully if run from a working directory where the repo layout isn't reachable.
 */
class SkillElementTableConsistencyTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    private static final Path SKILL = REPO_ROOT.resolve(".claude/skills/vibetags-usage/SKILL.md");

    private static final Path ANNOTATIONS_DIR = REPO_ROOT.resolve(
        "vibetags-annotations/src/main/java/se/deversity/vibetags/annotations");

    /** An annotation element: four leading spaces, a type, a name, {@code ()}, an optional default. */
    private static final Pattern ELEMENT = Pattern.compile(
        "^ {4}([\\w<>\\[\\], .]+?)\\s+(\\w+)\\(\\)\\s*(default\\s+[^;]+)?;", Pattern.MULTILINE);

    private static final Pattern ENUM_BODY = Pattern.compile(
        "enum\\s+\\w+\\s*\\{(.*?)\\}", Pattern.DOTALL);

    /** A row of the full element table: {@code | `@AIFoo` | ...elements... |}. */
    private static final Pattern TABLE_ROW = Pattern.compile(
        "^\\| `@(\\w+)` \\| (.+) \\|$", Pattern.MULTILINE);

    /** A row of the positional-form table: its second cell opens with the annotation applied. */
    private static final Pattern POSITIONAL_ROW = Pattern.compile(
        "^\\| `@(\\w+)` \\| `@\\w+\\(", Pattern.MULTILINE);

    /** An entry of the "will not compile bare" list: {@code `@AIFoo(element)`}. */
    private static final Pattern BARE_ENTRY = Pattern.compile("`@(\\w+)\\(\\w+(?:, \\w+)*\\)`");

    private static final String TABLE_START = "**Every element, in full.**";
    private static final String TABLE_END = "Enum constants are nested types";

    private static String skillText() throws IOException {
        assumeTrue(Files.isRegularFile(SKILL), "vibetags-usage skill not reachable; skipping");
        assumeTrue(Files.isDirectory(ANNOTATIONS_DIR), "annotations module not reachable; skipping");
        return Files.readString(SKILL, StandardCharsets.UTF_8);
    }

    /** The slice of {@code text} between two literal markers, both of which must be present. */
    private static String section(String text, String from, String to) {
        int start = text.indexOf(from);
        assertTrue(start >= 0, "cheat sheet marker missing from the skill: " + from);
        int end = text.indexOf(to, start);
        assertTrue(end > start, "cheat sheet marker missing from the skill: " + to);
        return text.substring(start, end);
    }

    /** Source text of every {@code @AI*} annotation, keyed by simple name. */
    private static Map<String, String> annotationSources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(ANNOTATIONS_DIR)) {
            stream.filter(p -> p.getFileName().toString().startsWith("AI"))
                .filter(p -> p.toString().endsWith(".java"))
                .sorted()
                .forEach(files::add);
        }
        for (Path file : files) {
            String name = file.getFileName().toString().replace(".java", "");
            sources.put(name, Files.readString(file, StandardCharsets.UTF_8));
        }
        return sources;
    }

    /** Comment-free source, so a {@code value()} named only in Javadoc is not mistaken for one. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** The full element table, keyed by annotation name, valued by its elements cell. */
    private static Map<String, String> tableCells(String skill) {
        Map<String, String> cells = new LinkedHashMap<>();
        Matcher rows = TABLE_ROW.matcher(section(skill, TABLE_START, TABLE_END));
        while (rows.find()) {
            cells.put(rows.group(1), rows.group(2));
        }
        return cells;
    }

    /** Annotations declaring an element named {@code name}, or any element with no default. */
    private static SortedSet<String> annotationsDeclaring(Map<String, String> sources, String name) {
        SortedSet<String> found = new TreeSet<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Matcher elements = ELEMENT.matcher(stripComments(entry.getValue()));
            while (elements.find()) {
                boolean hit = name == null ? elements.group(3) == null : name.equals(elements.group(2));
                if (hit) {
                    found.add(entry.getKey());
                    break;
                }
            }
        }
        return found;
    }

    @Test
    void everyAnnotationHasARowAndEveryRowAnAnnotation() throws IOException {
        SortedSet<String> rows = new TreeSet<>(tableCells(skillText()).keySet());
        assertEquals(new TreeSet<>(annotationSources().keySet()), rows,
            "the vibetags-usage element table and vibetags-annotations disagree about which "
                + "annotations exist; update .claude/skills/vibetags-usage/SKILL.md");
    }

    @Test
    void everyElementIsListedAndBoldedExactlyWhenItHasNoDefault() throws IOException {
        Map<String, String> cells = tableCells(skillText());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : annotationSources().entrySet()) {
            String cell = cells.get(entry.getKey());
            if (cell == null) {
                continue;   // reported by everyAnnotationHasARowAndEveryRowAnAnnotation
            }
            Matcher elements = ELEMENT.matcher(stripComments(entry.getValue()));
            while (elements.find()) {
                String element = elements.group(2);
                boolean required = elements.group(3) == null;
                boolean bolded = cell.contains("**`" + element + "`**");
                if (!cell.contains("`" + element + "`")) {
                    problems.add(entry.getKey() + "." + element + " is missing from the table");
                } else if (required && !bolded) {
                    problems.add(entry.getKey() + "." + element
                        + " has no default but is not bolded as required");
                } else if (!required && bolded) {
                    problems.add(entry.getKey() + "." + element
                        + " has a default but is bolded as required");
                }
            }
        }
        assertTrue(problems.isEmpty(),
            "the vibetags-usage element table is out of date:\n  " + String.join("\n  ", problems));
    }

    @Test
    void everyEnumConstantIsListed() throws IOException {
        Map<String, String> cells = tableCells(skillText());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : annotationSources().entrySet()) {
            String cell = cells.get(entry.getKey());
            if (cell == null) {
                continue;
            }
            Matcher enums = ENUM_BODY.matcher(stripComments(entry.getValue()));
            while (enums.find()) {
                for (String constant : enums.group(1).split(",")) {
                    String trimmed = constant.trim();
                    if (!trimmed.isEmpty() && !cell.contains("`" + trimmed + "`")) {
                        problems.add(entry.getKey() + "." + trimmed + " is missing from the table");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(),
            "the vibetags-usage element table lists the wrong enum constants:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void thePositionalFormListHoldsExactlyTheAnnotationsDeclaringValue() throws IOException {
        String skill = skillText();
        SortedSet<String> actual = annotationsDeclaring(annotationSources(), "value");

        String heading = "**The " + englishNumber(actual.size()) + " that take the positional form:**";
        assertTrue(skill.contains(heading),
            "the positional-form heading states the wrong count; expected: " + heading);

        SortedSet<String> documented = new TreeSet<>();
        Matcher rows = POSITIONAL_ROW.matcher(
            section(skill, heading, "Every row above was compiled to check it."));
        while (rows.find()) {
            documented.add(rows.group(1));
        }
        assertEquals(actual, documented,
            "the skill's positional-form table does not match the annotations declaring value()");
    }

    @Test
    void theWillNotCompileBareListHoldsExactlyTheAnnotationsWithARequiredElement() throws IOException {
        String skill = skillText();
        Map<String, String> sources = annotationSources();
        SortedSet<String> actual = annotationsDeclaring(sources, null);

        String heading = "**The " + englishNumber(actual.size()) + " that will not compile bare.**";
        assertTrue(skill.contains(heading),
            "the \"will not compile bare\" heading states the wrong count; expected: " + heading);

        SortedSet<String> documented = new TreeSet<>();
        Matcher entries = BARE_ENTRY.matcher(section(skill, heading, "The other "));
        while (entries.find()) {
            documented.add(entries.group(1));
        }
        assertEquals(actual, documented,
            "the skill's \"will not compile bare\" list does not match the annotations that have "
                + "an element with no default");

        int total = sources.size();
        int positional = annotationsDeclaring(sources, "value").size();
        String opening = "**" + capitalize(englishNumber(total - positional))
            + " of the " + englishNumber(total) + " do not**";
        assertTrue(skill.contains(opening),
            "the cheat sheet's opening sentence states the wrong counts; expected: " + opening);

        String tail = "The other " + englishNumber(total - actual.size()) + " are usable bare";
        assertTrue(skill.contains(tail),
            "the \"usable bare\" sentence states the wrong count; expected: " + tail);
    }

    private static final String[] UNITS = {
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen"};

    private static final String[] TENS = {
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /** English for 0-99, hyphenated above twenty, matching how the skill writes its counts. */
    private static String englishNumber(int n) {
        assertTrue(n >= 0 && n < 100, "no English spelling for " + n);
        if (n < 20) {
            return UNITS[n];
        }
        return n % 10 == 0 ? TENS[n / 10] : TENS[n / 10] + "-" + UNITS[n % 10];
    }

    private static String capitalize(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
