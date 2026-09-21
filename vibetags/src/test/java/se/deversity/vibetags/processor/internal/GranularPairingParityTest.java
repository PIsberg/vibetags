package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import se.deversity.vibetags.processor.internal.content.GranularPairing;
import se.deversity.vibetags.processor.internal.content.Platform;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GranularPairing} spells each aggregate file and rules directory as a string, because the
 * rendering layer may not import {@link ServiceRegistry}, which owns the real paths
 * (<a href="https://github.com/PIsberg/vibetags/issues/763">issue #763</a>). This holds the two to
 * the same answer, so a pointer in a lean root index can never name a path the registry does not
 * write to.
 *
 * <p>Compared with forward slashes on every OS: the pairing's strings go into generated text, where
 * a backslash would be wrong on Windows too.
 */
class GranularPairingParityTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    private static String relative(Path p) {
        return ROOT.relativize(p).toString().replace(java.io.File.separatorChar, '/');
    }

    @Test
    void everyPairingNamesThePathsTheRegistryWrites() {
        Map<String, Path> files = ServiceRegistry.buildServiceFileMap(ROOT);
        for (GranularPairing pairing : GranularPairing.values()) {
            Path aggregate = files.get(pairing.aggregateKey());
            Path dir = files.get(pairing.granularKey());
            assertNotNull(aggregate, pairing + ": no service path for " + pairing.aggregateKey());
            assertNotNull(dir, pairing + ": no service path for " + pairing.granularKey());
            assertEquals(relative(aggregate), pairing.aggregateFile(), pairing + " aggregate file");
            assertEquals(relative(dir), pairing.scopedDir(), pairing + " rules directory");
            assertTrue(ServiceRegistry.writesDirectory(pairing.granularKey()),
                pairing + ": " + pairing.granularKey() + " must be a directory service");
        }
    }

    @Test
    void everyPairingKeyIsAPlatform() {
        for (GranularPairing pairing : GranularPairing.values()) {
            assertNotNull(Platform.fromServiceKey(pairing.aggregateKey()), pairing.aggregateKey());
            assertNotNull(Platform.fromServiceKey(pairing.granularKey()), pairing.granularKey());
        }
    }
}
