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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code GEMINI.md} does in a lean indexed reactor root, measured.
 *
 * <p>{@code GEMINI.md} collapses to a scoped-rules index in a single module (#320). Until
 * <a href="https://github.com/PIsberg/vibetags/issues/763">issue #763</a> it was absent from
 * {@code ModuleSidecar.INDEXABLE_AGGREGATES}, so in a {@code .vibetags-root-index} reactor the root
 * {@code CLAUDE.md} got a pointer to the module while the root {@code GEMINI.md} kept the module's
 * verbose tier embedded: a lean root for one file and a fat root for the other, from one build.
 * That asymmetry was written down nowhere, and this test first pinned it so that changing it would
 * be a decision.
 *
 * <p>The decision was taken on 2026-09-21: {@code GEMINI.md} goes lean too. Both lists now read
 * one {@code GranularPairing} table, so they cannot disagree again, and this test asserts the two
 * root files behave alike. The Gemini pointer sends the agent to the rule files rather than
 * promising they load by themselves, because Gemini CLI does not read {@code .gemini/rules/}
 * (#669).
 */
@Tag("e2e")
@DisplayName("GEMINI.md in a lean indexed reactor root")
class IndexedRootGeminiEndToEndTest {

    private static final String CORE_SOURCE = """
        package com.example.core;

        import se.deversity.vibetags.annotations.AIContext;
        import se.deversity.vibetags.annotations.AILocked;

        @AILocked(reason = "CORE LOCKED REASON")
        @AIContext(focus = "CORE VERBOSE FOCUS", avoids = "reflection")
        public class DocumentModel {
        }
        """;

    @TempDir
    Path reactorRoot;

    @AfterEach
    void tearDown() {
        VibeTagsLogger.shutdown();
    }

    /** Lean index at the root, with both CLAUDE.md and GEMINI.md opted in, plus per-module rules. */
    @BeforeEach
    void setUpIndexedReactor() throws IOException {
        Files.createFile(reactorRoot.resolve(".vibetags-root-index"));
        Files.createFile(reactorRoot.resolve("CLAUDE.md"));
        Files.createFile(reactorRoot.resolve("GEMINI.md"));
        Files.createDirectories(reactorRoot.resolve("module-core").resolve(".claude/rules"));
        Files.createDirectories(reactorRoot.resolve("module-core").resolve(".gemini/rules"));
        Files.createFile(reactorRoot.resolve("module-core").resolve("CLAUDE.md"));
        Files.createFile(reactorRoot.resolve("module-core").resolve("GEMINI.md"));
    }

    private void compileModule(String module, String fqn, String source) throws IOException {
        ProcessorTestHarness harness = new ProcessorTestHarness(reactorRoot, false);
        Files.createDirectories(reactorRoot.resolve(module));
        Files.writeString(reactorRoot.resolve(module).resolve("pom.xml"),
            "<project><artifactId>" + module + "</artifactId></project>", StandardCharsets.UTF_8);
        harness.writeSourceFile(module + "/src/main/java/" + fqn.replace('.', '/') + ".java", source);
        harness.compile();
    }

    /**
     * Both files are opted into, in the same reactor, on the same round, and both roots replace the
     * module's verbose tier with a pointer while keeping its safety tier inline.
     */
    @Test
    @DisplayName("the root GEMINI.md points at the module, as the root CLAUDE.md does")
    void geminiIsAnIndexableAggregateAtTheReactorRoot() throws IOException {
        compileModule("module-core", "com.example.core.DocumentModel", CORE_SOURCE);

        String rootClaude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        String rootGemini = Files.readString(reactorRoot.resolve("GEMINI.md"));

        assertFalse(rootClaude.contains("CORE VERBOSE FOCUS"),
            "precondition: the lean root index replaces a module's CLAUDE.md body with a pointer, "
                + "which is what .vibetags-root-index is for:\n" + rootClaude);

        assertFalse(rootGemini.contains("CORE VERBOSE FOCUS"),
            "the root GEMINI.md must replace the verbose tier of the module with a pointer, the way the "
                + "root CLAUDE.md does (#763):\n" + rootGemini);
        assertTrue(rootGemini.contains("CORE LOCKED REASON"),
            "the safety tier stays inline at the root, or @AILocked stops being always-on "
                + "(#332):\n" + rootGemini);
        assertTrue(rootGemini.contains("module-core/.gemini/rules"),
            "the pointer names where the rules of the module live:\n" + rootGemini);
        assertFalse(rootGemini.contains("loaded automatically"),
            "Gemini CLI does not read .gemini/rules/ by itself (#669), so the pointer must not "
                + "promise that it does:\n" + rootGemini);
    }

    /**
     * The half that is not in doubt: a module's own GEMINI.md still collapses, because
     * {@code GranularIndexSection} does know about it (#320). The two behaviours are consistent
     * with each other only if the root-level omission is deliberate.
     */
    @Test
    @DisplayName("a module's own GEMINI.md still collapses to a scoped-rules index")
    void aModuleGeminiStillCollapsesToAnIndex() throws IOException {
        compileModule("module-core", "com.example.core.DocumentModel", CORE_SOURCE);

        Path moduleGemini = reactorRoot.resolve("module-core").resolve("GEMINI.md");
        String gemini = Files.readString(moduleGemini);

        assertTrue(gemini.contains("CORE LOCKED REASON"),
            "the safety tier stays inline in the module's own aggregate:\n" + gemini);
        assertFalse(gemini.contains("CORE VERBOSE FOCUS"),
            "and the verbose tier moves to .gemini/rules/, which is #320's behaviour and is not in "
                + "question here:\n" + gemini);
    }
}
