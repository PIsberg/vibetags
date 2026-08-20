package se.deversity.vibetags.processor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The indexed reactor root with Copilot's aggregate and granular directory at the <em>root</em>
 * (<a href="https://github.com/PIsberg/vibetags/issues/319">issue #319</a>).
 *
 * <p>The combination was untested: {@code examples/multimodule-indexed/} had no
 * {@code .github/copilot-instructions.md}, so nothing in CI exercised the path where a module keeps
 * its own {@code .claude/rules/} — and so gets a pointer in {@code CLAUDE.md} — while its Copilot
 * rules live in the reactor root's shared {@code .github/instructions/}. Two defects hid there: the
 * shared granular directory only ever kept the last module's files, and each module's contribution
 * repeated an empty "Locked Files" heading, so an aggregate whose whole purpose is to be lean grew
 * on a version bump.
 */
@Tag("e2e")
class IndexedRootCopilotEndToEndTest {

    private static final String CORE_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AIContext;
        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "CORE LOCKED REASON")
        @AIContext(focus = "CORE VERBOSE FOCUS", avoids = "reflection")
        public class DocumentModel {
        }
        """;

    /** Deliberately has nothing in the safety tier — the module that used to emit empty headings. */
    private static final String APP_SOURCE = """
        package com.example.app;

        import se.deversity.vibetags.annotations.AIContext;

        @AIContext(focus = "APP VERBOSE FOCUS", avoids = "reflection")
        public class DocumentService {
        }
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /**
     * The blindbean layout: lean index at the root, Copilot's aggregate AND its granular directory
     * at the root, and per-module {@code .claude/rules/} but no per-module Copilot directory.
     */
    @BeforeEach
    void setUpIndexedReactor() throws IOException {
        Files.createFile(reactorRoot.resolve(".vibetags-root-index"));
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.createDirectories(reactorRoot.resolve(".github/instructions"));
        Files.createFile(reactorRoot.resolve(".github/copilot-instructions.md"));
        for (String module : new String[]{"module-core", "module-app"}) {
            Files.createDirectories(reactorRoot.resolve(module).resolve(".claude/rules"));
        }
    }

    private void compileModule(String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve(module));
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    private void compileReactor() throws IOException {
        compileModule("module-core", "com.example.core.DocumentModel", CORE_SOURCE);
        compileModule("module-app", "com.example.app.DocumentService", APP_SOURCE);
    }

    @Test
    void copilotAggregateCollapsesToAScopedRulesIndex() throws IOException {
        compileReactor();

        String copilot = Files.readString(reactorRoot.resolve(".github/copilot-instructions.md"));
        assertTrue(copilot.contains("## Scoped Rules Index"),
            "the Copilot aggregate must collapse, exactly as CLAUDE.md does:\n" + copilot);
        assertTrue(copilot.contains(".github/instructions/com-example-core-DocumentModel.instructions.md"),
            "…pointing at the scoped file it actually wrote:\n" + copilot);
        assertFalse(copilot.contains("CORE VERBOSE FOCUS"),
            "the verbose tier belongs in the scoped file, not the always-loaded aggregate:\n" + copilot);
        assertTrue(copilot.contains("CORE LOCKED REASON"),
            "the safety tier stays inline (#332):\n" + copilot);
    }

    /**
     * Every module writes into the <em>same</em> root-level {@code .github/instructions/}, so the
     * cleanup pass has to spare the files it did not write. It used to delete them, leaving only
     * the last module's — two files where the build had produced seven.
     */
    @Test
    void everyModuleKeepsItsFilesInTheSharedGranularDirectory() throws IOException {
        compileReactor();

        List<String> instructions = fileNames(reactorRoot.resolve(".github/instructions"));
        assertTrue(instructions.contains("com-example-core-DocumentModel.instructions.md"),
            "module-core's scoped file must survive module-app's compile: " + instructions);
        assertTrue(instructions.contains("com-example-app-DocumentService.instructions.md"),
            "…and module-app's must be there too: " + instructions);
        assertEquals(2, instructions.size(), instructions.toString());
    }

    /**
     * The reactor merge repeats each module's preamble, so an empty heading is paid once per
     * module. A module with nothing locked must contribute no locked section at all.
     */
    @Test
    void aModuleWithNothingLockedContributesNoEmptyHeading() throws IOException {
        compileReactor();

        String copilot = Files.readString(reactorRoot.resolve(".github/copilot-instructions.md"));
        // module-core locks something, module-app does not — so exactly one heading, not two.
        assertEquals(1, countOccurrences(copilot, "## Locked Files"),
            "one locked heading, from the one module that locks something:\n" + copilot);
        assertFalse(copilot.contains("Do not suggest changes to the following files:\n\n\n"),
            "no heading may be followed by an empty list:\n" + copilot);
    }

    /** The same suppression on the Claude side, where the empty element is {@code <locked_files/>}. */
    @Test
    void indexedClaudeOutputOmitsAnEmptyLockedFilesElement() throws IOException {
        compileReactor();

        String claude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        // Count the opening tag, not the string: the trailing <rule> sentence names <locked_files>
        // too, so a bare substring count would be one higher than the number of elements emitted.
        assertEquals(1, countOccurrences(claude, "  <locked_files>\n"),
            "only the module that locks something may emit the element:\n" + claude);
        assertFalse(claude.contains("  <locked_files>\n  </locked_files>"),
            "an empty locked_files element is pure noise in a lean root:\n" + claude);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static List<String> fileNames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
        }
    }
}
