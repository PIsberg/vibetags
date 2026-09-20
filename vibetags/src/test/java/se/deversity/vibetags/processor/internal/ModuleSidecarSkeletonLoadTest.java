package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skeleton load classifies a sidecar exactly as the full load does.
 *
 * <p>{@code unreadableSidecarNames} parses every sidecar in full — base64-decoding every body,
 * splitting them, parsing every granular contribution and populating every map — only to ask
 * whether the result is {@code UNREADABLE} or {@code FUTURE_VERSION}. Measured on the three-module
 * example, that is 21 of the build's 57 sidecar loads, each decoding around 46 values.
 *
 * <p>The skeleton load skips the materialising, and the risk that creates is precise: a body whose
 * base64 is corrupt must still be judged corrupt. The full load learns that from
 * {@code Base64.getDecoder().decode} throwing, which is the same call the skeleton keeps, and this
 * test is what holds the two classifications together. A skeleton that stopped validating would
 * report a corrupt sidecar as loadable, and the caller prunes on that answer.
 */
class ModuleSidecarSkeletonLoadTest {

    private static Path sidecarWithBody(Path root, String moduleId, String body) throws IOException {
        Files.createDirectories(root.resolve(moduleId));
        ModuleSidecar s = new ModuleSidecar(moduleId, moduleId);
        s.putBody("claude", body);
        s.save(root);
        return root.resolve(".vibetags-mod-" + moduleId);
    }

    /** Replaces the first encoded body value with something that is not base64. */
    private static void corruptFirstBody(Path sidecar) throws IOException {
        List<String> lines = Files.readAllLines(sidecar, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (line.startsWith("claude=") && eq > 0) {
                lines.set(i, "claude=!!!not base64!!!");
                Files.write(sidecar, lines, StandardCharsets.UTF_8);
                return;
            }
        }
        throw new AssertionError("no encoded body found to corrupt in " + sidecar);
    }

    @Test
    @DisplayName("a healthy sidecar keeps the headers the skeleton callers read")
    void skeletonKeepsTheHeaders(@TempDir Path root) throws IOException {
        Path sidecar = sidecarWithBody(root, "core", "some body");

        ModuleSidecar full = ModuleSidecar.load(sidecar);
        ModuleSidecar skeleton = ModuleSidecar.loadSkeleton(sidecar);

        assertNotNull(full);
        assertNotNull(skeleton);
        assertEquals(full.getModuleId(), skeleton.getModuleId());
        assertEquals(full.getModulePath(), skeleton.getModulePath());
        assertEquals(full.getRegionId(), skeleton.getRegionId());
        assertEquals(full.getElementIds(), skeleton.getElementIds());
    }

    @Test
    @DisplayName("the skeleton does not materialise the bodies it skipped")
    void skeletonSkipsTheBodies(@TempDir Path root) throws IOException {
        Path sidecar = sidecarWithBody(root, "core", "some body");

        ModuleSidecar skeleton = ModuleSidecar.loadSkeleton(sidecar);

        assertNotNull(skeleton);
        assertTrue(skeleton.getBodies().isEmpty(),
            "the skeleton exists to skip this work; a caller that needs a body must use load()");
    }

    @Test
    @DisplayName("a corrupt body is corrupt under both loads")
    void corruptBodyIsMalformedUnderBothLoads(@TempDir Path root) throws IOException {
        Path sidecar = sidecarWithBody(root, "core", "some body");
        corruptFirstBody(sidecar);

        assertNull(ModuleSidecar.load(sidecar),
            "the full load judges an undecodable body corrupt, and the caller prunes on that");
        assertNull(ModuleSidecar.loadSkeleton(sidecar),
            "the skeleton must reach the same verdict, or a corrupt sidecar survives pruning");
    }

    @Test
    @DisplayName("unreadable and future-version classify identically too")
    void sentinelsMatch(@TempDir Path root) throws IOException {
        Path unreadable = Files.createDirectory(root.resolve(".vibetags-mod-locked"));
        assertSame(ModuleSidecar.load(unreadable), ModuleSidecar.loadSkeleton(unreadable),
            "a sidecar that cannot be read is UNREADABLE either way, never corrupt");

        Path future = root.resolve(".vibetags-mod-future");
        Files.writeString(future, "version=99999\nmoduleId=x\n", StandardCharsets.UTF_8);
        assertSame(ModuleSidecar.load(future), ModuleSidecar.loadSkeleton(future),
            "a newer format is FUTURE_VERSION either way, and must never be pruned");
    }
}
