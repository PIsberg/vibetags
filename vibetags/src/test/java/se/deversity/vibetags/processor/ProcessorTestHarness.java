package se.deversity.vibetags.processor;

import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained test harness that compiles annotated Java sources directly via
 * {@link JavaCompiler}, writing processor output to a {@code @TempDir}. Tests
 * using this harness have no dependency on the example project being compiled.
 *
 * <p>Usage pattern (compile once per test class):
 * <pre>{@code
 *   @TempDir static Path tempDir;
 *   private static ProcessorTestHarness harness;
 *
 *   @BeforeAll
 *   static void setUp() throws IOException {
 *       harness = ProcessorTestHarness.withExampleSources(tempDir);
 *   }
 * }</pre>
 */
class ProcessorTestHarness {

    /**
     * One file manager per test thread, reused across every compilation that thread runs.
     *
     * <p>A {@code StandardJavaFileManager} caches its view of the classpath and of the JDK image:
     * building a fresh one per compile made javac re-open and re-index all 26 classpath entries
     * and rebuild its {@code jrt:} index for each of the 278 compilations the suite performs.
     * Measured on the {@code e2e} tier, reuse cut compile time from 931 s to 643 s of thread time.
     * Pinned to 4 threads, which is the vCPU count CI gets, the tier went from 69 s to 47 s of
     * wall clock once the twelve test classes that drive javac themselves were switched over too.
     *
     * <p>Per-thread rather than global because {@code JavacFileManager} is not thread-safe and the
     * suite runs test classes and methods concurrently. Everything a compilation varies —
     * {@code CLASS_OUTPUT}, the options, the compilation units — is set per call, so nothing
     * leaks between tests on the same thread; the 1537 tests of the {@code e2e} tier are the
     * regression check on that. Never closed: the instance lives for the lifetime of the fork.
     */
    private static final ThreadLocal<StandardJavaFileManager> SHARED_FILE_MANAGER =
        ThreadLocal.withInitial(
            () -> ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null));

    /**
     * This thread's shared file manager, wrapped so that closing it is a no-op. Test classes that
     * drive javac themselves should use this instead of {@code getStandardFileManager}; see
     * {@link #SHARED_FILE_MANAGER} for the measurement that motivates it.
     */
    static StandardJavaFileManager sharedFileManager() {
        return new NonClosing(SHARED_FILE_MANAGER.get());
    }

    private final Path root;
    private final List<JavaFileObject> sources = new ArrayList<>();
    private final List<Path> fileSources = new ArrayList<>();
    private javax.annotation.processing.Processor processorOverride;

    ProcessorTestHarness(Path tempDir) throws IOException {
        this(tempDir, true);
    }

    /**
     * @param createDefaults when {@code true}, all opt-in signal files are created so every
     *                       service activates; when {@code false}, no opt-in files are created —
     *                       the caller opts in explicitly via {@link #touchOptIn(String)}.
     */
    ProcessorTestHarness(Path tempDir, boolean createDefaults) throws IOException {
        this.root = tempDir;
        if (createDefaults) {
            createOptInFiles();
        }
    }

    /** Creates a single empty opt-in signal file (relative to the temp root). */
    void touchOptIn(String relative) throws IOException {
        touch(relative);
    }

    /** Creates empty opt-in signal files so the processor activates all services. */
    private void createOptInFiles() throws IOException {
        touch(".cursorrules");
        touch("CLAUDE.md");
        touch(".aiexclude");
        touch("AGENTS.md");
        touch("QWEN.md");
        touch("gemini_instructions.md");
        touch(".github/copilot-instructions.md");
        touch("llms.txt");
        touch("llms-full.txt");
        touch(".cursorignore");
        touch(".claudeignore");
        touch(".copilotignore");
        touch(".qwenignore");
        touch(".codex/config.toml");
        touch(".codex/rules/vibetags.rules");
        touch(".qwen/settings.json");
        touch(".qwen/commands/refactor.md");
        touch("CONVENTIONS.md");
        touch(".aiderignore");
        touch(".trae/rules/.vibetags");
        touch(".roo/rules/.vibetags");
        // New platforms
        touch(".windsurfrules");
        touch(".rules");
        touch(".cody/config.json");
        touch(".codyignore");
        touch(".supermavenignore");
        touch(".continue/rules/.vibetags");
        touch(".tabnine/guidelines/.vibetags");
        touch(".amazonq/rules/.vibetags");
        touch(".ai/rules/.vibetags");
        // v0.8.0 platforms
        touch(".pearai/rules/.vibetags");
        touch(".mentatconfig.json");
        touch("sweep.yaml");
        touch(".plandex.yaml");
        touch(".doubleignore");
        touch(".interpreter/profiles/vibetags.yaml");
        touch(".codeiumignore");
        // v0.9.6 platforms
        touch("GEMINI.md");
        touch(".antigravityignore");
        // v0.9.7 platforms
        touch(".clinerules");
        touch(".junie/guidelines.md");
        touch(".kiro/steering/.vibetags");
        // Context-packer ignore files
        touch(".repomixignore");
        touch(".gitingestignore");
        touch(".gptignore");
        touch(".ghostcoderignore");
        touch(".piecesignore");
        // AI pull-request reviewers
        touch(".coderabbit.yaml");
        touch(".pr_agent.toml");
        touch("ellipsis.yaml");
        // Editors & modes
        touch(".void/rules.md");
        touch(".roomodes");
        // Machine-readable @AILocked report
        touch(".vibetags-locks");
        // Claude Code local override, Skill, and granular rules; Copilot granular instructions
        touch("CLAUDE.local.md");
        touch(".claude/skills/vibetags-guardrails/SKILL.md");
        // NOTE: the four granular directories whose platform ALSO has an aggregate file
        // (.claude/rules ↔ CLAUDE.md, .cursor/rules ↔ .cursorrules, .windsurf/rules ↔ .windsurfrules,
        // .github/instructions ↔ copilot-instructions.md) are intentionally NOT opted in by default.
        // Co-activating both would collapse the aggregate to a scoped-rules index; the default harness
        // represents the common single-opt-in case (full aggregate output). Tests that want the index
        // behavior opt into the granular sibling explicitly via withExampleSources(dir, extraOptIns…).
    }

    private void touch(String relative) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            Files.createFile(p);
        }
    }

    /**
     * Blocks until the filesystem clock has advanced far enough that a file written after this
     * call is guaranteed a strictly-greater last-modified timestamp than one written before it.
     *
     * <p>The write cache and fingerprint short-circuit compare millisecond mtimes, so mtime-based
     * tests must let the on-disk timestamp move on between two compiles. This adapts to the
     * filesystem's actual mtime granularity (~1 ms on ext4/NTFS/APFS, up to ~2 s on FAT) by
     * probing it directly, instead of always paying a fixed 1.5 s worst-case wait. Capped at 2 s
     * so a coarse or quirky filesystem can never hang the suite.
     */
    static void awaitFilesystemTick(Path dir) throws IOException, InterruptedException {
        Path probe = Files.createTempFile(dir, ".tick", ".tmp");
        try {
            long start = Files.getLastModifiedTime(probe).toMillis();
            long deadlineNanos = System.nanoTime() + 2_000_000_000L; // 2 s safety cap
            while (true) {
                Files.write(probe, new byte[]{1});
                if (Files.getLastModifiedTime(probe).toMillis() > start) {
                    return;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    return;
                }
                Thread.sleep(1);
            }
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /** Queues an in-memory Java source file (fully qualified class name + source content). */
    void addSource(String qualifiedClassName, String content) {
        String path = qualifiedClassName.replace('.', '/') + ".java";
        sources.add(new StringSource(path, content));
    }

    /**
     * Queues a Java source file that exists on disk. Unlike {@link #addSource}, the compiled
     * source has a real {@code file:} URI, so {@code ModuleRootResolver} can walk up from it to
     * a build file — required for multi-module identity tests. The file is compiled as-is; the
     * caller is responsible for writing it (see {@link #writeSourceFile}).
     */
    void addSourceFile(Path file) {
        fileSources.add(file);
    }

    /**
     * Writes {@code content} to {@code relativePath} under the temp root (creating parent
     * directories) and queues it for compilation as a file-backed source.
     */
    Path writeSourceFile(String relativePath, String content) throws IOException {
        Path p = root.resolve(relativePath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        addSourceFile(p);
        return p;
    }

    /** Clears all queued sources so the harness can run a second, different compilation. */
    void clearSources() {
        sources.clear();
        fileSources.clear();
    }

    /**
     * Runs {@link AIGuardrailProcessor} via the Java compiler against the queued sources.
     * The processor option {@code -Avibetags.root} is set to {@link #root} so that all
     * generated AI config files land in the temp directory.
     *
     * @param extraOptions additional compiler options (e.g. {@code "-Avibetags.check=true"})
     */
    void compile(String... extraOptions) {
        compileReturningDiagnostics(extraOptions);
    }

    /**
     * Compiles with a caller-supplied processor instance instead of a plain
     * {@link AIGuardrailProcessor}. Lets a test reproduce a build that wraps the
     * {@code ProcessingEnvironment} the way Gradle's incremental processing does.
     */
    void compileWith(javax.annotation.processing.Processor processor, String... extraOptions) {
        this.processorOverride = processor;
        try {
            compileReturningDiagnostics(extraOptions);
        } finally {
            this.processorOverride = null;
        }
    }

    List<javax.tools.Diagnostic<? extends JavaFileObject>> compileReturningDiagnostics(String... extraOptions) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JavaCompiler unavailable — run tests with a JDK, not a JRE");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = sharedFileManager()) {
            Path classOut = root.resolve("classes");
            Files.createDirectories(classOut);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));

            List<String> options = new ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-proc:only",
                "-Avibetags.root=" + root.toAbsolutePath()
            ));
            options.addAll(List.of(extraOptions));

            List<JavaFileObject> units = new ArrayList<>(sources);
            if (!fileSources.isEmpty()) {
                fm.getJavaFileObjectsFromPaths(fileSources).forEach(units::add);
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, units
            );
            task.setProcessors(List.of(
                processorOverride != null ? processorOverride : new AIGuardrailProcessor()));
            task.call();
            return diagnostics.getDiagnostics();
        } catch (IOException e) {
            throw new RuntimeException("Compilation setup failed", e);
        }
    }

    /** Returns the temp directory that receives all processor output. */
    Path root() {
        return root;
    }

    /** Reads a file relative to the temp root, returning empty string if absent. */
    String readFile(String relative) throws IOException {
        Path p = root.resolve(relative);
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    /** Returns {@code true} if the file exists in the temp root. */
    boolean fileExists(String relative) {
        return Files.exists(root.resolve(relative));
    }

    // -----------------------------------------------------------------------
    // Shared example source set
    // -----------------------------------------------------------------------

    /**
     * Returns a harness pre-loaded with annotated sources that mirror the example project.
     * The annotation mix covers every annotation type so all assertion-level tests pass.
     */
    static ProcessorTestHarness withExampleSources(Path tempDir) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir);
        addExampleSources(h);
        h.compile();
        return h;
    }

    /**
     * Same as {@link #withExampleSources(Path)} but also opts into {@code extraOptIns} before
     * compiling — used by granular tests to activate a granular directory whose aggregate sibling
     * is on by default, so the aggregate collapses to a scoped-rules index and the scoped files are
     * produced. The single-arg overload is unaffected (non-varargs wins for a lone {@code Path}).
     */
    static ProcessorTestHarness withExampleSources(Path tempDir, String... extraOptIns) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir);
        for (String optIn : extraOptIns) {
            h.touchOptIn(optIn);
        }
        addExampleSources(h);
        h.compile();
        return h;
    }

    /**
     * Same annotated sources as {@link #withExampleSources(Path)} but opting in to only the given
     * signal files (no others). Used to exercise the AGENTS.md "sole-file fallback" rule, where
     * AGENTS.md is only managed when it is the only AI config file present.
     */
    static ProcessorTestHarness withExampleSourcesSoleOptIn(Path tempDir, String optInFile) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(tempDir, false);
        h.touchOptIn(optInFile);
        addExampleSources(h);
        h.compile();
        return h;
    }

    private static void addExampleSources(ProcessorTestHarness h) {
        h.addSource("com.example.payment.PaymentProcessor",
            "package com.example.payment;\n" +
            "import se.deversity.vibetags.annotations.AILocked;\n" +
            "@AILocked(reason = \"Core payment logic - do not refactor\")\n" +
            "public class PaymentProcessor {}\n");

        h.addSource("com.example.security.SecurityConfig",
            "package com.example.security;\n" +
            "import se.deversity.vibetags.annotations.*;\n" +
            "@AILocked(reason = \"Security configuration - auth rules must not change without security review\")\n" +
            "@AIContext(focus = \"authentication flow\", avoids = \"reflection-based access\")\n" +
            "public class SecurityConfig {}\n");

        h.addSource("com.example.database.DatabaseConnector",
            "package com.example.database;\n" +
            "import se.deversity.vibetags.annotations.AIAudit;\n" +
            "@AIAudit(checkFor = {\"SQL Injection\", \"Thread Safety issues\"})\n" +
            "public class DatabaseConnector {}\n");

        h.addSource("com.example.internal.GeneratedMetadata",
            "package com.example.internal;\n" +
            "import se.deversity.vibetags.annotations.AIIgnore;\n" +
            "@AIIgnore(reason = \"Auto-generated file - treat as non-existent\")\n" +
            "public class GeneratedMetadata {}\n");

        h.addSource("com.example.utils.StringParser",
            "package com.example.utils;\n" +
            "import se.deversity.vibetags.annotations.AIContext;\n" +
            "@AIContext(focus = \"memory usage\", avoids = \"java.util.regex\")\n" +
            "public class StringParser {}\n");

        h.addSource("com.example.service.NotificationService",
            "package com.example.service;\n" +
            "import se.deversity.vibetags.annotations.AIDraft;\n" +
            "@AIDraft(instructions = \"Implement email sending via SMTP and SMS via Twilio\")\n" +
            "public class NotificationService {}\n");

        h.addSource("com.example.UserProfile",
            "package com.example;\n" +
            "import se.deversity.vibetags.annotations.AIPrivacy;\n" +
            "public class UserProfile {\n" +
            "    @AIPrivacy(reason = \"Contains PII - GDPR protected\")\n" +
            "    private String email;\n" +
            "}\n");

        h.addSource("com.example.core.CriticalService",
            "package com.example.core;\n" +
            "import se.deversity.vibetags.annotations.AICore;\n" +
            "@AICore(sensitivity = \"high\", note = \"payment processing core — battle-tested\")\n" +
            "public class CriticalService {}\n");

        h.addSource("com.example.perf.HotPathRouter",
            "package com.example.perf;\n" +
            "import se.deversity.vibetags.annotations.AIPerformance;\n" +
            "@AIPerformance(constraint = \"O(1) per invocation — no allocations on the hot path\")\n" +
            "public class HotPathRouter {}\n");

        h.addSource("com.example.api.PaymentGateway",
            "package com.example.api;\n" +
            "import se.deversity.vibetags.annotations.AIContract;\n" +
            "public interface PaymentGateway {\n" +
            "    @AIContract(reason = \"External payment gateway API — breaking changes will violate SLA\")\n" +
            "    double charge(String customerId, double amount);\n" +
            "}\n");
    }

    // -----------------------------------------------------------------------
    // Internal: non-closing wrapper around the shared file manager
    // -----------------------------------------------------------------------

    /**
     * Hands the shared {@link #SHARED_FILE_MANAGER} to a compilation task without letting the
     * task's try-with-resources close it. Every other call forwards unchanged.
     *
     * <p>{@link ForwardingJavaFileManager} covers the {@code JavaFileManager} half; the four
     * {@code getJavaFileObjects*} overloads and the {@code File}-based {@code setLocation} /
     * {@code getLocation} come from {@code StandardJavaFileManager} and have to be forwarded by
     * hand.
     */
    private static final class NonClosing
            extends ForwardingJavaFileManager<StandardJavaFileManager>
            implements StandardJavaFileManager {

        NonClosing(StandardJavaFileManager delegate) {
            super(delegate);
        }

        /** No-op: the delegate is shared with every other compilation on this thread. */
        @Override
        public void close() {
            // deliberately not closed
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromFiles(Iterable<? extends File> files) {
            return fileManager.getJavaFileObjectsFromFiles(files);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(File... files) {
            return fileManager.getJavaFileObjects(files);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjectsFromStrings(Iterable<String> names) {
            return fileManager.getJavaFileObjectsFromStrings(names);
        }

        @Override
        public Iterable<? extends JavaFileObject> getJavaFileObjects(String... names) {
            return fileManager.getJavaFileObjects(names);
        }

        @Override
        public void setLocation(Location location, Iterable<? extends File> files) throws IOException {
            fileManager.setLocation(location, files);
        }

        @Override
        public Iterable<? extends File> getLocation(Location location) {
            return fileManager.getLocation(location);
        }
    }

    // -----------------------------------------------------------------------
    // Internal: in-memory Java source file
    // -----------------------------------------------------------------------

    private static final class StringSource extends SimpleJavaFileObject {
        private final String content;

        StringSource(String path, String content) {
            super(URI.create("string:///" + path), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
