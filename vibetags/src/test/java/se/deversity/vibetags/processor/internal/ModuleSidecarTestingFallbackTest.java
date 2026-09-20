package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ~tfull~<service>} sidecar key: what a routed test round would have written to a
 * service's file had {@code TESTING.md} not been there.
 *
 * <p>A routed round stores the safety half as the service's ordinary body. If {@code TESTING.md}
 * is then deleted and only the main sources are rebuilt, that stored half is all the merge has,
 * and the rest of the test guardrails are in no file. The fallback body is what the merge reads
 * instead once the opt-in is gone. Real sidecar files throughout: the format is shared between
 * independently compiled modules and between processor versions.
 */
class ModuleSidecarTestingFallbackTest {

    private static final String MAIN_BODY = "MAIN-GUARDRAILS";
    private static final String ROUTED_BODY = "TEST-SAFETY-ONLY";
    private static final String UNROUTED_BODY = "TEST-SAFETY-AND-EVERYTHING-ELSE";

    @TempDir
    Path root;

    @BeforeEach
    void moduleDirectory() throws IOException {
        Files.createDirectories(root.resolve("core"));
    }

    private void saveMainAndRoutedTestSidecars() throws IOException {
        ModuleSidecar main = new ModuleSidecar("core", "core", "core");
        main.putBody("claude", MAIN_BODY);
        main.save(root);
        ModuleSidecar tests = new ModuleSidecar("core__test", "core", "core");
        tests.putBody("claude", ROUTED_BODY);
        tests.putUnroutedBody("claude", UNROUTED_BODY);
        tests.save(root);
    }

    private String mergedClaude() {
        return ModuleSidecar.mergeFor("claude", ModuleSidecar.readAll(root), true);
    }

    @Test
    void theUnroutedBodyRoundTripsThroughTheFile() throws IOException {
        saveMainAndRoutedTestSidecars();

        ModuleSidecar loaded = ModuleSidecar.loadFor(root, "core__test");

        assertNotNull(loaded);
        assertEquals(Map.of("claude", UNROUTED_BODY), loaded.getUnroutedBodies());
        assertEquals(Map.of("claude", ROUTED_BODY), loaded.getBodies(),
            "a reserved key must never turn up as a service body, where it would be rendered");
    }

    @Test
    void whileTestingMdIsPresentTheRoutedBodyIsTheTruth() throws IOException {
        saveMainAndRoutedTestSidecars();
        Files.createFile(root.resolve("TESTING.md"));

        String merged = mergedClaude();

        assertTrue(merged.contains(MAIN_BODY) && merged.contains(ROUTED_BODY), merged);
        assertFalse(merged.contains(UNROUTED_BODY), merged);
    }

    @Test
    void onceTestingMdIsGoneTheMergeFallsBackToTheUnroutedBody() throws IOException {
        saveMainAndRoutedTestSidecars();

        String merged = mergedClaude();

        assertTrue(merged.contains(MAIN_BODY) && merged.contains(UNROUTED_BODY), merged);
        assertFalse(merged.contains(ROUTED_BODY), merged);
    }

    /**
     * A directory named TESTING.md is not the opt-in, by the same rule {@code ServiceRegistry}
     * applies everywhere else (a bare existence check was issue #642).
     */
    @Test
    void aDirectoryNamedTestingMdIsNotTheOptIn() throws IOException {
        saveMainAndRoutedTestSidecars();
        Files.createDirectory(root.resolve("TESTING.md"));

        assertTrue(mergedClaude().contains(UNROUTED_BODY));
    }

    /**
     * A routed round whose test code carries no safety annotation may have no ordinary body for a
     * service at all. The fallback still has to be found.
     */
    @Test
    void theFallbackIsFoundEvenWhenTheRoutedBodyIsMissing() throws IOException {
        ModuleSidecar main = new ModuleSidecar("core", "core", "core");
        main.putBody("claude", MAIN_BODY);
        main.save(root);
        ModuleSidecar tests = new ModuleSidecar("core__test", "core", "core");
        tests.putUnroutedBody("claude", UNROUTED_BODY);
        tests.putBody("cursor", "keeps the sidecar non-empty");
        tests.save(root);

        assertTrue(mergedClaude().contains(UNROUTED_BODY), mergedClaude());
    }

    /** Sidecars from older processors and from unrouted rounds: both states merge as they always did. */
    @Test
    void aSidecarWithNoFallbackMergesTheSameWithOrWithoutTestingMd() throws IOException {
        ModuleSidecar main = new ModuleSidecar("core", "core", "core");
        main.putBody("claude", MAIN_BODY);
        main.save(root);
        ModuleSidecar tests = new ModuleSidecar("core__test", "core", "core");
        tests.putBody("claude", ROUTED_BODY);
        tests.save(root);

        String without = mergedClaude();
        Files.createFile(root.resolve("TESTING.md"));
        String with = mergedClaude();

        assertEquals(without, with);
        assertTrue(with.contains(MAIN_BODY) && with.contains(ROUTED_BODY), with);
    }

    /**
     * What an older processor does with a {@code ~tfull~} line is what this one does with a key it
     * has never heard of: load the rest, store nothing for it, render nothing from it.
     */
    @Test
    void anUnknownReservedKeyIsSkippedAndTheRestStillLoads() throws IOException {
        saveMainAndRoutedTestSidecars();
        Path file = root.resolve(".vibetags-mod-core__test");
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        String encoded = Base64.getEncoder().encodeToString("FROM-THE-FUTURE".getBytes(StandardCharsets.UTF_8));
        lines.add(lines.size() - 1, "~zzz~claude=" + encoded);
        Files.write(file, lines, StandardCharsets.UTF_8);

        ModuleSidecar loaded = ModuleSidecar.loadFor(root, "core__test");

        assertNotNull(loaded, "an unknown reserved key must not make the sidecar unreadable");
        assertEquals(Map.of("claude", ROUTED_BODY), loaded.getBodies());
        assertFalse(mergedClaude().contains("FROM-THE-FUTURE"));
    }
}
