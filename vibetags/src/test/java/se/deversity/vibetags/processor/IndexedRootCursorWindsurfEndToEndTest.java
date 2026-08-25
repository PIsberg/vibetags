package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The indexed reactor root for Cursor and Windsurf.
 *
 * <p>The root's index pointer is built from three hand-written switches — which granular service
 * governs an aggregate, which directory that service writes into, and what the aggregate file is
 * called. {@code IndexedRootCopilotEndToEndTest} and {@code LeanIndexedRootEndToEndTest} exercise
 * the Claude and Copilot arms of all three. Cursor and Windsurf have their own arms in each, and
 * nothing ran them.
 *
 * <p>A wrong arm produces a pointer that reads perfectly well and names a file that does not
 * exist. That is the worst outcome the indexed root has: the whole design is "the root says where
 * the detail is instead of repeating it", so a pointer nobody can follow does not degrade the
 * aggregate, it empties it. The assertions below are therefore that each named path is a file that
 * was actually written.
 */
@Tag("e2e")
@DisplayName("indexed reactor root, Cursor and Windsurf")
class IndexedRootCursorWindsurfEndToEndTest {

    private static final String CORE_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AIContext;
        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "settlement maths is regulator-audited")
        @AIContext(focus = "the core ledger", avoids = "reflection")
        public class Ledger {
        }
        """;

    private static final String APP_SOURCE = """
        package com.example.app;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "the request edge", avoids = "reflection")
        public class RequestHandler {
        }
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /**
     * A lean indexed root with Cursor and Windsurf aggregates, and each module keeping both its own
     * aggregate and its own scoped-rules directory for both platforms.
     */
    @BeforeEach
    void setUpIndexedReactor() throws IOException {
        Files.createFile(reactorRoot.resolve(".vibetags-root-index"));
        Files.createFile(reactorRoot.resolve(".cursorrules"));
        Files.createFile(reactorRoot.resolve(".windsurfrules"));
        for (String module : new String[]{"module-core", "module-app"}) {
            Path dir = reactorRoot.resolve(module);
            Files.createDirectories(dir.resolve(".cursor/rules"));
            Files.createDirectories(dir.resolve(".windsurf/rules"));
            Files.createFile(dir.resolve(".cursorrules"));
            Files.createFile(dir.resolve(".windsurfrules"));
        }
    }

    private void compileModule(String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(
            module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    private void buildReactor() throws IOException {
        compileModule("module-core", "com.example.core.Ledger", CORE_SOURCE);
        compileModule("module-app", "com.example.app.RequestHandler", APP_SOURCE);
    }

    private String read(String relative) throws IOException {
        return Files.readString(reactorRoot.resolve(relative), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the root aggregate points at each module's own files, and they exist")
    void theRootPointsAtFilesThatWereActuallyWritten() throws IOException {
        buildReactor();

        for (String aggregate : new String[]{".cursorrules", ".windsurfrules"}) {
            String root = read(aggregate);
            for (String module : new String[]{"module-core", "module-app"}) {
                assertTrue(root.contains(module),
                    aggregate + " does not mention " + module + ", so that module's guardrails "
                        + "reach nobody from the root:\n" + root);
                assertTrue(Files.isRegularFile(reactorRoot.resolve(module).resolve(aggregate)),
                    "the root points at " + module + "/" + aggregate + " but nothing wrote it");
            }
        }
    }

    @Test
    @DisplayName("each platform's scoped directory is named with its own path and suffix")
    void eachPlatformsScopedDirectoryIsNamedCorrectly() throws IOException {
        buildReactor();

        // Cursor writes .mdc under .cursor/rules; Windsurf writes .md under .windsurf/rules.
        // Naming one with the other's directory or suffix produces a dangling pointer.
        assertTrue(read(".cursorrules").contains(".cursor/rules"),
            "the Cursor index must name Cursor's directory:\n" + read(".cursorrules"));
        assertTrue(read(".windsurfrules").contains(".windsurf/rules"),
            "the Windsurf index must name Windsurf's directory:\n" + read(".windsurfrules"));

        assertEquals(1, countFiles("module-core/.cursor/rules", ".mdc"),
            "Cursor's scoped rule file for the one annotated class in module-core");
        assertEquals(1, countFiles("module-core/.windsurf/rules", ".md"),
            "Windsurf's scoped rule file for the same class");
    }

    @Test
    @DisplayName("a module's own aggregate carries only that module's guardrails")
    void aModulesAggregateIsScopedToThatModule() throws IOException {
        buildReactor();

        String core = read("module-core/.cursorrules");
        String app = read("module-app/.cursorrules");

        assertTrue(core.contains("Ledger"), "module-core's file must carry its own class:\n" + core);
        assertTrue(!core.contains("RequestHandler"),
            "and not its sibling's, or the indexed layout has bought nothing:\n" + core);
        assertTrue(app.contains("RequestHandler"), app);
        assertTrue(!app.contains("Ledger"), app);
    }

    @Test
    @DisplayName("rebuilding the reactor changes nothing")
    void theIndexedRootConverges() throws IOException {
        buildReactor();
        String cursorBefore = read(".cursorrules");
        String windsurfBefore = read(".windsurfrules");
        String moduleBefore = read("module-core/.cursorrules");

        buildReactor();

        assertEquals(cursorBefore, read(".cursorrules"),
            "a second identical build must not rewrite the root aggregate");
        assertEquals(windsurfBefore, read(".windsurfrules"));
        assertEquals(moduleBefore, read("module-core/.cursorrules"));
    }

    private long countFiles(String relativeDir, String suffix) throws IOException {
        Path dir = reactorRoot.resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        }
    }
}
