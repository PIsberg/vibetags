package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Keeps the README's annotation reference table true.
 *
 * <p>The table states two things a reader relies on before writing an annotation: what it can be
 * attached to, and whether its guardrail stays in the always-loaded aggregate or moves to a scoped
 * rule file. Both are properties of the code, so both are read from the code here rather than
 * trusted to whoever last edited the Markdown.
 *
 * <p>The tier column is the one worth deriving rather than listing. "Safety" is not a label on the
 * annotation — it is whichever buckets {@code ClaudeRenderer}'s indexed variant chooses to keep
 * inline once the aggregate collapses. So the set is read back from that method rather than typed
 * here: change which sections it emits and this fails, which is the only thing keeping the README
 * honest about a claim readers act on.
 */
class AnnotationReferenceTest {

    /** {@code vibetags/} is the surefire working directory; its parent is the repo root. */
    private static final Path REPO_ROOT = Paths.get("").toAbsolutePath().getParent();

    /** `@AIName` | can annotate | required attributes | tier */
    private static final Pattern ROW = Pattern.compile(
        "^\\|\\s*`@(AI\\w+)`\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*$",
        Pattern.MULTILINE);

    private static final Map<ElementType, String> TARGET_LABELS = Map.of(
        ElementType.TYPE, "type",
        ElementType.METHOD, "method",
        ElementType.FIELD, "field",
        ElementType.PARAMETER, "parameter",
        ElementType.CONSTRUCTOR, "constructor",
        ElementType.ANNOTATION_TYPE, "annotation",
        ElementType.PACKAGE, "package");

    @Test
    void everyAnnotationHasARowAndNoRowIsInvented() throws IOException {
        Map<String, String[]> rows = readTable();
        Set<String> declared = annotationSimpleNames();
        assumeTrue(!declared.isEmpty(), "annotations module not reachable; skipping");

        List<String> missing = declared.stream().filter(a -> !rows.containsKey(a)).sorted().toList();
        List<String> extra = rows.keySet().stream().filter(a -> !declared.contains(a)).sorted().toList();
        assertTrue(missing.isEmpty(), "README annotation reference is missing: " + missing);
        assertTrue(extra.isEmpty(), "README annotation reference lists annotations that do not exist: " + extra);
    }

