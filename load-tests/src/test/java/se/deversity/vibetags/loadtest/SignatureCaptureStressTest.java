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
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What an ordinary build stops paying for when nothing is going to read the result.
 *
 * <p>{@code ElementSignature.of} renders a type's whole visible member set and sorts it. It is the
 * most expensive thing the collector does per element, and its only reader is the opt-in enforcing
 * mode ({@code -Avibetags.enforce}). Since RC9 the collector skips it unless enforcement is on;
 * before that, every build built those strings and dropped them.
 *
 * <p>The existing sweeps cannot see the difference, and that is a property of their fixture rather
 * than of the change: {@link SyntheticClassGenerator} emits classes with one method each, so the
 * per-type signature work is a rounding error next to javac's own allocation. This test uses the
 * shape where the cost actually lives — <b>wide</b> types, many visible members each, which is what
 * a real service or domain class looks like.
 *
 * <p>It measures the same compilation twice, with enforcement on and off, and reports the delta.
 * That delta <em>is</em> the saving, because enforcement-on is what the default path used to do.
 * The assertion is the regression guard: make signature capture unconditional again and this goes
 * red.
 *
 * <pre>
 *   cd load-tests
 *   mvn test -Dtest=SignatureCaptureStressTest
 * </pre>
 *
 * Results are appended to {@code target/signature-capture-<timestamp>.txt}.
 */
class SignatureCaptureStressTest {

    private static final Path RESULTS_FILE = Path.of("target",
        "signature-capture-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt");

    private static final String PROCESSOR_CLASS = "se.deversity.vibetags.processor.AIGuardrailProcessor";

    /** Opt-in files the processor regenerates; without them it writes nothing and measures nothing. */
    private static final String[] OPT_IN_FILES = {".cursorrules", "CLAUDE.md", "AGENTS.md"};

    /** Wide enough that the per-type member walk dominates, small enough to stay under a minute. */
    private static final int CLASSES = 400;
    private static final int MEMBERS_PER_CLASS = 40;

