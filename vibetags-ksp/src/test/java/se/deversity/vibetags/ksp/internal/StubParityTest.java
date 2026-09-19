package se.deversity.vibetags.ksp.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
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
     * switches front ends sees an empty diff. The one exception is {@code .vibetags-locks}, whose
     * {@code file}/{@code startLine}/{@code endLine} under kapt point into kapt's generated stubs;
     * KSP has no stub and no Tree API, so it omits them, and the recording has them stripped.
     */
    @Test
    void generatedFilesAreByteIdenticalToKapt() throws IOException {
        assertEquals(recorded("CLAUDE.md"), Files.readString(root.resolve("CLAUDE.md")), "CLAUDE.md");
        assertEquals(recorded("llms-full.txt"), Files.readString(root.resolve("llms-full.txt")), "llms-full.txt");
        assertEquals(recorded("vibetags-locks.jsonl"), Files.readString(root.resolve(".vibetags-locks")),
            ".vibetags-locks");
        Set<String> kaptRules = new TreeSet<>(List.of(RULES));
        Set<String> kspRules = new TreeSet<>();
        try (Stream<Path> rules = Files.list(root.resolve(".claude/rules"))) {
            rules.forEach(rule -> kspRules.add(rule.getFileName().toString()));
        }
        assertEquals(kaptRules, kspRules, "the set of granular rule files");
        for (String rule : RULES) {
            assertEquals(recorded("rules/" + rule), Files.readString(root.resolve(".claude/rules/" + rule)), rule);
        }
    }

    /** The granular rule files kapt wrote, recorded under {@code kapt-output/rules}. */
    private static final String[] RULES = {
        "com-fx-Base.md", "com-fx-Box.md", "com-fx-Color.md", "com-fx-Marker.md",
        "com-fx-Plain-Companion.md", "com-fx-Plain-Inner.md", "com-fx-Plain-Nested.md", "com-fx-Plain.md",
        "com-fx-Point.md", "com-fx-Result2-Ok.md", "com-fx-Result2.md", "com-fx-Shape-Companion.md",
        "com-fx-Shape-DefaultImpls.md", "com-fx-Shape.md", "com-fx-Singleton.md", "com-fx-TypesFacade.md",
        "com-fx-nested-Holder.md", "com-fx-nested-OtherKt.md",
        "com-fx-vc-Account.md", "com-fx-vc-Money.md", "com-fx-vc-ValueClassesKt.md"};

    private static String recorded(String name) throws IOException {
        try (InputStream in = StubParityTest.class.getResourceAsStream("/kapt-parity/kapt-output/" + name)) {
            assertTrue(in != null, () -> "missing recording: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
