package se.deversity.vibetags.loadtest;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.VibeTagsLogger;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Splits the allocation "overhead" into the part any annotation processor costs and the part
 * VibeTags actually adds.
 *
 * <p>{@code MemoryVolumeStressTest} subtracts a {@code -proc:none} compile from a VibeTags compile
 * and reports the difference as the processor's cost. That subtraction charges VibeTags for javac's
 * entire annotation-processing subsystem — the extra rounds, the processing environment, the
 * retained element model — none of which VibeTags can make cheaper. Any optimization effort aimed
 * at that portion is aimed at nothing.
 *
 * <p>So this measures three compiles of the same sources:
 *
 * <ol>
 *   <li>{@code -proc:none} — annotation processing off entirely</li>
 *   <li>{@link NoOpProcessor} — processing on, doing nothing</li>
 *   <li>VibeTags</li>
 * </ol>
 *
 * and reports {@code apTax = noOp − procNone} against {@code vibetags = full − noOp}. The second
 * number is the one a change to this codebase can move, and the one worth quoting as "what VibeTags
 * costs".
 *
 * <p>Allocation, not wall-clock: {@code ThreadMXBean.getThreadAllocatedBytes} counts bytes and does
 * not care that the machine is busy, which is what makes it reproducible enough to act on.
 */
class ProcessorTaxStressTest {

    private static final Path RESULTS_FILE = Path.of("target", "processor-tax-results.txt");
    private static final String PROCESSOR_CLASS = "se.deversity.vibetags.processor.AIGuardrailProcessor";
    private static final String NO_OP_CLASS = NoOpProcessor.class.getName();

    private static final String[] OPT_IN_FILES = {
        ".cursorrules", "CLAUDE.md", ".aiexclude", "AGENTS.md", "gemini_instructions.md", "QWEN.md"
    };

    private static final int CLASSES = 1000;

    private static ThreadMXBean threadBean;

    @BeforeAll
    static void prepare() throws IOException {
        java.lang.management.ThreadMXBean base = ManagementFactory.getThreadMXBean();
        assumeTrue(base instanceof ThreadMXBean,
            "com.sun.management.ThreadMXBean not available on this JVM");
        threadBean = (ThreadMXBean) base;
        assumeTrue(threadBean.isThreadAllocatedMemorySupported(),
            "per-thread allocation counters not supported on this JVM");
        threadBean.setThreadAllocatedMemoryEnabled(true);
        Files.createDirectories(RESULTS_FILE.getParent());
    }

    @AfterEach
    void releaseProcessorLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void mostOfTheOverheadIsJavacsAnnotationProcessingSubsystem(@TempDir Path tempDir) throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve(".codex").resolve("rules"));
        Files.createDirectories(projectRoot.resolve(".github"));
        Files.createDirectories(projectRoot.resolve(".qwen").resolve("commands"));
        for (String f : OPT_IN_FILES) {
            Files.createFile(projectRoot.resolve(f));
        }

        List<String[]> sources = SyntheticClassGenerator.generate(CLASSES);

        // Warm the shared compile path first, or whichever variant runs first carries a one-off
        // class-loading tail bigger than the differences being measured.
        compile(sources, projectRoot, Variant.PROC_NONE, tempDir, "warm");

        long procNone = compile(sources, projectRoot, Variant.PROC_NONE, tempDir, "none");
        long noOp = compile(sources, projectRoot, Variant.NO_OP, tempDir, "noop");
        long vibetags = compile(sources, projectRoot, Variant.VIBETAGS, tempDir, "full");

        long apTax = noOp - procNone;
        long vibetagsShare = vibetags - noOp;
        long reported = vibetags - procNone;
        double vibetagsPercent = reported <= 0 ? 0.0 : 100.0 * vibetagsShare / reported;

        String line = String.format(Locale.ROOT,
            "classes=%d procNone=%dKB noOp=%dKB vibetags=%dKB | apTax=%dKB vibetagsShare=%dKB "
                + "(%.1f%% of the %dKB this harness reports as \"processor overhead\")",
            CLASSES, procNone / 1024, noOp / 1024, vibetags / 1024,
            apTax / 1024, vibetagsShare / 1024, vibetagsPercent, reported / 1024);
        System.out.println(line);
        Files.writeString(RESULTS_FILE, line + System.lineSeparator(),
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        assertTrue(vibetags > noOp,
            "VibeTags must allocate more than a processor that does nothing, or this fixture is "
                + "not exercising it: " + line);
        assertTrue(noOp > procNone,
            "Turning annotation processing on must cost something, or the no-op control is not "
                + "running: " + line);
    }

    // -----------------------------------------------------------------------

    private enum Variant { PROC_NONE, NO_OP, VIBETAGS }

    private static long compile(List<String[]> sources, Path projectRoot, Variant variant,
                                Path tempDir, String label) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("run with a JDK, not a JRE");
        }
        Path classesDir = tempDir.resolve("classes-" + label);
        Files.createDirectories(classesDir);

        List<JavaFileObject> sourceFiles = new ArrayList<>();
        for (String[] pair : sources) {
            sourceFiles.add(new InMemorySource(pair[0], pair[1]));
        }

        List<String> options = new ArrayList<>(List.of(
            "-source", "17", "-target", "17",
            "-d", classesDir.toString(),
            "-classpath", System.getProperty("java.class.path")));
        switch (variant) {
            case PROC_NONE -> options.add("-proc:none");
            case NO_OP -> {
                options.add("-processor");
                options.add(NO_OP_CLASS);
            }
            case VIBETAGS -> {
                options.add("-processor");
                options.add(PROCESSOR_CLASS);
                options.add("-Avibetags.root=" + projectRoot.toAbsolutePath());
            }
            default -> throw new IllegalStateException("unhandled variant " + variant);
        }

        System.gc();
        long tid = Thread.currentThread().getId();
        long before = threadBean.getThreadAllocatedBytes(tid);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm =
                 compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            compiler.getTask(null, fm, diagnostics, options, null, sourceFiles).call();
        }
        return threadBean.getThreadAllocatedBytes(tid) - before;
    }

    /** Same shape as the generator's output, kept local so this test owns its own fixture. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        InMemorySource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