    /**
     * Minimum share of the processor's own allocation overhead that turning enforcement off must
     * save, as a percentage.
     *
     * <p>Measured on this fixture: 6.9 % with the optimization present (36 MB), 0.0 % without it.
     * The threshold sits between those, nearer the floor, so run-to-run variance cannot fail a
     * healthy build while the optimization's removal cannot pass one.
     */
    private static final double MIN_SAVED_SHARE = 3.0;

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
    }

    @AfterEach
    void releaseProcessorLogHandle() {
        VibeTagsLogger.shutdown();
    }

    @Test
    void enforcementOffAllocatesLessThanEnforcementOn(@TempDir Path tempDir) throws Exception {
        List<JavaFileObject> sources = wideClasses(CLASSES, MEMBERS_PER_CLASS);

        // Warm-up pass on each side first: the first compile in a JVM carries a one-off
        // class-loading tail that is larger than the effect being measured.
        compile(sources, root(tempDir, "warm-off"), false, tempDir, "classes-warm-off");
        compile(sources, root(tempDir, "warm-on"), true, tempDir, "classes-warm-on");

        long baseline = compileWithoutProcessor(sources, tempDir);
        long on = compile(sources, root(tempDir, "on"), true, tempDir, "classes-on");
        long off = compile(sources, root(tempDir, "off"), false, tempDir, "classes-off");

        // Against javac's own allocation the saving looks small; against the processor's own
        // overhead — the only part VibeTags is responsible for — it is the number that matters.
        long overheadOn = on - baseline;
        long overheadOff = off - baseline;
        long savedKb = (on - off) / 1024;
        double shareOfOverhead = overheadOn <= 0 ? 0.0 : 100.0 * (on - off) / overheadOn;
        String line = String.format(Locale.ROOT,
            "classes=%d members=%d baseline=%dKB enforceOn=%dKB enforceOff=%dKB "
                + "overheadOn=%dKB overheadOff=%dKB saved=%dKB (%.1f%% of processor overhead)",
            CLASSES, MEMBERS_PER_CLASS, baseline / 1024, on / 1024, off / 1024,
            overheadOn / 1024, overheadOff / 1024, savedKb, shareOfOverhead);
        System.out.println(line);
        append(line);

        // A bare `off < on` is not a regression gate. Run this against 0.9.5, where signature
        // capture was unconditional and there is nothing to save, and it reports
        // "saved=216KB (0.0% of processor overhead)" out of 556 MB — then passes, because two
        // noisy measurements of the same work land on either side of each other about half the
        // time. The gate has to assert the size of the saving, not merely its sign.
        //
        // MIN_SAVED_SHARE is set well below what the optimization actually delivers (6.9 % of
        // processor overhead on RC9, 36 MB, which the README reports as reproducible to 0.3 %) and
        // far above what its absence produces (0.0 %). Anything in between means the saving shrank
        // enough to be worth a look, which is exactly when this should fail.
        assertTrue(shareOfOverhead >= MIN_SAVED_SHARE, String.format(Locale.ROOT,
            "Enforcement-off must save at least %.1f%% of the processor's own allocation overhead, "
                + "got %.1f%%. Signature capture has most likely become unconditional again. %s",
            MIN_SAVED_SHARE, shareOfOverhead, line));
    }

    private static Path root(Path tempDir, String name) throws IOException {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root);
        for (String f : OPT_IN_FILES) {
            Files.createFile(root.resolve(f));
        }
        return root;
    }

    /**
     * {@code n} classes with {@code members} public methods and fields each, every class carrying
     * {@code @AILocked} — the family the enforcing mode signs, so the signature is computed for all
     * of them when it is on.
     */
    private static List<JavaFileObject> wideClasses(int n, int members) {
        List<JavaFileObject> sources = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder(4096);
            sb.append("package com.example.wide;\n")
              .append("import se.deversity.vibetags.annotations.AILocked;\n")
              .append("@AILocked(reason = \"synthetic wide class #").append(i).append("\")\n")
              .append("public class Wide").append(i).append(" {\n");
            for (int m = 0; m < members; m++) {
                sb.append("    public final String field").append(m).append(" = \"\";\n")
                  .append("    public String method").append(m)
                  .append("(String a, int b, java.util.List<String> c) throws java.io.IOException { return a; }\n");
            }
            sb.append("}\n");
            sources.add(source("Wide" + i, sb.toString()));
        }
        return sources;
    }

    /** The same compile with no annotation processing at all — javac's own share of the total. */
    private static long compileWithoutProcessor(List<JavaFileObject> sources, Path tempDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classesDir = tempDir.resolve("classes-baseline");
        Files.createDirectories(classesDir);
        List<String> options = List.of(
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

    /** Compiles once and returns bytes allocated on this thread during the compile. */
    private static long compile(List<JavaFileObject> sources, Path projectRoot, boolean enforce,
                                Path tempDir, String classesDirName) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("javax.tools.JavaCompiler not available — run with a JDK, not a JRE.");
        }
        Path classesDir = tempDir.resolve(classesDirName);
        Files.createDirectories(classesDir);

        List<String> options = new ArrayList<>(List.of(
            "-d", classesDir.toString(),
            "-classpath", System.getProperty("java.class.path"),
            "-processor", PROCESSOR_CLASS,
            "-Avibetags.root=" + projectRoot.toAbsolutePath(),
            // The cache would short-circuit the second compile of identical content, which would
            // measure the cache rather than the collector.
            "-Avibetags.cache=false"));
        if (enforce) {
            options.add("-Avibetags.enforce=locked");
        }

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

    private static JavaFileObject source(String simpleName, String code) {
        URI uri = URI.create("string:///com/example/wide/" + simpleName + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    private static void append(String line) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(RESULTS_FILE, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            pw.println(line);
        }
    }
}
