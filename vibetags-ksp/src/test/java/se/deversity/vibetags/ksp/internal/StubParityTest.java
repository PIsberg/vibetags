package se.deversity.vibetags.ksp.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permanent answer to "does a project that moves from kapt to KSP keep its element paths?".
 *
 * <p>{@code kapt-parity/src} is compiled here through KSP, and every annotated element's path and
 * kind is compared with {@code kapt-parity/expected-paths.tsv}, which was recorded from a real
 * kapt build of the same sources. The fixture covers each Kotlin shape whose stub differs from the
 * source: facades, companions, {@code @JvmStatic}, {@code @JvmOverloads}, {@code DefaultImpls},
 * suspend functions, use-site targets, variance, every primitive and array, and each value-class
 * shape kapt keeps or drops (constructors, accessors, members, receivers, {@code @JvmName}).
 *
 * <p>A difference here is a path change for every consumer who switches front ends: a
 * {@code .vibetags-locks} entry that moves, a granular rule file that is deleted and recreated
 * under a new name. When the list must change (a new kapt version stubs something differently),
 * re-record it from kapt rather than editing it to match.
 */
class StubParityTest {

    private static final Pattern LOCKED = Pattern.compile(
        "\\{\"type\":\"locked\",\"element\":\"([^\"]+)\",\"kind\":\"([A-Z_]+)\"");
    /** The position fields of a {@code .vibetags-locks} entry, stripped to compare with kapt's. */
    private static final Pattern POSITION = Pattern.compile("\"file\":\"[^\"]*\",\"startLine\":\\d+,\"endLine\":\\d+,");
    private static final Pattern LOCATED = Pattern.compile(
        "\"element\":\"([^\"]+)\",\"kind\":\"[A-Z_]+\",\"file\":\"([^\"]+)\",\"startLine\":(\\d+),\"endLine\":(\\d+)");
    private static final Pattern PARAMETER = Pattern.compile("com\\.fx[^ `\"]*#[A-Za-z0-9_$]+");

    private static Path root;
    private static KspHarness.Result result;

    @BeforeAll
    static void compileFixtureThroughKsp(@TempDir Path dir) throws IOException {
        root = dir;
        Files.writeString(root.resolve("CLAUDE.md"), "");
        Files.writeString(root.resolve("llms-full.txt"), "");
        Files.writeString(root.resolve(".vibetags-locks"), "");
        Files.createDirectories(root.resolve(".claude/rules"));
        // kapt's build had one, so the module resolves to the root there; same here.
        Files.writeString(root.resolve("build.gradle.kts"), "");
        Path sources = KspHarness.copySources("kapt-parity/src", root.resolve("src"));
        // kapt's build ran as Gradle project "kapt-gt"; internal functions' JVM names embed it.
        result = new KspHarness(sources, root).moduleName("kapt-gt").run();
    }

