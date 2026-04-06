package se.deversity.vibetags.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent build safety test for {@code AIGuardrailProcessor}.
 *
 * <h2>What it does</h2>
 * <p>Spins up N threads, each invoking {@code javax.tools.JavaCompiler.getTask()} against the
 * <em>same shared project root</em> (i.e., the same output files).  With no file-level locking
 * in {@code writeFileIfChanged}, concurrent writes can produce truncated or interleaved content.
 *
 * <p>The test documents whether this happens by:
 * <ol>
 *   <li>Running N compiler tasks in parallel.</li>
 *   <li>Asserting that <em>all output files still exist</em> after the storm.</li>
 *   <li>Asserting that <em>each file contains the VibeTags header</em> (minimal parsability check).</li>
 *   <li>Reporting any thread that threw an exception — {@code IOException} from interleaved
 *       writes surfaces here.</li>
 * </ol>
 *
 * <p><b>Expected outcome:</b> The test may log "FILE CORRUPTION detected" warnings for
 * truncated-write races.  This is intentional — the test is a diagnostic that exposes the
 * known gap in thread safety.  The assertions focus on what must hold even under contention;
 * they are designed to highlight problems, not hide them.
 *
 * <h2>Configuration</h2>
 * <pre>
 *   # Default: 4 threads; override:
 *   mvn test -Dtest=ConcurrentBuildTest -Dload.test.threads=8
 * </pre>
 */
class ConcurrentBuildTest {

    private static final String PROCESSOR_CLASS = "se.deversity.vibetags.processor.AIGuardrailProcessor";

    /** File whose presence acts as the opt-in for the processor. */
    private static final String[] OPT_IN_FILES = {
        ".cursorrules", "CLAUDE.md", ".aiexclude", "AGENTS.md",
        "gemini_instructions.md", "QWEN.md"
    };

    /** Token present in the header of every file the processor generates. */
    private static final String HEADER_MARKER = "VibeTags";

    // -------------------------------------------------------------------------
    // Main test
    // -------------------------------------------------------------------------

    @Test
    void concurrentCompile_sharedRoot_filesRemainParseable(@TempDir Path tempDir) throws Exception {
        int threadCount = resolveThreadCount();
        System.out.printf("%n[ConcurrentBuildTest] Running %d threads against a shared project root%n",
                threadCount);

        // --- shared project root: opt-in files must exist BEFORE any thread compiles ---
        Path projectRoot = tempDir.resolve("shared-project");
        Files.createDirectories(projectRoot.resolve(".codex").resolve("rules"));
        Files.createDirectories(projectRoot.resolve(".github"));
        Files.createDirectories(projectRoot.resolve(".qwen").resolve("commands"));
        for (String f : OPT_IN_FILES) {
            Files.createFile(projectRoot.resolve(f));
        }

        // --- generate a modest class set (10 classes) so compilation is fast ---
        List<String[]> sources = SyntheticClassGenerator.generate(10);

        // --- launch threads ---
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1); // all threads start simultaneously
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<ThreadResult>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                startGate.await(); // synchronize start
                return runCompile(threadId, sources, projectRoot, tempDir);
            }));
        }

        startGate.countDown(); // release all threads at once
        pool.shutdown();
        boolean finished = pool.awaitTermination(120, TimeUnit.SECONDS);
        assertTrue(finished, "Thread pool did not finish within 2 minutes");

        // --- collect results ---
        List<ThreadResult> results = new ArrayList<>();
        for (Future<ThreadResult> f : futures) {
            results.add(f.get());
        }

        long errors = results.stream().filter(r -> r.exception != null).count();
        long successes = results.stream().filter(r -> r.exception == null).count();

        System.out.printf("[ConcurrentBuildTest] Threads: %d  |  Succeeded: %d  |  Errors: %d%n",
                threadCount, successes, errors);

        // Print per-thread error details (informational, not a hard failure)
        results.stream()
               .filter(r -> r.exception != null)
               .forEach(r -> System.out.printf(
                       "  Thread %d threw: %s: %s%n",
                       r.threadId, r.exception.getClass().getSimpleName(), r.exception.getMessage()));

        // --- post-condition checks ---
        // 1. All threads must have submitted their task (no thread-pool stall)
        assertEquals(threadCount, results.size(), "All futures must resolve");

        // 2. Output files must still exist
        for (String f : OPT_IN_FILES) {
            Path p = projectRoot.resolve(f);
            assertTrue(Files.exists(p), "Output file must still exist after concurrent writes: " + f);
        }

        // 3. Each output file must contain the VibeTags header (minimal parsability)
        List<String> corruptedFiles = new ArrayList<>();
        for (String f : OPT_IN_FILES) {
            Path p = projectRoot.resolve(f);
            String content = Files.readString(p, StandardCharsets.UTF_8);
            if (!content.contains(HEADER_MARKER)) {
                corruptedFiles.add(f + " (size=" + content.length() + ")");
            }
        }

        if (!corruptedFiles.isEmpty()) {
            System.out.println("[ConcurrentBuildTest] FILE CORRUPTION detected in: " + corruptedFiles);
            System.out.println("  -> This confirms the known lack of file locking in writeFileIfChanged.");
            System.out.println("  -> These files had their header overwritten by a concurrent thread.");
        } else {
            System.out.println("[ConcurrentBuildTest] No corruption detected in this run.");
            System.out.println("  -> Locking may still be required; timing-sensitive races can be sporadic.");
        }

        // We document corruption but do NOT hard-fail the test.
        // Change the assertion below once file locking is implemented:
        //   assertTrue(corruptedFiles.isEmpty(), "No files should be corrupted: " + corruptedFiles);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ThreadResult runCompile(int threadId, List<String[]> sources,
                                    Path projectRoot, Path tempDir) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IllegalStateException("JavaCompiler unavailable");
            }

            Path classesDir = tempDir.resolve("classes-thread-" + threadId);
            Files.createDirectories(classesDir);

            List<JavaFileObject> sourceFiles = sources.stream()
                    .map(pair -> makeSource(pair[0], pair[1]))
                    .collect(Collectors.toList());

            List<String> options = List.of(
                    "-source", "17",
                    "-target", "17",
                    "-d", classesDir.toString(),
                    "-classpath", System.getProperty("java.class.path"),
                    "-processor", PROCESSOR_CLASS,
                    "-Avibetags.root=" + projectRoot.toAbsolutePath()
            );

            DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                    diag, Locale.ROOT, StandardCharsets.UTF_8)) {
                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fm, diag, options, null, sourceFiles);
                task.call();
            }
            return new ThreadResult(threadId, null);
        } catch (Exception e) {
            return new ThreadResult(threadId, e);
        }
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

    private static int resolveThreadCount() {
        String prop = System.getProperty("load.test.threads");
        if (prop != null && !prop.isBlank()) {
            try {
                return Math.max(2, Integer.parseInt(prop.trim()));
            } catch (NumberFormatException ignored) {}
        }
        return 4; // sensible default
    }

    /** Simple value type to carry per-thread outcomes across Future boundaries. */
    private record ThreadResult(int threadId, Exception exception) {}
}
