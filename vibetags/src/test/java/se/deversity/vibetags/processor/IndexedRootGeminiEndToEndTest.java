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
 * <p>{@code GEMINI.md} collapses to a scoped-rules index in a single module (#320):
 * {@code GranularIndexSection} has a {@code GEMINI_MD} case beside {@code CLAUDE}, {@code CURSOR},
 * {@code WINDSURF} and {@code COPILOT}. It is absent from
 * {@code ModuleSidecar.INDEXABLE_AGGREGATES}, which lists four, so in a
 * {@code .vibetags-root-index} reactor it is not one of the aggregates a module's body is replaced
 * by a pointer in.
 *
 * <p><a href="https://github.com/PIsberg/vibetags/issues/763">Issue #763</a> reports that as drift
 * found by reading. This test settles what the code actually does, and pins it either way, because
 * the asymmetry is currently written down nowhere: a reader comparing the two lists cannot tell a
 * decision from an oversight, and neither could a test that did not exist.
 *
 * <p>It deliberately asserts today's behaviour rather than the behaviour #763 might choose. If
 * {@code gemini_md} is added to {@code INDEXABLE_AGGREGATES}, this test fails and names the
 * decision, which is the point: changing it changes generated output in every consuming reactor
 * that has both files.
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
     * The measurement #763 asked for. CLAUDE.md is in {@code INDEXABLE_AGGREGATES} and GEMINI.md is
     * not, so the root CLAUDE.md replaces the module's body with a pointer while the root GEMINI.md
     * keeps it embedded. Both files are opted into, in the same reactor, on the same round.
     */
    @Test
    @DisplayName("the root CLAUDE.md points at the module while the root GEMINI.md embeds it")
    void geminiIsNotTreatedAsAnIndexableAggregateAtTheReactorRoot() throws IOException {
        compileModule("module-core", "com.example.core.DocumentModel", CORE_SOURCE);

        String rootClaude = Files.readString(reactorRoot.resolve("CLAUDE.md"));
        String rootGemini = Files.readString(reactorRoot.resolve("GEMINI.md"));

        assertFalse(rootClaude.contains("CORE VERBOSE FOCUS"),
            "precondition: the lean root index replaces a module's CLAUDE.md body with a pointer, "
                + "which is what .vibetags-root-index is for:\n" + rootClaude);

        // Today's behaviour, and the asymmetry #763 names. Not asserted as desirable: asserted so
        // that changing it is a decision somebody makes on purpose rather than a diff nobody
        // notices in a consumer's committed files.
        assertTrue(rootGemini.contains("CORE VERBOSE FOCUS"),
            "GEMINI.md is absent from ModuleSidecar.INDEXABLE_AGGREGATES, so the reactor root "
                + "keeps the module's verbose tier embedded here while CLAUDE.md gets a pointer. "
                + "If this now fails, gemini_md was added to that list: that is issue #763's "
                + "decision, and it changes generated output in every reactor with both files.\n"
                + rootGemini);
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
