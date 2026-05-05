package se.deversity.vibetags.processor;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
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

    private final Path root;
    private final List<JavaFileObject> sources = new ArrayList<>();

    ProcessorTestHarness(Path tempDir) throws IOException {
        this.root = tempDir;
        createOptInFiles();
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
        touch(".cursor/rules/.vibetags"); // Create a hidden file to signal directory existence
        touch(".trae/rules/.vibetags");
        touch(".roo/rules/.vibetags");
        // New platforms
        touch(".windsurfrules");
        touch(".rules");
        touch(".cody/config.json");
        touch(".codyignore");
        touch(".supermavenignore");
        touch(".windsurf/rules/.vibetags");
        touch(".continue/rules/.vibetags");
        touch(".tabnine/guidelines/.vibetags");
        touch(".amazonq/rules/.vibetags");
        touch(".ai/rules/.vibetags");
    }

    private void touch(String relative) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            Files.createFile(p);
        }
    }

    /** Queues an in-memory Java source file (fully qualified class name + source content). */
    void addSource(String qualifiedClassName, String content) {
        String path = qualifiedClassName.replace('.', '/') + ".java";
        sources.add(new StringSource(path, content));
    }

    /**
     * Runs {@link AIGuardrailProcessor} via the Java compiler against the queued sources.
     * The processor option {@code -Avibetags.root} is set to {@link #root} so that all
     * generated AI config files land in the temp directory.
     */
    void compile() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JavaCompiler unavailable — run tests with a JDK, not a JRE");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            Path classOut = root.resolve("classes");
            Files.createDirectories(classOut);
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOut.toFile()));

            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-proc:only",
                "-Avibetags.root=" + root.toAbsolutePath()
            );

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, sources
            );
            task.setProcessors(List.of(new AIGuardrailProcessor()));
            task.call();
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

        h.compile();
        return h;
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