    @Test
    void everyRowsTargetsMatchTheAnnotationsOwnTarget() throws IOException {
        Map<String, String[]> rows = readTable();
        assumeTrue(!rows.isEmpty(), "README table not reachable; skipping");

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String[]> row : rows.entrySet()) {
            Class<?> type = load(row.getKey());
            if (type == null) {
                continue;
            }
            Target target = type.getAnnotation(Target.class);
            String actual = target == null ? "any" : Stream.of(target.value())
                .map(t -> TARGET_LABELS.getOrDefault(t, t.name().toLowerCase(java.util.Locale.ROOT)))
                .reduce((a, b) -> a + ", " + b).orElse("any");
            if (!actual.equals(row.getValue()[0])) {
                problems.add("@" + row.getKey() + ": README says \"" + row.getValue()[0]
                    + "\", @Target says \"" + actual + "\"");
            }
        }
        assertTrue(problems.isEmpty(),
            "README annotation reference disagrees with the annotations' own @Target — someone "
                + "would write an annotation where it does not compile:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void everyRowsRequiredAttributesMatchTheAnnotationsMembers() throws IOException {
        Map<String, String[]> rows = readTable();
        assumeTrue(!rows.isEmpty(), "README table not reachable; skipping");

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String[]> row : rows.entrySet()) {
            Class<?> type = load(row.getKey());
            if (type == null) {
                continue;
            }
            // A member with no default has to be supplied at the use site or the code does not
            // compile, which is exactly what the column claims.
            String actual = Stream.of(type.getDeclaredMethods())
                .filter(m -> m.getDefaultValue() == null)
                .map(m -> "`" + m.getName() + "`")
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse(EM_DASH);
            String claimed = row.getValue()[1];
            if (!sortedAttributes(claimed).equals(sortedAttributes(actual))) {
                problems.add("@" + row.getKey() + ": README says " + claimed + ", the annotation "
                    + "requires " + actual);
            }
        }
        assertTrue(problems.isEmpty(),
            "README annotation reference disagrees with the annotations' own members — a reader "
                + "following it would write code that does not compile, or leave out an attribute "
                + "the table said was optional:\n  " + String.join("\n  ", problems));
    }

    @Test
    void theSafetyColumnMatchesWhatTheIndexedRendererKeepsInline() throws IOException {
        Map<String, String[]> rows = readTable();
        assumeTrue(!rows.isEmpty(), "README table not reachable; skipping");

        Set<String> claimedSafety = new LinkedHashSet<>();
        rows.forEach((name, cells) -> {
            if (cells[2].contains("safety")) {
                claimedSafety.add(name);
            }
        });

        Set<String> actualSafety = safetyTierFromRenderer();
        assumeTrue(!actualSafety.isEmpty(),
            "could not read the indexed renderer; skipping rather than asserting nothing");

        assertEquals(actualSafety, claimedSafety,
            "The README marks a different set as safety tier than the indexed renderer keeps "
                + "inline. Whichever moved, the other has to follow — a guardrail readers believe "
                + "is always loaded but is not is worse than no guardrail.");
    }

    // -----------------------------------------------------------------------

    /**
     * The safety tier as the renderer defines it, read from {@code ClaudeRenderer.renderIndexed}.
     *
     * <p>"Safety" is not a property of an annotation — it is whichever buckets that method still
     * emits once the aggregate collapses to an index. Reading them from the method means adding or
     * removing one there fails this test, instead of leaving the README describing a tier split
     * that no longer happens.
     */
    private static Set<String> safetyTierFromRenderer() throws IOException {
        Path renderer = REPO_ROOT.resolve("vibetags/src/main/java/se/deversity/vibetags/processor"
            + "/internal/content/platforms/ClaudeRenderer.java");
        if (!Files.isRegularFile(renderer)) {
            return Set.of();
        }
        String src = Files.readString(renderer, StandardCharsets.UTF_8);
        int start = src.indexOf("private static String renderIndexed(");
        if (start < 0) {
            return Set.of();
        }
        int end = src.indexOf("\n    }", start);
        String body = src.substring(start, end < 0 ? src.length() : end);

        Set<String> found = new LinkedHashSet<>();
        // The locked bucket is emitted inline rather than through an append*Section helper.
        if (body.contains("model.locked()")) {
            found.add("AILocked");
        }
        Matcher sections = Pattern.compile("append(\\w+)Section\\(").matcher(body);
        while (sections.find()) {
            String bucket = sections.group(1);
            // appendPrivacySection -> AIPrivacy, appendCoreSection -> AICore, and so on. The
            // renderer's helpers are named after the annotation they render.
            found.add("AI" + bucket);
        }
        return found;
    }

    /** The generator writes this when an annotation requires nothing. */
    private static final String EM_DASH = "—";

    /** Order-insensitive compare; the table lists attributes alphabetically, reflection does not. */
    private static List<String> sortedAttributes(String cell) {
        String trimmed = cell.strip();
        if (trimmed.isEmpty() || EM_DASH.equals(trimmed) || "-".equals(trimmed)) {
            return List.of();
        }
        return Stream.of(trimmed.split(",")).map(String::strip).sorted().toList();
    }

    /** annotation simple name → {targets, requiredAttributes, tier}. */
    private static Map<String, String[]> readTable() throws IOException {
        Path readme = REPO_ROOT.resolve("README.md");
        if (!Files.isRegularFile(readme)) {
            return Map.of();
        }
        String md = Files.readString(readme, StandardCharsets.UTF_8);
        int start = md.indexOf("### Annotation reference");
        if (start < 0) {
            return Map.of();
        }
        Matcher m = ROW.matcher(md.substring(start));
        Map<String, String[]> rows = new LinkedHashMap<>();
        while (m.find()) {
            rows.put(m.group(1), new String[]{m.group(2), m.group(3), m.group(4)});
        }
        return rows;
    }

    private static Set<String> annotationSimpleNames() throws IOException {
        Path dir = REPO_ROOT.resolve(
            "vibetags-annotations/src/main/java/se/deversity/vibetags/annotations");
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString().replace(".java", "");
                if (name.startsWith("AI")
                    && Files.readString(f, StandardCharsets.UTF_8).contains("public @interface")) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static Class<?> load(String simpleName) {
        try {
            return Class.forName("se.deversity.vibetags.annotations." + simpleName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
