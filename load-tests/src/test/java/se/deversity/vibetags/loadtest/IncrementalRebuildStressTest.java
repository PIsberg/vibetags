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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 *   <li><b>coldOwn / warmOwn / savedOwn</b> — the same three against a {@link NoOpProcessor}
 *       control rather than {@code -proc:none}, so javac's own annotation-processing subsystem is
 *       out of the denominator. See below.</li>
 *   <li><b>hashOwn / headroom</b> — what a {@link SourceHashProcessor} allocates over the same
 *       no-op control, and {@code warmOwn - hashOwn}. See below.</li>
 * </ul>
 *
 * <h2>Two denominators, and only one of them is VibeTags'</h2>
 *
 * <p>The first draft of this table had one, and it is the diluted one. {@code -proc:none} switches
 * off javac's entire annotation-processing machinery, which at N=1000 is about three quarters of
 * the figure and which no change to this codebase can touch, so a saving reported against it reads
 * far smaller than the saving actually is. Issue #834 was opened on that reading: "a no-op rebuild
 * still costs 96 % of a cold build". Measured against the no-op control instead:
 *
 * <pre>
 *   N       savedOwn, before #833     savedOwn, since #833     warmOwn, since #833
 *   100     27.1 %                    18.3 / 17.4 %            4.4 / 4.2 MB
 *   500     16.1 %                    -0.8 / -1.0 %            25.4 / 25.8 MB
 *   1000    14.7 %                    -1.3 / -1.2 %            48.4 / 49.0 MB
 * </pre>
 *
 * <p>The first column is one run of a processor built at 5411b87b, and it reproduces the 15 % to
 * 30 % the first version of this column reported: that figure was measured against a processor
 * jar from before #833. The other two are two runs of the current processor, same session. #833
 * stopped {@code ModuleSidecar.unreadableSidecarNames} re-parsing the round's own sidecar on every
 * build. That cost about 9 MB at N=1000 and sat on the path the short-circuit skips, so it was
 * most of what the short-circuit saved. Removing it took the cold round from 57.5 MB to 48.1 MB and
 * left the warm round where it was. From N=500 up the short-circuit now saves no allocation to
 * speak of. It still saves the writes, which is its point, and about 15 % of wall-clock at N=1000.
 *
 * <p>So {@code warmOwn} is the whole remaining opportunity, about 48 MB at N=1000, against the
 * 225 MB the diluted column reports as still standing, and almost all of it is the live-round work
 * that runs before a fingerprint can be computed. Any proposal to decide earlier than
 * {@code generateFiles()} is bidding for a share of that.
 *
 * <p>The pair reproduces where {@code ProcessorTaxStressTest}'s equivalent does not: that class
 * measured {@code vibetagsShare} 34 % apart across two runs, while {@code coldOwn} and
 * {@code warmOwn} here agreed to 0.1 %. The difference is that all three compiles happen
 * back-to-back inside one test method over one fixture, so whatever the machine is doing applies
 * to all of them.
 *
 * <h2>What deciding earlier would cost</h2>
 *
 * <p>An exit ahead of the collection walk needs a proof that no annotated source changed, and an
 * mtime proxy is ruled out because it fails towards stale guardrail files. So the proxy reads
 * content, and {@link SourceHashProcessor} does exactly that much: it hashes every source in the
 * first round and nothing else. {@code headroom} is what would be left to win if that hash
 * replaced everything the warm round still does, and it is an upper bound, because the control
 * leaves out the digest table, its persistence and the transitive-manifest proof a real exit
 * would also need. A headroom near zero, or below it, settles #834 without designing the rest.
 *
 * <pre>
 *   N       hashOwn          headroom
 *   100     -0.9 / -0.9 MB   5.3 / 5.2 MB
 *   500     0.6 / 0.5 MB     24.9 / 25.3 MB
 *   1000    1.5 / 1.7 MB     46.9 / 47.3 MB
 * </pre>
 *
 * <p>Two runs, same session. It is not near zero. Hashing every source costs about 3 % of
 * {@code warmOwn} at N=1000, and at N=100 it sits below the run-to-run floor, which is what a
 * negative figure means. So cost is not what stops an early exit. What stops it is correctness:
 * the transitive-manifest proof and invariant 17's partial-round rule, both recorded on #834.
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
     * cost. It is not: measured on main at 802d1420 the saving is 4.9 % at N=100, 3.3 % at N=500
     * and 3.6 % at N=1000 (recorded in {@code results/1.3.7-SNAPSHOT/incremental-rebuild.txt}),
     * because the collector has already walked every annotated element in the round before a
     * fingerprint can be computed, and that walk is where the allocation is.
     *
     * <p>Against the no-op control the same saving was 15 % to 30 % before #833, and since #833
     * it is about zero from N=500 up, because that change removed most of what the short-circuit
     * was skipping (see the class javadoc). Neither number can carry a gate. The diluted one is close enough to the
     * run-to-run floor that a threshold either fails healthy builds or passes a broken
     * short-circuit, and the honest one is a difference of two measurements, each with its own
     * floor. The note is deterministic, so it is asserted instead, and both savings are reported
     * rather than gated.
     */
    private static final String SHORT_CIRCUIT_NOTE = "inputs unchanged since last run";

    /** What the same note carries when the early exit ahead of the collection walk fired (#834). */
    private static final String EARLY_EXIT_NOTE = "(source digest ";

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
            pw.println("=".repeat(170));
            pw.println("ColdAlloc/WarmAlloc/Saved are against a -proc:none baseline, so they carry"
                + " javac's whole annotation-processing");
            pw.println("subsystem as well as VibeTags'. ColdOwn/WarmOwn/SavedOwn are against a"
                + " no-op processor, which is the only");
            pw.println("base a change to this codebase can move (#834). HashOwn is what hashing every"
                + " source costs over the same no-op");
            pw.println("processor, the minimum an exit ahead of the collection walk would spend."
                + " Headroom is WarmOwn - HashOwn, an upper bound.");
            pw.println();
            pw.printf("%-10s %-16s %-16s %-12s %-12s %-14s %-16s %-16s %-14s %-14s %-14s%n",
                "Classes", "ColdAlloc(KB)", "WarmAlloc(KB)", "Cold(ms)", "Warm(ms)", "Saved(%)",
                "ColdOwn(KB)", "WarmOwn(KB)", "SavedOwn(%)", "HashOwn(KB)", "Headroom(KB)");
            pw.println("-".repeat(170));
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

        // On disk, as a build tool hands them to javac: the early exit (#834) reads every source
        // file back to hash it, and an in-memory source has no file, so it could never fire here.
        Path sourceDir = Files.createDirectories(tempDir.resolve("src/com/example/generated"));
        List<JavaFileObject> sources = new ArrayList<>();
        for (String[] pair : SyntheticClassGenerator.generate(n)) {
            sources.add(source(sourceDir, pair[0], withoutDraft(pair[1])));
        }

        // The first compile in a JVM carries a one-off class-loading tail bigger than the effect,
        // so it is spent in a project root nothing is read back out of.
        compile(sources, root(tempDir, "warmup"), tempDir, "classes-warmup");

        long baseline = compileWithoutProcessor(sources, tempDir);
        long noOp = compileWithNoOpProcessor(sources, tempDir);
        long hash = compileWithSourceHashProcessor(sources, tempDir);
        int hashedCount = SourceHashProcessor.lastHashedCount();

        Path projectRoot = root(tempDir, "project");
        Measurement cold = compile(sources, projectRoot, tempDir, "classes-cold");
        Map<String, String> afterCold = generated(projectRoot);
        Measurement warm = compile(sources, projectRoot, tempDir, "classes-warm");
        Map<String, String> afterWarm = generated(projectRoot);

        long coldOverhead = cold.allocatedBytes - baseline;
        long warmOverhead = warm.allocatedBytes - baseline;
        double savedShare = coldOverhead <= 0 ? 0.0 : 100.0 * (coldOverhead - warmOverhead) / coldOverhead;

        // The same saving over the only base a change to this codebase can move. See
        // compileWithNoOpProcessor: `overhead` carries javac's whole annotation-processing
        // subsystem, and `own` does not.
        long coldOwn = cold.allocatedBytes - noOp;
        long warmOwn = warm.allocatedBytes - noOp;
        double savedOwnShare = coldOwn <= 0 ? 0.0 : 100.0 * (coldOwn - warmOwn) / coldOwn;

        // What an early "sources unchanged" exit would have to spend, over the same control, and
        // what would be left for it to win (#834). See SourceHashProcessor for why this is a
        // lower bound on the cost and so an upper bound on the headroom.
        long hashOwn = hash - noOp;
        long headroom = warmOwn - hashOwn;

        String line = String.format(Locale.ROOT,
            "%-10d %-16d %-16d %-12d %-12d %-14.1f %-16d %-16d %-14.1f %-14d %-14d",
            n, coldOverhead / 1024, warmOverhead / 1024, cold.elapsedMillis, warm.elapsedMillis,
            savedShare, coldOwn / 1024, warmOwn / 1024, savedOwnShare, hashOwn / 1024,
            headroom / 1024);
        System.out.println(line);
        append(line);

        nothingMoved(afterCold, afterWarm, n);
        shortCircuitFired(cold, warm, n);
        assertTrue(warm.notes.contains(EARLY_EXIT_NOTE),
            "the rebuild of unchanged sources took the fingerprint short-circuit but not the early"
                + " exit ahead of the collection walk (#834) at N=" + n + ", so it walked every"
                + " element again. Processor said: " + warm.notes);
        assertEquals(n, hashedCount,
            "the source-hash control hashed " + hashedCount + " of " + n + " sources, so HashOwn"
                + " is the price of hashing fewer files than an early exit would have to read");
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
        return compileWithOptions(sources, tempDir, "classes-baseline", List.of("-proc:none"));
    }

    /**
     * The same compile with annotation processing on and a processor that does nothing.
     *
     * <p>The reason this column exists is the reason {@code ProcessorTaxStressTest} exists, and it
     * matters more here than anywhere else in the harness. Subtracting {@code -proc:none} charges
     * VibeTags for javac's entire annotation-processing subsystem, which at N=1000 is about three
     * quarters of the figure. A saving reported against that base is diluted by the part of the
     * build no change to this codebase can touch, and {@code Saved(%)} was being read as if it
     * were VibeTags' own (issue #834). {@link NoOpProcessor} is the honest denominator: the price
     * of running <em>an</em> annotation processor, which the short-circuit cannot avoid because
     * the processor still has to be invoked.
     */
    private static long compileWithNoOpProcessor(List<JavaFileObject> sources, Path tempDir)
            throws IOException {
        return compileWithOptions(sources, tempDir, "classes-noop",
            List.of("-processor", NoOpProcessor.class.getName()));
    }

    /**
     * The same compile with a processor that hashes every source and does nothing else: the
     * least an exit ahead of the collection walk would have to spend (#834).
     */
    private static long compileWithSourceHashProcessor(List<JavaFileObject> sources, Path tempDir)
            throws IOException {
        return compileWithOptions(sources, tempDir, "classes-hash",
            List.of("-processor", SourceHashProcessor.class.getName()));
    }

    private static long compileWithOptions(List<JavaFileObject> sources, Path tempDir,
                                           String classesDirName, List<String> extra)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classesDir = tempDir.resolve(classesDirName);
        Files.createDirectories(classesDir);
        List<String> options = new ArrayList<>(List.of(
            "-source", "17",
            "-target", "17",
            "-d", classesDir.toString(),
            "-classpath", System.getProperty("java.class.path")));
        options.addAll(extra);
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

    /**
     * The generator puts {@code @AIDraft} on every 7th class and {@code @AILocked} on others, so
     * every 14th class carries both and validation warns that they contradict. A build that warned
     * is never recorded as skippable, because a skipped build could not repeat the warning (#834),
     * so with the generator's output unchanged the early exit could not fire here at all. Only this
     * test drops the draft annotation; the generator stays as the other load tests use it.
     */
    private static String withoutDraft(String code) {
        return code.lines()
            .filter(line -> !line.startsWith("@AIDraft("))
            .collect(Collectors.joining("\n", "", "\n"));
    }

    private static JavaFileObject source(Path sourceDir, String simpleName, String code) throws IOException {
        Path file = sourceDir.resolve(simpleName + ".java");
        Files.writeString(file, code, StandardCharsets.UTF_8);
        URI uri = file.toUri();
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }

            // SourceHashProcessor reads bytes, as a proxy over files on disk would. This one
            // materialises each file once where a disk read streams it, which overstates the hash
            // arm by at most the fixture's source bytes, well under the run-to-run floor.
            @Override
            public InputStream openInputStream() {
                return new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8));
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
