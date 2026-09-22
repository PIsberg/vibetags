package se.deversity.vibetags.loadtest;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.deversity.vibetags.processor.VibeTagsLogger;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the build a developer actually runs most often costs: the one where nothing changed.
 *
 * <h2>Why the volume sweeps cannot see this</h2>
 *
 * <p>{@link AnnotationVolumeStressTest} and {@link MemoryVolumeStressTest} give every N its own
 * {@code @TempDir}, so every compile they measure is the first compile that project root has ever
 * seen. {@code .vibetags-cache} is empty, the build fingerprint has nothing to compare against,
 * and both short-circuits are structurally unreachable. Every number in
 * {@code load-tests/results/} is therefore a cold build, while the build a developer waits for
 * twenty times an afternoon is a warm one.
 *
 * <p>{@code WriteCacheHitBenchmark} measures the cache at the level of a single
 * {@code writeFileIfChanged} call, which is the right granularity for proving the data structure
 * and the wrong one for answering "what does my rebuild cost". This measures the same compile
 * twice into the same project root, which is what Maven does on the second {@code mvn compile}.
 *
 * <h2>What it measures</h2>
 * <ul>
 *   <li><b>cold</b> — allocation and wall-clock for the first compile into an empty project root.</li>
 *   <li><b>warm</b> — the same sources compiled again into the same root, with the cache and the
 *       fingerprint from the cold round on disk.</li>
 *   <li><b>saved</b> — {@code cold - warm} as a share of the cold round's processor-attributable
 *       allocation. This is the number the fingerprint short-circuit exists to produce.</li>
 * </ul>
 *
 * <p>The generated files are compared byte for byte across the two rounds. A short-circuit that
 * is fast because it stopped writing the right content would otherwise read as an improvement.
 *
 * <pre>
 *   cd load-tests
 *   mvn test -Dtest=IncrementalRebuildStressTest
 * </pre>
 *
 * Results are appended to {@code target/incremental-rebuild-<timestamp>.txt}.
 */
class IncrementalRebuildStressTest {

    private static final Path RESULTS_FILE = Path.of("target",
        "incremental-rebuild-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt");

    private static final String PROCESSOR_CLASS = "se.deversity.vibetags.processor.AIGuardrailProcessor";

    /** The same six opt-ins the volume sweeps use, so this table reads against those baselines. */
    private static final String[] OPT_IN_FILES = {
        ".cursorrules", "CLAUDE.md", ".aiexclude", "AGENTS.md", "gemini_instructions.md", "QWEN.md"
    };

    /**
     * The note {@code AIGuardrailProcessor} prints when the fingerprint short-circuit fires.
     *
     * <p>This, not a threshold on the saving, is the gate. The first draft of this test asserted
     * that the warm round skipped at least 25 % of the cold round's processor-attributable
     * allocation, on the assumption that skipping "content build and writes" would be most of the
     * cost. It is not: measured at 1.3.6 the saving is 9.0 % at N=100, 4.3 % at N=500 and 3.6 % at
     * N=1000, because the collector has already walked every annotated element in the round before
     * a fingerprint can be computed, and that walk is where the allocation is. A number that small
     * cannot carry a regression gate — it is close enough to the run-to-run floor that a threshold
     * either fails healthy builds or passes a broken short-circuit. The note is deterministic, so
     * it is asserted instead, and the saving is reported rather than gated.
     */
    private static final String SHORT_CIRCUIT_NOTE = "inputs unchanged since last run";

    private static ThreadMXBean threadBean;

