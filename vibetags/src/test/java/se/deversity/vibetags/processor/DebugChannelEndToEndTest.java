package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code -Avibetags.log.level=DEBUG} is the channel someone turns on when a build produced the
 * wrong files, and it has two properties that nothing else was checking.
 *
 * <p>First, it must not change what is written. Every debug statement in the processor sits behind
 * {@code if (log != null && log.isDebugEnabled())}, so at INFO those blocks never run — which means
 * an expression inside one is executed for the first time on the build of the person already
 * debugging a problem. {@code log.debug("sidecar.save ...", mySidecar.getModuleBodies().size())}
 * calls an accessor that no other code path calls. A throw there turns "my files look wrong" into
 * "the build fails only when I try to find out why", and the previous run's files stay on disk.
 *
 * <p>Second, the events have to be there. Invariant 15 makes the {@code domain.event key=value}
 * lines a contract: they are how a multi-module build's decisions are reconstructed after the fact,
 * and a renamed or dropped event is a silent loss of the only record of what the processor did.
 *
 * <p>The fixture is a two-module reactor because that is where the interesting decisions are — the
 * per-module sidecar write, the read back of every module's sidecar, and the merged round write.
 * Those three are the debug events a misbehaving reactor build is diagnosed from.
 */
@Tag("e2e")
@DisplayName("the DEBUG diagnostic channel")
class DebugChannelEndToEndTest {

    private static final String CORE_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AILocked;
        import se.deversity.vibetags.annotations.AIContext;

        @AILocked(reason = "settlement maths is regulator-audited")
        @AIContext("the core ledger")
        public class Ledger {
        }
        """;

    private static final String APP_SOURCE = """
        package com.example.app;

        import se.deversity.vibetags.annotations.AIAudit;
        import se.deversity.vibetags.annotations.AISecure;

        @AIAudit(checkFor = {"Path Traversal"})
        @AISecure(aspect = "request validation")
        public class RequestHandler {
        }
        """;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Compiles the two-module reactor into {@code root}, returning the generated files by name. */
    private Map<String, String> buildReactor(Path root, String... extraOptions) throws IOException {
        Files.createDirectories(root.resolve("module-core"));
        Files.createDirectories(root.resolve("module-app"));
        // File presence is the only platform opt-in, so these three decide what gets written.
        Files.createFile(root.resolve("CLAUDE.md"));
        Files.createFile(root.resolve(".cursorrules"));
        Files.createDirectories(root.resolve(".claude").resolve("rules"));

        compileModule(root, "module-core", "com.example.core.Ledger", CORE_SOURCE, extraOptions);
        compileModule(root, "module-app", "com.example.app.RequestHandler", APP_SOURCE, extraOptions);

        return generatedFiles(root);
    }

    private void compileModule(Path root, String module, String fqn, String source,
                               String... extraOptions) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(root, false);
        Files.writeString(root.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(
            module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile(extraOptions);
    }

    /**
     * Every generated guardrail file under {@code root}, keyed by its relative path. Excludes the
     * log itself, the compiler's class output and the sidecars, whose content legitimately differs
     * between two runs (timestamps, absolute paths).
     */
    private static Map<String, String> generatedFiles(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> candidates = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path p : candidates) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("classes/") || rel.startsWith("module-")
                        || rel.equals("vibetags.log") || rel.startsWith(".vibetags-")) {
                    continue;
                }
                files.put(rel, Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    @DisplayName("turning DEBUG on does not change a single generated byte")
    void debugLoggingDoesNotAlterTheGeneratedFiles(@TempDir Path quiet, @TempDir Path loud)
            throws IOException {
        Map<String, String> atInfo = buildReactor(quiet);
        VibeTagsLogger.shutdown();
        Map<String, String> atDebug = buildReactor(loud, "-Avibetags.log.level=DEBUG");

        assertTrue(!atInfo.isEmpty(),
            "the fixture generated nothing, so this comparison would pass vacuously");
        assertEquals(atInfo.keySet(), atDebug.keySet(),
            "DEBUG changed which files were written");
        for (Map.Entry<String, String> entry : atInfo.entrySet()) {
            assertEquals(entry.getValue(), atDebug.get(entry.getKey()),
                entry.getKey() + " differs between an INFO build and a DEBUG build");
        }
    }

    @Test
    @DisplayName("the reactor's decisions are recorded as domain.event key=value lines")
    void theDocumentedReactorEventsAreEmitted(@TempDir Path root) throws IOException {
        buildReactor(root, "-Avibetags.log.level=DEBUG");

        Path log = root.resolve("vibetags.log");
        assertTrue(Files.isRegularFile(log), "DEBUG must produce the log file it documents");
        String content = Files.readString(log, StandardCharsets.UTF_8);

        // Renaming any of these is a breaking change to the diagnostic contract (CLAUDE.md,
        // "Logging"): they are how a reactor build's behaviour is reconstructed afterwards.
        List<String> missing = new ArrayList<>();
        for (String event : List.of("sidecar.save", "sidecar.read", "round.write")) {
            if (!content.contains(event)) {
                missing.add(event);
            }
        }
        assertEquals(List.of(), missing,
            "these DEBUG events are the record of what a multi-module build decided, and the log "
                + "carries none of them:\n" + content);
    }

    @Test
    @DisplayName("every logged event carries key=value pairs, not prose")
    void debugEventsAreStructured(@TempDir Path root) throws IOException {
        buildReactor(root, "-Avibetags.log.level=DEBUG");

        String content = Files.readString(root.resolve("vibetags.log"), StandardCharsets.UTF_8);
        List<String> unstructured = new ArrayList<>();
        for (String event : List.of("sidecar.save", "sidecar.read", "round.write")) {
            for (String line : content.split("\\R")) {
                if (line.contains(event) && !line.contains("=")) {
                    unstructured.add(line.strip());
                }
            }
        }
        assertEquals(List.of(), unstructured,
            "the logging contract is domain.event key=value; a line without a single '=' is prose "
                + "that nothing downstream can read");
    }

    @Test
    @DisplayName("a build at DEBUG reports no errors of its own")
    void debugLoggingRaisesNoErrors(@TempDir Path root) throws IOException {
        buildReactor(root, "-Avibetags.log.level=DEBUG");

        String content = Files.readString(root.resolve("vibetags.log"), StandardCharsets.UTF_8);
        assertTrue(!content.contains("ERROR"),
            "a successful build must not log an error just because the diagnostic channel is "
                + "open:\n" + content);
    }
}
