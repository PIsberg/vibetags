package se.deversity.vibetags.loadtest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What {@code TESTING.md} routing costs, and what it must not cost, at annotation volume.
 *
 * <h2>Why this is not part of {@link AnnotationVolumeStressTest}</h2>
 *
 * <p>That harness hands javac in-memory {@code JavaFileObject}s, which is why it is fast and why
 * every run in {@code load-tests/results/} is comparable with every other. It also means routing
 * can never engage in it: {@code ModuleRootResolver} resolves a module from the compilation unit's
 * source <em>file</em>, and a {@code string:///} URI has none, so no source set is ever named and
 * every round is treated as a main round. Measured on 2026-09-20 by opting that harness into
 * {@code TESTING.md} and giving it a {@code pom.xml}: the file stayed 0 bytes at N=10 and N=100
 * while {@code CLAUDE.md} took 4638 and 34458 bytes (issue #789).
 *
 * <p>So this test writes its sources to disk under {@code src/main/java} and {@code src/test/java}
 * and compiles from files. That adds file I/O per source, which is exactly why it is a separate
 * class: converting the in-memory harness in place would have silently invalidated every
 * historical baseline it has produced. The two series measure different things on purpose.
 *
 * <h2>What it measures</h2>
 * <ul>
 *   <li><b>bytes</b> - the headline, because it is deterministic. Routed, the always-loaded
 *       {@code CLAUDE.md} shrinks to its safety tier and the rest moves to {@code TESTING.md};
 *       unrouted, all of it is in {@code CLAUDE.md}. The saving is the first number, since the
 *       point of the feature is spending less of the agent's always-loaded context.</li>
 *   <li><b>ms</b> - the test round's compile time, routed against unrouted. Wall-clock on one
 *       run, so it is an order-of-magnitude reading, not a benchmark, and nothing asserts on it.</li>
 * </ul>
 *
 * <h2>What it guards</h2>
 *
 * <p>Routing moves guardrails between files, so the failure that matters is a guardrail that
 * lands in neither. Every {@code focus} the unrouted build wrote must still be readable in one of
 * the two files after routing. The e2e tests pin that for a handful of annotations; this pins it
 * for hundreds, where a merge that drops a region is how it would actually happen.
 *
 * <p>{@link #routingActuallyEngaged} is the other half, and the reason it is asserted rather than
 * assumed: the in-memory harness produced green runs in which the feature under measurement was
 * never switched on.
 */
class TestingMdRoutingStressTest {

    private static final Path RESULTS_FILE = Path.of("target",
            "testing-md-routing-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt");

    private static final String PROCESSOR_CLASS = "se.deversity.vibetags.processor.AIGuardrailProcessor";

    /** The always-loaded file guardrails are routed out of, and the file they are routed into. */
    private static final String ALWAYS_LOADED = "CLAUDE.md";
    private static final String ROUTED_TO = "TESTING.md";

    @BeforeAll
    static void prepareResultsFile() throws IOException {
        Files.createDirectories(RESULTS_FILE.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            pw.println("VibeTags TESTING.md Routing Stress Test Results");
            pw.println("=".repeat(96));
            pw.printf("%-8s %-8s %-16s %-16s %-16s %-12s %-12s%n",
                    "Main", "Test", "UnroutedCLAUDE", "RoutedCLAUDE", "RoutedTESTING",
                    "Unrouted(ms)", "Routed(ms)");
            pw.println("-".repeat(96));
        }
    }

    @ParameterizedTest(name = "{0} main + {0} test classes")
    @ValueSource(ints = {10, 100, 500})
    void routingMovesGuardrailsOutOfTheAlwaysLoadedFileWithoutLosingAny(int n, @TempDir Path tempDir)
            throws Exception {
        assumeTrue(n <= maxClasses(), "Skipping N=" + n + " (stress.max.classes)");

        Run unrouted = build(tempDir.resolve("unrouted"), n, false);
        Run routed = build(tempDir.resolve("routed"), n, true);

        routingActuallyEngaged(unrouted, routed, n);
        nothingWasLost(unrouted, routed, n);

        String line = String.format("%-8d %-8d %-16d %-16d %-16d %-12d %-12d",
                n, n, unrouted.alwaysLoadedBytes, routed.alwaysLoadedBytes, routed.routedBytes,
                unrouted.testRoundMillis, routed.testRoundMillis);
        System.out.println(line);
        long saved = unrouted.alwaysLoadedBytes - routed.alwaysLoadedBytes;
        System.out.printf("  always-loaded context saved: %d bytes (%.1f%%)%n",
                saved, 100.0 * saved / unrouted.alwaysLoadedBytes);
        appendResult(line);
    }

    /**
     * The routed build must have routed. Asserted, not assumed, because the cheapest way for this
     * whole test to go green while measuring nothing is for the round not to be classified as a
     * test round at all, which is precisely what the in-memory harness did.
     */
    private static void routingActuallyEngaged(Run unrouted, Run routed, int n) {
        assertTrue(routed.routedBytes > 0,
                ROUTED_TO + " is empty at N=" + n + ": the test round was not routed, so this run "
                        + "measured the unrouted path twice. Check that the sources were written under "
                        + "src/test/java and that the module root carries a build file.");
        assertTrue(routed.alwaysLoadedBytes < unrouted.alwaysLoadedBytes,
                "routing must take content out of " + ALWAYS_LOADED + ", but it grew from "
                        + unrouted.alwaysLoadedBytes + " to " + routed.alwaysLoadedBytes
                        + " bytes at N=" + n);
        assertFalse(unrouted.always.contains(ROUTED_TO),
                "the unrouted build must not point at a file that does not exist");
        assertTrue(routed.always.contains(ROUTED_TO),
                "the routed build's always-loaded file must point a reader at " + ROUTED_TO);
    }

    /**
     * Routing relocates guardrails; it never drops one. Checks every generated {@code focus}, so
     * a merge that loses a whole module region shows up as hundreds of missing strings rather than
     * as a byte count that merely looks plausible.
     */
    private static void nothingWasLost(Run unrouted, Run routed, int n) {
        String routedBoth = routed.always + "\n" + routed.testing;
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String focus = testFocus(i);
            if (unrouted.always.contains(focus) && !routedBoth.contains(focus)) {
                missing.add(focus);
            }
        }
        assertTrue(missing.isEmpty(),
                missing.size() + " of " + n + " test guardrails are in neither " + ALWAYS_LOADED
                        + " nor " + ROUTED_TO + " after routing. First few: "
                        + missing.subList(0, Math.min(5, missing.size())));

        // The main half is checked too, and against the stricter rule: routing must not move a
        // main guardrail anywhere, so each one stays in the always-loaded file. Without this, the
        // routed pair coming out smaller than the unrouted file reads as a saving when it could
        // equally be the main round having lost a region to the merge.
        List<String> movedOrLost = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String focus = mainFocus(i);
            if (unrouted.always.contains(focus) && !routed.always.contains(focus)) {
                movedOrLost.add(focus);
            }
        }
        assertTrue(movedOrLost.isEmpty(),
                movedOrLost.size() + " of " + n + " main guardrails left " + ALWAYS_LOADED
                        + "; only test code is routed. First few: "
                        + movedOrLost.subList(0, Math.min(5, movedOrLost.size())));
    }

    // -------------------------------------------------------------------------

    /** One project built end to end: main round then test round, as Maven does it. */
    private static final class Run {
        final String always;
        final String testing;
        final long alwaysLoadedBytes;
        final long routedBytes;
        final long testRoundMillis;

        Run(String always, String testing, long testRoundMillis) {
            this.always = always;
            this.testing = testing;
            this.alwaysLoadedBytes = always.length();
            this.routedBytes = testing.length();
            this.testRoundMillis = testRoundMillis;
        }
    }

    private Run build(Path projectRoot, int n, boolean optIntoTestingMd) throws IOException {
        Files.createDirectories(projectRoot);
        // The module root: ModuleRootResolver walks up from each source file looking for one of
        // these, and without it no source set is ever named.
        Files.writeString(projectRoot.resolve("pom.xml"),
                "<project><artifactId>loadtest</artifactId></project>", StandardCharsets.UTF_8);
        Files.createFile(projectRoot.resolve(ALWAYS_LOADED));
        if (optIntoTestingMd) {
            Files.createFile(projectRoot.resolve(ROUTED_TO));
        }

        List<Path> main = writeSources(projectRoot, "main", n, false);
        List<Path> test = writeSources(projectRoot, "test", n, true);

        compile(projectRoot, main, "classes-main");
        long testRoundMillis = compile(projectRoot, test, "classes-test");

        return new Run(read(projectRoot.resolve(ALWAYS_LOADED)), read(projectRoot.resolve(ROUTED_TO)),
                testRoundMillis);
    }

    /**
     * Writes {@code n} generated classes to {@code src/<sourceSet>/java/...} on disk. The path is
     * the whole point: it is what the processor reads the source set out of.
     */
    private static List<Path> writeSources(Path projectRoot, String sourceSet, int n, boolean isTest)
            throws IOException {
        Path pkgDir = projectRoot.resolve("src").resolve(sourceSet).resolve("java")
                .resolve("com").resolve("example").resolve("generated").resolve(sourceSet);
        Files.createDirectories(pkgDir);
        List<Path> written = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String type = (isTest ? "SyntheticTest" : "SyntheticMain") + i;
            Path file = pkgDir.resolve(type + ".java");
            Files.writeString(file, source(sourceSet, type, i, isTest), StandardCharsets.UTF_8);
            written.add(file);
        }
        return written;
    }

    /**
     * A class carrying one routed annotation and, on every third, one that stays inline. Written
     * here rather than taken from {@link SyntheticClassGenerator} because this test asserts on the
     * exact focus strings and on which side of the safety split each annotation falls, and a
     * change to the shared generator's mix would otherwise silently change what is measured.
     */
    private static String source(String sourceSet, String type, int i, boolean isTest) {
        StringBuilder sb = new StringBuilder(384);
        sb.append("package com.example.generated.").append(sourceSet).append(";\n\n")
                .append("import se.deversity.vibetags.annotations.AIContext;\n")
                .append("import se.deversity.vibetags.annotations.AILocked;\n\n");
        if (i % 3 == 0) {
            // A safety annotation: stays in the always-loaded file even in a routed test round.
            sb.append("@AILocked(reason = \"Synthetic ").append(sourceSet)
                    .append(" lock #").append(i).append("\")\n");
        }
        sb.append("@AIContext(focus = \"")
                .append(isTest ? testFocus(i) : mainFocus(i))
                .append("\")\n")
                .append("public class ").append(type).append(" {\n}\n");
        return sb.toString();
    }

    /** The routed half: a non-safety guardrail on a class in a test source set. */
    private static String testFocus(int i) {
        return "Synthetic test focus #" + i;
    }

    /** The half that must not move: the same annotation on a class in the main source set. */
    private static String mainFocus(int i) {
        return "Synthetic main focus #" + i;
    }

    /** Compiles real files on disk, and returns wall-clock milliseconds. */
    private long compile(Path projectRoot, List<Path> sources, String classesDirName) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "javax.tools.JavaCompiler not available - run tests with a JDK, not a JRE.");
        }
        Path classesDir = projectRoot.resolve("target").resolve(classesDirName);
        Files.createDirectories(classesDir);

        List<String> options = List.of(
                "-source", "17",
                "-target", "17",
                "-d", classesDir.toString(),
                "-classpath", System.getProperty("java.class.path"),
                "-processor", PROCESSOR_CLASS,
                "-Avibetags.root=" + projectRoot.toAbsolutePath());

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        long start = System.currentTimeMillis();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            compiler.getTask(null, fm, diagnostics, options, null,
                    fm.getJavaFileObjectsFromPaths(sources)).call();
        }
        return System.currentTimeMillis() - start;
    }

    private static String read(Path p) throws IOException {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    private static int maxClasses() {
        String prop = System.getProperty("stress.max.classes");
        if (prop == null || prop.isBlank() || prop.contains("${")) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(prop.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static synchronized void appendResult(String line) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE,
                StandardCharsets.UTF_8, StandardOpenOption.APPEND))) {
            pw.println(line);
        }
    }
}