    @BeforeAll
    static void prepare() throws IOException {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean,
            "com.sun.management.ThreadMXBean not available on this JVM — skipping.");
        threadBean = (ThreadMXBean) platformBean;
        assumeTrue(threadBean.isThreadAllocatedMemorySupported(),
            "Per-thread allocation tracking not supported on this JVM.");
        if (!threadBean.isThreadAllocatedMemoryEnabled()) {
            threadBean.setThreadAllocatedMemoryEnabled(true);
        }
        Files.createDirectories(RESULTS_FILE.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            pw.println("VibeTags Incremental-Rebuild Stress Test Results");
            pw.println("=".repeat(96));
            pw.printf("%-10s %-16s %-16s %-12s %-12s %-14s%n",
                "Classes", "ColdAlloc(KB)", "WarmAlloc(KB)", "Cold(ms)", "Warm(ms)", "Saved(%)");
            pw.println("-".repeat(96));
        }
    }

    @AfterEach
    void releaseProcessorLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @ParameterizedTest(name = "N={0} classes")
    @ValueSource(ints = {100, 500, 1000})
    void theSecondBuildOfUnchangedSourcesCostsMuchLess(int n, @TempDir Path tempDir) throws Exception {
        assumeTrue(n <= maxClasses(), "Skipping N=" + n + " (stress.max.classes)");

        List<JavaFileObject> sources = SyntheticClassGenerator.generate(n).stream()
            .map(pair -> source(pair[0], pair[1]))
            .collect(Collectors.toList());

        // The first compile in a JVM carries a one-off class-loading tail bigger than the effect,
        // so it is spent in a project root nothing is read back out of.
        compile(sources, root(tempDir, "warmup"), tempDir, "classes-warmup");

        long baseline = compileWithoutProcessor(sources, tempDir);

        Path projectRoot = root(tempDir, "project");
        Measurement cold = compile(sources, projectRoot, tempDir, "classes-cold");
        Map<String, String> afterCold = generated(projectRoot);
        Measurement warm = compile(sources, projectRoot, tempDir, "classes-warm");
        Map<String, String> afterWarm = generated(projectRoot);

        long coldOverhead = cold.allocatedBytes - baseline;
        long warmOverhead = warm.allocatedBytes - baseline;
        double savedShare = coldOverhead <= 0 ? 0.0 : 100.0 * (coldOverhead - warmOverhead) / coldOverhead;

        String line = String.format(Locale.ROOT, "%-10d %-16d %-16d %-12d %-12d %-14.1f",
            n, coldOverhead / 1024, warmOverhead / 1024, cold.elapsedMillis, warm.elapsedMillis, savedShare);
        System.out.println(line);
        append(line);

        nothingMoved(afterCold, afterWarm, n);
        shortCircuitFired(cold, warm, n);
    }

    /**
     * The warm round must have taken the fingerprint short-circuit, and the cold round must not
     * have.
     *
     * <p>This is the gate, because it is the only part of this measurement that is deterministic.
     * Both halves matter: without the cold-round half, a fixture that reused a project root from a
     * previous case would short-circuit both rounds and report a saving of nothing at all, and the
     * test would pass.
     */
    private static void shortCircuitFired(Measurement cold, Measurement warm, int n) {
        assertTrue(!cold.shortCircuited(),
            "the first build into an empty project root reported \"" + SHORT_CIRCUIT_NOTE
                + "\" at N=" + n + ": this root was not cold, so there is no cold column here");
        assertTrue(warm.shortCircuited(),
            "the rebuild of unchanged sources did not report \"" + SHORT_CIRCUIT_NOTE + "\" at N="
                + n + ", so the build fingerprint no longer matches across two identical rounds and "
                + "every consumer rebuilds content it could have skipped. Processor said: "
                + warm.notes);
    }

    /**
     * The warm round must have produced exactly what the cold round did.
     *
     * <p>A short-circuit is only worth having while the file it skipped writing already holds the
     * right bytes. Without this comparison a fingerprint that matched when it should not have —
     * the failure mode {@code WriteCache}'s own guardrail calls a false positive — reads here as
     * the largest saving this test has ever recorded.
     */
    private static void nothingMoved(Map<String, String> cold, Map<String, String> warm, int n) {
        assertEquals(cold.keySet(), warm.keySet(),
            "the warm round changed which files exist at N=" + n);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : cold.entrySet()) {
            if (!entry.getValue().equals(warm.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        assertTrue(changed.isEmpty(),
            "the warm round rewrote " + changed + " with different content at N=" + n
                + "; a rebuild of unchanged sources must be byte-identical");
        assertTrue(cold.values().stream().anyMatch(content -> !content.isEmpty()),
            "the cold round wrote nothing at N=" + n + ", so this measured an inactive processor");
    }

    // -------------------------------------------------------------------------

    /** Bytes allocated, milliseconds elapsed, and what the processor said, for one compile. */
    private static final class Measurement {
        final long allocatedBytes;
        final long elapsedMillis;
        final String notes;

        Measurement(long allocatedBytes, long elapsedMillis, String notes) {
            this.allocatedBytes = allocatedBytes;
            this.elapsedMillis = elapsedMillis;
            this.notes = notes;
        }

        boolean shortCircuited() {
            return notes.contains(SHORT_CIRCUIT_NOTE);
        }
    }

    private static Path root(Path tempDir, String name) throws IOException {
        Path projectRoot = tempDir.resolve(name);
        Files.createDirectories(projectRoot);
        for (String f : OPT_IN_FILES) {
            Path target = projectRoot.resolve(f);
            if (!Files.exists(target)) {
                Files.createFile(target);
            }
        }
        return projectRoot;
    }

    private static Measurement compile(List<JavaFileObject> sources, Path projectRoot, Path tempDir,
                                       String classesDirName) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("javax.tools.JavaCompiler not available — run with a JDK, not a JRE.");
        }
        Path classesDir = tempDir.resolve(classesDirName);
        Files.createDirectories(classesDir);

        List<String> options = List.of(
            "-source", "17",
            "-target", "17",
            "-d", classesDir.toString(),
            "-classpath", System.getProperty("java.class.path"),
            "-processor", PROCESSOR_CLASS,
            "-Avibetags.root=" + projectRoot.toAbsolutePath());

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        System.gc();
        long tid = Thread.currentThread().getId();
        long before = threadBean.getThreadAllocatedBytes(tid);
        long start = System.currentTimeMillis();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            compiler.getTask(null, fm, diagnostics, options, null, sources).call();
        }
        long elapsed = System.currentTimeMillis() - start;
        String notes = diagnostics.getDiagnostics().stream()
            .map(d -> d.getMessage(Locale.ROOT))
            .collect(Collectors.joining("\n"));
        return new Measurement(threadBean.getThreadAllocatedBytes(tid) - before, elapsed, notes);
    }

    /** The same compile with no annotation processing: javac's own share, subtracted from both rounds. */
    private static long compileWithoutProcessor(List<JavaFileObject> sources, Path tempDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classesDir = tempDir.resolve("classes-baseline");
        Files.createDirectories(classesDir);
        List<String> options = List.of(
            "-source", "17",
            "-target", "17",
            "-d", classesDir.toString(),
            "-classpath", System.getProperty("java.class.path"),
            "-proc:none");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        System.gc();
        long tid = Thread.currentThread().getId();
        long before = threadBean.getThreadAllocatedBytes(tid);
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            compiler.getTask(null, fm, diagnostics, options, null, sources).call();
        }
        return threadBean.getThreadAllocatedBytes(tid) - before;
    }

    /** The generated opt-in files, by name, so the two rounds can be compared byte for byte. */
    private static Map<String, String> generated(Path projectRoot) throws IOException {
        Map<String, String> content = new LinkedHashMap<>();
        for (String f : OPT_IN_FILES) {
            Path p = projectRoot.resolve(f);
            content.put(f, Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "");
        }
        return content;
    }

    private static JavaFileObject source(String simpleName, String code) {
        URI uri = URI.create("string:///com/example/generated/" + simpleName + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
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

    private static void append(String line) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            pw.println(line);
        }
    }
}
