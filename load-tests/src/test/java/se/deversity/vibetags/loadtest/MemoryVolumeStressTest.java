package se.deversity.vibetags.loadtest;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.deversity.vibetags.processor.VibeTagsLogger;

import javax.tools.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Memory-volume stress test for {@code AIGuardrailProcessor}.
 *
 * <p>Companion to {@link AnnotationVolumeStressTest} — that test measures wall-clock
 * cost; this one measures heap footprint. The README calls allocation rate / peak heap
 * out as a first-class metric ("a processor that allocates O(N²) strings can OOM a
 * multi-module Maven build long before it errors") but until now it was only reachable
 * via JMH {@code -prof gc}. This test makes it part of the standard JUnit sweep.
 *
 * <h2>What it measures</h2>
 * <ul>
 *   <li><b>processorAlloc</b> – bytes allocated on the compile thread when the VibeTags
 *       processor is active, via {@code com.sun.management.ThreadMXBean}.</li>
 *   <li><b>baselineAlloc</b>  – same measurement with {@code -proc:none}.</li>
 *   <li><b>overheadAlloc</b>  – {@code processorAlloc - baselineAlloc} — bytes attributable
 *       to the processor itself (this is the number to track for regression).</li>
 *   <li><b>peakHeap</b>       – peak heap usage observed during the processor run, via
 *       {@code MemoryPoolMXBean.getPeakUsage()} after {@code resetPeakUsage()}.</li>
 * </ul>
 *
 * <h2>Why two metrics</h2>
 * Allocated-bytes is deterministic across JVM runs and isolates what the processor
 * itself produced. Peak-heap is noisier (depends on GC timing and heap sizing) but is
 * the number that actually decides whether a downstream multi-module build OOMs.
 *
 * <h2>How to run</h2>
 * <pre>
 *   cd load-tests
 *   mvn test -Dtest=MemoryVolumeStressTest
 * </pre>
 *
 * Results are written to {@code target/memory-results-&lt;timestamp&gt;.txt}.
 */
class MemoryVolumeStressTest {

    private static final Path RESULTS_FILE = Path.of("target",
            "memory-results-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt");

    private static final String PROCESSOR_CLASS = "se.deversity.vibetags.processor.AIGuardrailProcessor";

    private static final String[] OPT_IN_FILES = {
            ".cursorrules", "CLAUDE.md", ".aiexclude", "AGENTS.md",
            "gemini_instructions.md", "QWEN.md"
    };

    /**
     * The {@code com.sun.management} extension exposes per-thread allocated bytes.
     * It is available on HotSpot/OpenJDK; the test is skipped on JVMs that do not
     * implement it (e.g. some embedded JVMs).
     */
    private static ThreadMXBean threadBean;

    @BeforeAll
    static void prepareResultsFile() throws IOException {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof ThreadMXBean,
                "com.sun.management.ThreadMXBean not available on this JVM — skipping memory sweep.");
        threadBean = (ThreadMXBean) platformBean;
        assumeTrue(threadBean.isThreadAllocatedMemorySupported(),
                "Per-thread allocation tracking not supported on this JVM.");
        if (!threadBean.isThreadAllocatedMemoryEnabled()) {
            threadBean.setThreadAllocatedMemoryEnabled(true);
        }

        Files.createDirectories(RESULTS_FILE.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            pw.println("VibeTags Memory-Volume Stress Test Results");
            pw.println("=".repeat(86));
            pw.printf("%-10s %-20s %-20s %-20s %-14s%n",
                    "Classes", "ProcessorAlloc(KB)", "BaselineAlloc(KB)", "OverheadAlloc(KB)", "PeakHeap(MB)");
            pw.println("-".repeat(86));
        }
    }

    @ParameterizedTest(name = "N={0} classes")
    @ValueSource(ints = {10, 100, 500, 1000, 5000, 10_000})
    void memoryStress_nAnnotatedClasses(int n, @TempDir Path tempDir) throws Exception {
        int maxClasses = Integer.parseInt(System.getProperty("stress.max.classes", String.valueOf(Integer.MAX_VALUE)));
        assumeTrue(n <= maxClasses, "Skipping N=" + n + " (stress.max.classes=" + maxClasses + ")");

        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve(".codex").resolve("rules"));
        Files.createDirectories(projectRoot.resolve(".github"));
        Files.createDirectories(projectRoot.resolve(".qwen").resolve("commands"));
        for (String f : OPT_IN_FILES) {
            Files.createFile(projectRoot.resolve(f));
        }

        List<String[]> sources = SyntheticClassGenerator.generate(n);

        // Run the baseline first so the JIT has warmed up the shared compile path before
        // we attribute allocation cost to the processor. Otherwise the first sample carries
        // a one-off class-loading tail that inflates whichever side runs first.
        long baselineAlloc = compileAndMeasureAllocations(sources, projectRoot, /*procNone=*/true, tempDir);

        // Reset heap-pool peak counters right before the run we care about so the
        // peak-heap reading reflects only the processor compile, not earlier work.
        resetHeapPeaks();

        long processorAlloc = compileAndMeasureAllocations(sources, projectRoot, /*procNone=*/false, tempDir);
        long peakHeapBytes = readHeapPeak();

        long overheadAlloc = processorAlloc - baselineAlloc;

        // Sanity: processor-on must have allocated something measurable.
        assertTrue(processorAlloc > 0, "Processor compile should allocate > 0 bytes (N=" + n + ")");

        String line = String.format("%-10d %-20d %-20d %-20d %-14d",
                n,
                processorAlloc / 1024,
                baselineAlloc / 1024,
                overheadAlloc / 1024,
                peakHeapBytes / (1024 * 1024));
        System.out.println(line);
        appendResult(line);
    }

    /**
     * Releases the file appender held by the processor's {@code vibetags.log} so that
     * JUnit's {@code @TempDir} cleanup can delete the project root on Windows. On Linux
     * the lock is advisory and cleanup succeeds anyway, but Windows enforces it.
     */
    @AfterEach
    void releaseProcessorLogHandle() {
        VibeTagsLogger.shutdown();
    }

    private long compileAndMeasureAllocations(List<String[]> sources, Path projectRoot,
                                              boolean procNone, Path tempDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "javax.tools.JavaCompiler not available — run tests with a JDK, not a JRE.");
        }

        Path classesDir = tempDir.resolve(procNone ? "classes-baseline" : "classes-proc");
        Files.createDirectories(classesDir);

        List<JavaFileObject> sourceFiles = sources.stream()
                .map(pair -> makeSource(pair[0], pair[1]))
                .collect(Collectors.toList());

        List<String> options = new ArrayList<>(List.of(
                "-source", "17",
                "-target", "17",
                "-d", classesDir.toString(),
                "-classpath", System.getProperty("java.class.path")
        ));

        if (procNone) {
            options.add("-proc:none");
        } else {
            options.add("-processor");
            options.add(PROCESSOR_CLASS);
            options.add("-Avibetags.root=" + projectRoot.toAbsolutePath());
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        // Settle the heap before sampling so prior tasks' garbage is not counted toward
        // this run. System.gc() is advisory but on HotSpot it is honoured for explicit calls
        // unless -XX:+DisableExplicitGC is set, which is not the case in the test JVM.
        System.gc();

        long tid = Thread.currentThread().getId();
        long allocBefore = threadBean.getThreadAllocatedBytes(tid);

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fm,
                    diagnostics,
                    options,
                    null,
                    sourceFiles
            );
            task.call();
        }

        long allocAfter = threadBean.getThreadAllocatedBytes(tid);
        return allocAfter - allocBefore;
    }

    /** Resets peak-usage counters on every heap pool so the next read reflects only the next run. */
    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /** Sums {@code peakUsage().getUsed()} across all heap pools as a coarse "peak heap" figure. */
    private static long readHeapPeak() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                total += pool.getPeakUsage().getUsed();
            }
        }
        return total;
    }

    private static JavaFileObject makeSource(String simpleName, String source) {
        URI uri = URI.create("string:///com/example/generated/" + simpleName + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    private static synchronized void appendResult(String line) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE,
                StandardCharsets.UTF_8, StandardOpenOption.APPEND))) {
            pw.println(line);
        }
    }
}
