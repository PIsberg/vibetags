package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import se.deversity.vibetags.processor.internal.DeprecatedServices;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Four outputs were deprecated in the last 1.x release (#641) and removed in 2.0.0 (#645): {@code gemini_instructions.md},
 * {@code .cody/config.json} with {@code .codyignore}, {@code .supermavenignore}, and the single
 * {@code .clinerules} file. The deprecation warning was the notice; this pins what removal means to
 * a consumer who never acted on it.
 *
 * <p>A removed file is no longer an opt-in. It is left exactly as the last build wrote it, no
 * warning names it any more, and it is not offered by the opt-in note. Nothing about it is deleted:
 * the file is the user's, and VibeTags does not touch a path it no longer manages.
 */
@Tag("e2e")
@DisplayName("Outputs removed in 2.0.0 (#645)")
class RemovedPlatformOutputsTest {

    /** The service keys the removal took out. */
    private static final Set<String> REMOVED_KEYS =
        Set.of("gemini", "cody", "cody_ignore", "supermaven_ignore", "cline");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"gemini_instructions.md", ".cody/config.json", ".codyignore", ".supermavenignore", ".clinerules"})
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

    @Test
    @DisplayName("no removed key is an opt-in key, a mapped service, a platform or a deprecation row")
    void removedKeysAreGoneFromEveryRegistry(@TempDir Path root) {
        Map<String, Path> serviceFiles = ServiceRegistry.buildServiceFileMap(root);
        for (String key : REMOVED_KEYS) {
            assertFalse(ServiceRegistry.optInKeys().contains(key), key + " is still an opt-in key");
            assertFalse(serviceFiles.containsKey(key), key + " still maps to a path");
            assertNull(Platform.fromServiceKey(key), key + " still names a Platform");
            assertFalse(DeprecatedServices.keys().contains(key),
                key + " is still warned about as deprecated, though it is gone");
        }
        for (String file : List.of("gemini_instructions.md", ".cody/config.json", ".codyignore", ".supermavenignore")) {
            assertFalse(serviceFiles.containsValue(root.resolve(file)), file + " is still a service path");
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