    @Test
    void kspRunsCleanly() {
        assertEquals("OK", result.exitCode(), () -> "errors: " + result.errors());
        assertTrue(result.errors().isEmpty(), () -> "errors: " + result.errors());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("failed and was skipped")),
            () -> "the adapter failed: " + result.warnings());
    }

    @Test
    void everyElementPathAndKindMatchesKapt() throws IOException {
        Set<String> expected = expected();
        Set<String> actual = new TreeSet<>();
        Matcher locked = LOCKED.matcher(Files.readString(root.resolve(".vibetags-locks")));
        while (locked.find()) {
            actual.add(locked.group(2) + "\t" + locked.group(1));
        }
        for (String path : parameterPaths()) {
            actual.add("PARAMETER\t" + path);
        }
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        assertTrue(missing.isEmpty() && extra.isEmpty(),
            () -> "KSP and kapt disagree.\n  kapt only (" + missing.size() + "):\n    "
                + String.join("\n    ", missing) + "\n  KSP only (" + extra.size() + "):\n    "
                + String.join("\n    ", extra));
    }

    /**
     * Stronger than the paths: the generated files themselves, byte for byte, against what kapt
     * wrote for the same sources. This pins element order as well as identity, so a project that
     * switches front ends sees an empty diff. The one exception is the position fields of
     * {@code .vibetags-locks} ({@code file}, {@code startLine}, {@code endLine}): kapt's point into
     * its generated stubs and KSP's into the {@code .kt} sources (#757), so both sides are compared
     * with them stripped, and {@link #kspLocksPointAtTheKotlinSource} checks KSP's own.
     */
    @Test
    void generatedFilesAreByteIdenticalToKapt() throws IOException {
        assertEquals(recorded("CLAUDE.md"), Files.readString(root.resolve("CLAUDE.md")), "CLAUDE.md");
        assertEquals(recorded("llms-full.txt"), Files.readString(root.resolve("llms-full.txt")), "llms-full.txt");
        assertEquals(recorded("vibetags-locks.jsonl"),
            POSITION.matcher(Files.readString(root.resolve(".vibetags-locks"))).replaceAll(""),
            ".vibetags-locks");
        Set<String> kaptRules = recordedRules();
        assertTrue(kaptRules.size() > 20, () -> "the recording lost its rule files: " + kaptRules);
        Set<String> kspRules = new TreeSet<>();
        try (Stream<Path> rules = Files.list(root.resolve(".claude/rules"))) {
            rules.forEach(rule -> kspRules.add(rule.getFileName().toString()));
        }
        assertEquals(kaptRules, kspRules, "the set of granular rule files");
        for (String rule : kaptRules) {
            assertEquals(recorded("rules/" + rule), Files.readString(root.resolve(".claude/rules/" + rule)), rule);
        }
    }

    /** The granular rule files kapt wrote, as recorded under {@code kapt-output/rules}. */
    private static Set<String> recordedRules() throws IOException {
        try (Stream<Path> rules = Files.list(Path.of(URI.create(
                StubParityTest.class.getResource("/kapt-parity/kapt-output/rules").toString())))) {
            Set<String> names = new TreeSet<>();
            rules.forEach(rule -> names.add(rule.getFileName().toString()));
            return names;
        }
    }

    private static String recorded(String name) throws IOException {
        try (InputStream in = StubParityTest.class.getResourceAsStream("/kapt-parity/kapt-output/" + name)) {
            assertTrue(in != null, () -> "missing recording: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Under KSP, {@code .vibetags-locks} points at the Kotlin source (#757): every locked element has
     * a {@code .kt} file relative to the root and a range, and a sample of ranges is pinned to the
     * fixture's own lines, including where a declaration's annotations start it, where a class body
     * and an enum entry end, and that a synthetic copy points at the declaration it copies.
     */
    @Test
    void kspLocksPointAtTheKotlinSource() throws IOException {
        java.util.Map<String, String> ranges = new java.util.HashMap<>();
        Matcher m = LOCATED.matcher(Files.readString(root.resolve(".vibetags-locks")));
        while (m.find()) {
            int start = Integer.parseInt(m.group(3));
            int end = Integer.parseInt(m.group(4));
            assertTrue(m.group(2).startsWith("src/com/fx/") && m.group(2).endsWith(".kt"), m.group());
            assertTrue(start >= 1 && start <= end, m.group());
            ranges.put(m.group(1), m.group(2) + ":" + start + "-" + end);
        }
        Set<String> locked = new TreeSet<>();
        Matcher all = LOCKED.matcher(POSITION.matcher(Files.readString(root.resolve(".vibetags-locks"))).replaceAll(""));
        while (all.find()) {
            locked.add(all.group(1));
        }
        Set<String> unlocated = new TreeSet<>(locked);
        unlocated.removeAll(ranges.keySet());
        assertTrue(unlocated.isEmpty(), () -> "locked with no position: " + unlocated);

        String types = "src/com/fx/Types.kt:";
        assertEquals(types + "17-70", ranges.get("com.fx.Plain"), "annotation line to closing brace");
        assertEquals(types + "33-33", ranges.get("com.fx.Plain.plain()"));
        assertEquals(types + "40-40", ranges.get("com.fx.Plain.defs(int)"), "an @JvmOverloads copy");
        assertEquals(types + "20-20", ranges.get("com.fx.Plain.tag"), "a constructor property");
        assertEquals(types + "85-85", ranges.get("com.fx.Color.RED"), "an enum entry");
        assertEquals(types + "89-95", ranges.get("com.fx.Shape"));
        assertEquals(types + "92-92", ranges.get("com.fx.Shape.DefaultImpls.describe(com.fx.Shape,java.lang.String)"),
            "a DefaultImpls copy points at the interface member");
        assertEquals(types + "13-13", ranges.get("com.fx.TypesFacade.TOP_CONST"));
    }

    private static Set<String> parameterPaths() throws IOException {
        List<Path> files = new ArrayList<>(List.of(root.resolve("CLAUDE.md"), root.resolve("llms-full.txt")));
        try (Stream<Path> rules = Files.list(root.resolve(".claude/rules"))) {
            files.addAll(rules.toList());
        }
        Set<String> paths = new TreeSet<>();
        for (Path file : files) {
            Matcher m = PARAMETER.matcher(Files.readString(file));
            while (m.find()) {
                paths.add(m.group());
            }
        }
        return paths;
    }

    private static Set<String> expected() throws IOException {
        Set<String> expected = new TreeSet<>();
        try (InputStream in = StubParityTest.class.getResourceAsStream("/kapt-parity/expected-paths.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    expected.add(line.strip());
                }
            }
        }
        return expected;
    }
}
