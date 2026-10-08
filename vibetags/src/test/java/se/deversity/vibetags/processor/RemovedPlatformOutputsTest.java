package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import se.deversity.vibetags.processor.internal.ServiceRegistry;
import se.deversity.vibetags.processor.internal.content.Platform;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every output deprecated in 1.3.5 (#641) and after it is removed in 1.4: the four of #645
 * ({@code gemini_instructions.md}, {@code .cody/config.json} with {@code .codyignore},
 * {@code .supermavenignore} and the single {@code .clinerules} file) and the seventeen of #720.
 * The deprecation warning was the notice; this pins what removal means to a consumer who never
 * acted on it.
 *
 * <p>A removed file is no longer an opt-in. It is left exactly as the last build wrote it, no
 * warning names it any more, and it is not offered by the opt-in note. Nothing about it is deleted:
 * the file is the user's, and VibeTags does not touch a path it no longer manages.
 */
@Tag("e2e")
@DisplayName("Outputs removed in 1.4 (#645, #720)")
class RemovedPlatformOutputsTest {

    /** The service keys the removal took out. */
    private static final Set<String> REMOVED_KEYS = Set.of(
        // #645
        "gemini", "cody", "cody_ignore", "supermaven_ignore", "cline",
        // #720
        "void", "mentat", "sweep", "plandex", "pearai_granular", "ghostcoder_ignore", "double_ignore",
        "pieces_ignore", "ai_rules_granular", "claude_ignore", "copilot_ignore", "antigravity_ignore",
        "firebase", "amazonq_granular", "interpreter", "ellipsis", "zencoder_granular");

    /** Every removed single-file output, as the user's project held it. */
    private static final List<String> REMOVED_FILES = List.of(
        "gemini_instructions.md", ".cody/config.json", ".codyignore", ".supermavenignore", ".clinerules",
        ".void/rules.md", ".mentatconfig.json", "sweep.yaml", ".plandex.yaml", ".ghostcoderignore",
        ".doubleignore", ".piecesignore", ".claudeignore", ".copilotignore", ".antigravityignore",
        ".idx/airules.md", ".interpreter/profiles/vibetags.yaml", "ellipsis.yaml");

    /** Every removed rules directory (#720). */
    private static final List<String> REMOVED_DIRECTORIES = List.of(
        ".pearai/rules", ".ai/rules", ".amazonq/rules", ".zencoder/rules");

    static Stream<String> removedFiles() {
        return REMOVED_FILES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("removedFiles")
    @DisplayName("a removed file still on disk is left byte-identical and no warning names it")
    void aRemovedFileIsNoLongerWritten(String removed, @TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn("CLAUDE.md");
        Path stale = root.resolve(removed);
        Files.createDirectories(stale.getParent());
        String lastBuild = "# what the last 1.x build wrote\n";
        Files.writeString(stale, lastBuild, StandardCharsets.UTF_8);
        ProcessorTestHarness.addExampleSources(h);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        assertTrue(h.readFile("CLAUDE.md").contains("PaymentProcessor"),
            "the processor ran and wrote CLAUDE.md, so the removed file was really passed over");
        assertEquals(lastBuild, Files.readString(stale, StandardCharsets.UTF_8),
            removed + " is no longer a VibeTags output, so the build must not write to it");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            String message = d.getMessage(null);
            assertFalse(message.contains("deprecated") && message.contains(removed),
                "the deprecation notice for " + removed + " shipped in 1.x; after removal it is noise:\n" + message);
        }
    }

    /**
     * A removed rules directory is not swept either. Its files were VibeTags' while it wrote them,
     * but the orphan sweep is for a directory VibeTags still manages; a directory it no longer knows
     * about is the user's to delete.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {".pearai/rules", ".ai/rules", ".amazonq/rules", ".zencoder/rules"})
    @DisplayName("a removed rules directory still on disk is left exactly as it was")
    void aRemovedDirectoryIsNoLongerWritten(String removed, @TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn("CLAUDE.md");
        Path dir = Files.createDirectories(root.resolve(removed));
        Files.createFile(dir.resolve(".vibetags"));
        Path stale = dir.resolve("com-example-payment-PaymentProcessor.md");
        String lastBuild = "<!-- VIBETAGS-START -->\nwhat the last 1.x build wrote\n<!-- VIBETAGS-END -->\n";
        Files.writeString(stale, lastBuild, StandardCharsets.UTF_8);
        Path gone = dir.resolve("com-example-Gone.md");
        Files.writeString(gone, lastBuild, StandardCharsets.UTF_8);
        ProcessorTestHarness.addExampleSources(h);

        h.compile();

        assertTrue(h.readFile("CLAUDE.md").contains("PaymentProcessor"), "the processor ran");
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(Set.of(".vibetags", "com-example-payment-PaymentProcessor.md", "com-example-Gone.md"),
                files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
                removed + " is no longer a VibeTags output: nothing is added to it and nothing swept from it");
        }
        assertEquals(lastBuild, Files.readString(stale, StandardCharsets.UTF_8), removed + " was rewritten");
    }

    @Test
    @DisplayName("no removed key is an opt-in key, a mapped service or a platform")
    void removedKeysAreGoneFromEveryRegistry(@TempDir Path root) {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(root);
        for (String key : REMOVED_KEYS) {
            assertFalse(ServiceRegistry.optInKeys().contains(key), key + " is still an opt-in key");
            assertFalse(serviceFiles.containsKey(key), key + " still maps to a path");
            assertNull(Platform.fromServiceKey(key), key + " still names a Platform");
        }
        for (String file : REMOVED_FILES) {
            if (!".clinerules".equals(file)) {
                assertFalse(serviceFiles.containsValue(root.resolve(file)), file + " is still a service path");
            }
        }
        for (String dir : REMOVED_DIRECTORIES) {
            assertFalse(serviceFiles.containsValue(root.resolve(dir)), dir + " is still a service path");
        }
    }

    /**
     * With every deprecated output removed, nothing is deprecated: a project holding all of them
     * gets no deprecation warning at all, and the build that used to print one per compilation is
     * silent about them.
     */
    @Test
    @DisplayName("a project holding every removed output gets no deprecation warning")
    void nothingIsDeprecatedAnyMore(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn("CLAUDE.md");
        for (String file : REMOVED_FILES) {
            Path p = root.resolve(file);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "", StandardCharsets.UTF_8);
        }
        for (String dir : REMOVED_DIRECTORIES) {
            Files.createDirectories(root.resolve(dir));
        }
        ProcessorTestHarness.addExampleSources(h);

        List<Diagnostic<? extends JavaFileObject>> diagnostics = h.compileReturningDiagnostics();

        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            String message = d.getMessage(null);
            assertFalse(message.contains("deprecated") && message.contains("opted-in"),
                "no output is deprecated after 1.4:\n" + message);
        }
    }

    @Test
    @DisplayName("a .clinerules file activates nothing, while the .clinerules/ directory still activates Cline")
    void onlyTheClineDirectoryIsAnOptIn(@TempDir Path root) throws IOException {
        Path fileRoot = Files.createDirectories(root.resolve("file"));
        Files.createFile(fileRoot.resolve(".clinerules"));
        Path dirRoot = Files.createDirectories(root.resolve("dir"));
        Files.createDirectories(dirRoot.resolve(".clinerules"));

        assertEquals(Set.of(), ServiceRegistry.resolveActiveServices(ServiceRegistry.buildServiceFileMap(fileRoot)),
            "the single .clinerules file was removed; the path now belongs only to the directory form");
        assertEquals(Set.of("cline_granular"),
            ServiceRegistry.resolveActiveServices(ServiceRegistry.buildServiceFileMap(dirRoot)),
            "the directory form is the replacement and must keep working");
    }

    /**
     * {@code .aiexclude} used to be written only beside {@code gemini_instructions.md} or an active
     * {@code AGENTS.md}. With the first removed, a Gemini user who followed the deprecation notice to
     * {@code GEMINI.md} would lose {@code .aiexclude} regeneration without a word, so {@code GEMINI.md}
     * takes the removed file's place in that pairing.
     */
    @Test
    @DisplayName(".aiexclude is written beside GEMINI.md, the file the deprecation notice sent Gemini users to")
    void aiexcludeFollowsGeminiMd(@TempDir Path root) throws IOException {
        ProcessorTestHarness h = new ProcessorTestHarness(root, false);
        h.touchOptIn("GEMINI.md");
        h.touchOptIn(".aiexclude");
        ProcessorTestHarness.addExampleSources(h);

        h.compile();

        assertTrue(h.readFile("GEMINI.md").contains("PaymentProcessor"), "GEMINI.md was generated");
        String aiexclude = h.readFile(".aiexclude");
        assertTrue(aiexclude.contains("GeneratedMetadata.java"),
            ".aiexclude carries the @AIIgnore glob when GEMINI.md is its Gemini sibling:\n" + aiexclude);
    }
}
