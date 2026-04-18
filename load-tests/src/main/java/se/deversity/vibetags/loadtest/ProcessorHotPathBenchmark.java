package se.deversity.vibetags.loadtest;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import se.deversity.vibetags.processor.AIGuardrailProcessor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks for the hottest methods in {@code AIGuardrailProcessor}.
 *
 * <h2>Benchmarks included</h2>
 * <table>
 *   <tr><th>Method</th><th>Scenario</th><th>Why it matters</th></tr>
 *   <tr><td>{@code writeFileIfChanged_noChange}</td><td>Content identical → skip write</td>
 *       <td>Most common case in incremental builds</td></tr>
 *   <tr><td>{@code writeFileIfChanged_smallWrite}</td><td>1 KB, always differs</td>
 *       <td>Baseline I/O cost</td></tr>
 *   <tr><td>{@code writeFileIfChanged_largeWrite}</td><td>64 KB, always differs</td>
 *       <td>Find where read-compare-write becomes expensive</td></tr>
 *   <tr><td>{@code buildServiceFileMap}</td><td>Pure Path construction</td>
 *       <td>Called once per compile — should be sub-microsecond</td></tr>
 *   <tr><td>{@code resolveActiveServices_allPresent}</td><td>All service files exist</td>
 *       <td>Files.exists() cost per service</td></tr>
 *   <tr><td>{@code resolveActiveServices_nonePresent}</td><td>Empty project root</td>
 *       <td>Fast-path when no opt-in files are present</td></tr>
 * </table>
 *
 * <h2>Running</h2>
 * <pre>
 *   cd load-tests
 *   mvn package exec:java -Dexec.mainClass=org.openjdk.jmh.Main
 *
 *   # Or run a specific benchmark:
 *   mvn package exec:java -Dexec.mainClass=org.openjdk.jmh.Main \
 *       -Dexec.args="writeFileIfChanged -f 1 -wi 3 -i 5 -tu ms"
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsPrepend = {"-Xms256m", "-Xmx512m"})
@State(Scope.Thread)
public class ProcessorHotPathBenchmark {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private AIGuardrailProcessor processor;

    /** Absolute path of a 1 KB file whose content changes every invocation. */
    private Path smallFile;
    /** Absolute path of a 64 KB file whose content changes every invocation. */
    private Path largeFile;
    /** Absolute path of a file whose content always matches what we write. */
    private Path unchangedFile;

    private String smallContent;
    private String largeContent;
    private String unchangedContent;

    /** A temp root where all service files exist (for resolveActiveServices). */
    private Path allPresentRoot;
    /** A temp root with no files (for resolveActiveServices fast-path). */
    private Path emptyRoot;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        processor = new AIGuardrailProcessor();

        Path tempDir = Files.createTempDirectory("jmh-vibetags-");
        tempDir.toFile().deleteOnExit();

        // --- 1 KB content ---
        smallContent = "a".repeat(1024);
        smallFile = tempDir.resolve("small.md");
        Files.writeString(smallFile, "different-initial", StandardCharsets.UTF_8);

        // --- 64 KB content ---
        largeContent = "b".repeat(64 * 1024);
        largeFile = tempDir.resolve("large.md");
        Files.writeString(largeFile, "different-initial", StandardCharsets.UTF_8);

        // --- unchanged file ---
        unchangedContent = "# header\nsome stable content\n";
        unchangedFile = tempDir.resolve("unchanged.md");
        Files.writeString(unchangedFile, unchangedContent, StandardCharsets.UTF_8);

        // --- roots for resolveActiveServices ---
        allPresentRoot = tempDir.resolve("all-present");
        Files.createDirectories(allPresentRoot.resolve(".codex").resolve("rules"));
        Files.createDirectories(allPresentRoot.resolve(".github"));
        Files.createDirectories(allPresentRoot.resolve(".qwen").resolve("commands"));
        for (Map.Entry<String, Path> e : AIGuardrailProcessor.buildServiceFileMap(allPresentRoot).entrySet()) {
            Path p = e.getValue();
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) Files.createFile(p);
        }

        emptyRoot = tempDir.resolve("empty");
        Files.createDirectories(emptyRoot);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        // Best-effort cleanup; JVM exit will handle the rest for TempDir
    }

    // -------------------------------------------------------------------------
    // Benchmarks: writeFileIfChanged
    // -------------------------------------------------------------------------

    /**
     * Best case: file already has the exact content being written.
     * The method should read-compare and return without writing.
     */
    @Benchmark
    public boolean writeFileIfChanged_noChange() {
        return processor.writeFileIfChanged(unchangedFile.toString(), unchangedContent, true);
    }

    /**
     * 1 KB content, always different from what is on disk.
     * Each invocation triggers a full read-compare-write cycle.
     */
    @Benchmark
    public boolean writeFileIfChanged_smallWrite() throws IOException {
        // Ensure the file has different content before each call
        Files.writeString(smallFile, "x", StandardCharsets.UTF_8);
        return processor.writeFileIfChanged(smallFile.toString(), smallContent, true);
    }

    /**
     * 64 KB content, always different from what is on disk.
     * Measures I/O cost of a large file update.
     */
    @Benchmark
    public boolean writeFileIfChanged_largeWrite() throws IOException {
        Files.writeString(largeFile, "x", StandardCharsets.UTF_8);
        return processor.writeFileIfChanged(largeFile.toString(), largeContent, true);
    }

    // -------------------------------------------------------------------------
    // Benchmarks: buildServiceFileMap
    // -------------------------------------------------------------------------

    /**
     * Pure Path construction — no I/O.  Expected to be sub-microsecond.
     */
    @Benchmark
    public Map<String, Path> buildServiceFileMap(Blackhole bh) {
        Map<String, Path> map = AIGuardrailProcessor.buildServiceFileMap(emptyRoot);
        bh.consume(map);
        return map;
    }

    // -------------------------------------------------------------------------
    // Benchmarks: resolveActiveServices
    // -------------------------------------------------------------------------

    /**
     * All opt-in service files exist — measures {@code Files.exists()} cost per service.
     */
    @Benchmark
    public Set<String> resolveActiveServices_allPresent(Blackhole bh) {
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(allPresentRoot);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        bh.consume(active);
        return active;
    }

    /**
     * No files exist — the fast-path that skips all writes and emits a NOTE.
     */
    @Benchmark
    public Set<String> resolveActiveServices_nonePresent(Blackhole bh) {
        Map<String, Path> serviceFiles = AIGuardrailProcessor.buildServiceFileMap(emptyRoot);
        Set<String> active = AIGuardrailProcessor.resolveActiveServices(noopMessager(), serviceFiles);
        bh.consume(active);
        return active;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static javax.annotation.processing.Messager noopMessager() {
        return new javax.annotation.processing.Messager() {
            public void printMessage(javax.tools.Diagnostic.Kind k, CharSequence m) {}
            public void printMessage(javax.tools.Diagnostic.Kind k, CharSequence m,
                                     javax.lang.model.element.Element e) {}
            public void printMessage(javax.tools.Diagnostic.Kind k, CharSequence m,
                                     javax.lang.model.element.Element e,
                                     javax.lang.model.element.AnnotationMirror a) {}
            public void printMessage(javax.tools.Diagnostic.Kind k, CharSequence m,
                                     javax.lang.model.element.Element e,
                                     javax.lang.model.element.AnnotationMirror a,
                                     javax.lang.model.element.AnnotationValue v) {}
        };
    }
}
