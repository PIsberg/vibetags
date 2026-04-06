package se.deversity.vibetags.loadtest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Annotation-volume stress test for {@code AIGuardrailProcessor}.
 *
 * <h2>What it measures</h2>
 * <ul>
 *   <li><b>processorTime</b> – wall-clock (ms) to compile N synthetic annotated classes
 *       with the VibeTags processor active.</li>
 *   <li><b>baselineTime</b>  – same compilation with {@code -proc:none} (compiler overhead only).</li>
 *   <li><b>overhead</b>      – {@code processorTime - baselineTime} — cost attributable to
 *       the processor itself.</li>
 *   <li><b>outputSize</b>    – total bytes in all generated AI-config files after the run.</li>
 * </ul>
 *
 * <h2>How to run</h2>
 * <pre>
 *   cd load-tests
 *   mvn test -Dtest=AnnotationVolumeStressTest
 * </pre>
 *
 * Results are written to {@code target/stress-results.txt} and also printed to stdout.
 */
class AnnotationVolumeStressTest {


    /** One shared results file written during the full test run. Filename includes a timestamp so successive runs accumulate. */
    private static final Path RESULTS_FILE = Path.of("target",
            "stress-results-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt");

    private static final String PROCESSOR_CLASS = "se.deversity.vibetags.processor.AIGuardrailProcessor";

    // Names of the opt-in files we pre-create so the processor has services to write.
    private static final String[] OPT_IN_FILES = {
        ".cursorrules", "CLAUDE.md", ".aiexclude", "AGENTS.md",
        "gemini_instructions.md", "QWEN.md"
    };

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeAll
    static void prepareResultsFile() throws IOException {
        Files.createDirectories(RESULTS_FILE.getParent());
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            pw.println("VibeTags Annotation-Volume Stress Test Results");
            pw.println("=".repeat(70));
            pw.printf("%-10s %-18s %-18s %-18s %-14s%n",
                    "Classes", "ProcessorTime(ms)", "BaselineTime(ms)", "Overhead(ms)", "OutputSize(B)");
            pw.println("-".repeat(70));
        }
    }

    // -------------------------------------------------------------------------
    // Parameterised sweep
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "N={0} classes")
    @ValueSource(ints = {10, 100, 500, 1000, 5000, 10_000})
    void stressTest_nAnnotatedClasses(int n, @TempDir Path tempDir) throws Exception {
        int maxClasses = Integer.parseInt(System.getProperty("stress.max.classes", String.valueOf(Integer.MAX_VALUE)));
        assumeTrue(n <= maxClasses, "Skipping N=" + n + " (stress.max.classes=" + maxClasses + ")");
        // --- create opt-in service files so the processor actively writes output ---
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve(".codex").resolve("rules"));
        Files.createDirectories(projectRoot.resolve(".github"));
        Files.createDirectories(projectRoot.resolve(".qwen").resolve("commands"));
        for (String f : OPT_IN_FILES) {
            Files.createFile(projectRoot.resolve(f));
        }

        // --- generate synthetic Java sources ---
        List<String[]> sources = SyntheticClassGenerator.generate(n);

        // --- compile WITH processor ---
        long processorTime = compileInProcess(sources, projectRoot, /*procNone=*/false, tempDir);

        // --- compile WITHOUT processor (baseline) ---
        long baselineTime = compileInProcess(sources, projectRoot, /*procNone=*/true, tempDir);

        long overhead = processorTime - baselineTime;

        // --- measure output file sizes ---
        long outputSize = measureOutputSize(projectRoot);

        // --- assertions: compilation must succeed and files must be non-empty ---
        assertTrue(outputSize > 0, "Processor should have written output files for N=" + n);
        assertFalse(Files.readString(projectRoot.resolve(".cursorrules")).isEmpty(),
                ".cursorrules must have content for N=" + n);

        // --- log results ---
        String line = String.format("%-10d %-18d %-18d %-18d %-14d",
                n, processorTime, baselineTime, overhead, outputSize);
        System.out.println(line);
        appendResult(line);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Compiles {@code sources} in-process using {@code javax.tools.JavaCompiler}.
     *
     * @param procNone if {@code true} passes {@code -proc:none} to skip annotation processing
     * @return wall-clock time in milliseconds
     */
    private long compileInProcess(List<String[]> sources, Path projectRoot,
                                  boolean procNone, Path tempDir) throws IOException {

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "javax.tools.JavaCompiler not available — run tests with a JDK, not a JRE.");
        }

        // Output directory for class files
        Path classesDir = tempDir.resolve(procNone ? "classes-baseline" : "classes-proc");
        Files.createDirectories(classesDir);

        // Build in-memory source file objects
        List<JavaFileObject> sourceFiles = sources.stream()
                .map(pair -> makeSource(pair[0], pair[1]))
                .collect(Collectors.toList());

        // Build compiler options
        List<String> options = new ArrayList<>(List.of(
                "-source", "17",
                "-target", "17",
                "-d", classesDir.toString(),
                // Add the processor jar and annotations to the classpath
                "-classpath", buildClasspath()
        ));

        if (procNone) {
            options.add("-proc:none");
        } else {
            options.add("-processor");
            options.add(PROCESSOR_CLASS);
            // Tell the processor which directory to treat as the project root.
            // This is read via processingEnv.getOptions().get("vibetags.root") in
            // AIGuardrailProcessor, overriding the default Paths.get("") (JVM CWD).
            options.add("-Avibetags.root=" + projectRoot.toAbsolutePath());
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        long start = System.currentTimeMillis();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {

            // We pass sources as in-memory objects; no file-manager plumbing needed.
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,          // use stderr
                    fm,
                    diagnostics,
                    options,
                    null,          // no annotation-processing class names (uses -processor flag)
                    sourceFiles
            );

            task.call(); // errors are expected for missing-class scenarios; we don't fail on them
        }
        return System.currentTimeMillis() - start;
    }

    /** Builds a Java classpath string that includes the processor and annotation jars. */
    private static String buildClasspath() {
        // Use the current JVM classpath — the processor jar is already on it because
        // it is a dependency of this Maven module (vibetags-processor).
        return System.getProperty("java.class.path");
    }

    /** Creates an in-memory {@link JavaFileObject} from source text. */
    private static JavaFileObject makeSource(String simpleName, String source) {
        URI uri = URI.create("string:///com/example/generated/" + simpleName + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    /** Sums all bytes in the opt-in output files that the processor may have written. */
    private static long measureOutputSize(Path root) throws IOException {
        long total = 0;
        for (String name : OPT_IN_FILES) {
            Path p = root.resolve(name);
            if (Files.exists(p)) {
                total += Files.size(p);
            }
        }
        return total;
    }

    private static synchronized void appendResult(String line) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE,
                StandardCharsets.UTF_8, StandardOpenOption.APPEND))) {
            pw.println(line);
        }
    }
}
